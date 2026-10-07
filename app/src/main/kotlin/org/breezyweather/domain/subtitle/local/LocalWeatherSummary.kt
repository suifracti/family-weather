package org.breezyweather.domain.subtitle.local

import org.breezyweather.domain.multisource.model.TimestampedCue
import org.breezyweather.domain.subtitle.gate.EpisodeSummaryGate
import org.breezyweather.domain.subtitle.model.EpisodeSummary
import org.breezyweather.domain.subtitle.model.EpisodeSummaryItem
import org.breezyweather.domain.subtitle.model.SubtitleManifest

/** Replayable, verbatim ASR selections. This does not establish that the ASR or forecast is correct. */
object LocalWeatherSummary {
    private val topics = listOf("暴雨", "降雨", "降水", "台风", "高温", "冷空气", "降温", "气温", "大风", "雷", "雪", "雨", "天气", "温度")
    private val advice = Regex("注意|防范|警惕|建议|务必|留意|合理规划")
    // These introduce a new narrated section, rather than a continuation such as “明天” after “今晚到”.
    private val sectionStart = Regex("^(?:眼下|接下来|再来(?:看|关注)|再看|最后几天|未来几天|展望假期|放眼假期|最后(?:我们)?(?:来看|关注)|(?:另外|此外)?随着.{0,8}冷空气(?:的)?(?:到来|影响)|.{0,4}方面(?:受到|受)冷空气(?:的)?影响)")
    private val citySection = Regex("(?:咱们|我们|下面|最后|接下来|现在).{0,4}(?:关注|来看|看一下).{0,8}城市")
    private val continuationStart = Regex("^(?:而|但|不过|同时|此外|另外|其中|因此|所以|以及|和|与|等|到|至|前后|可能|将|还将|有|局地|注意|请|务必|建议|合理规划)")
    private val unfinishedEnd = Regex("(?:到|至|从|最高|最低|的|等|或|及|和|与|但|不过|其中|包括|不仅|同时|另外|务必|需要|注意|可能|将|还将|将会|会|带来|出现|伴有|达到|下降|上升|一些)$")
    private val connectedEnd = Regex("(?:到|至|从|最高|最低|等|或|及|和|与|其中|包括|不仅|将|将会|会|带来|出现|伴有)$")

    fun generate(manifest: SubtitleManifest, cues: List<TimestampedCue>): EpisodeSummary? {
        if (cues.isEmpty()) return null
        if (cues.any { it.endMs > manifest.durationMs } ||
            cues.zipWithNext().any { (a, b) -> a.endMs > b.startMs }
        ) return null

        // Fix each continuous source window before ranking it. Never cut at a character/time budget:
        // time ranges, parallel regions, risks and following advice must stay with their source.
        val passages = mutableListOf<EpisodeSummaryItem>()
        val group = mutableListOf<Int>()
        fun flush() {
            if (group.isEmpty()) return
            val first = cues[group.first()]
            val last = cues[group.last()]
            val text = group.joinToString("") { cues[it].normalizedText }
            val compact = compact(text)
            val uncertainBoundary = (continuationStart.containsMatchIn(compact) &&
                !sectionStart.containsMatchIn(compact)) ||
                unfinishedEnd.containsMatchIn(compact) ||
                compact.length > 240 || last.endMs - first.startMs > 60_000L ||
                group.any { cues[it].confidence?.let { confidence -> confidence < 0.6f } == true }
            passages += EpisodeSummaryItem(first.startMs, last.endMs, text,
                supportingCueIds = group.toList(), replayOnly = uncertainBoundary)
            group.clear()
        }
        for ((index, cue) in cues.withIndex()) {
            val text = compact(cue.normalizedText)
            // City readings are a separate catalogue; fragmented numbers are not narrative notes.
            if (text.contains("城市天气预报") || citySection.containsMatchIn(text)) {
                flush()
                break
            }
            if (group.isNotEmpty()) {
                val previous = cues[group.last()]
                val previousText = compact(previous.normalizedText)
                val sectionTransition = sectionStart.containsMatchIn(text) &&
                    !connectedEnd.containsMatchIn(previousText)
                val separateUtterance = cue.startMs - previous.endMs >= 2_000L &&
                    !unfinishedEnd.containsMatchIn(previousText) && !continuationStart.containsMatchIn(text)
                if (sectionTransition || separateUtterance) flush()
            }
            group += index
        }
        flush()

        val candidates = passages.filter { compact(it.text).length >= 12 && topics.any(it.text::contains) }
            .distinctBy { compact(it.text) }
        if (candidates.isEmpty()) return null // Silence/irrelevant speech cannot become a forecast.
        val selected = candidates.sortedByDescending { item ->
            topics.count(item.text::contains) + (if (advice.containsMatchIn(item.text)) 2 else 0)
        }.take(5).sortedBy { it.startMs }
        return EpisodeSummary(
            manifest.program, manifest.episodeDate, manifest.sourceVideoUrl, manifest.sourceVideoSha256,
            manifest.vttSha256, EpisodeSummaryGate.LOCAL_EXTRACTIVE_ASR_METHOD, selected
        )
    }

    private fun compact(text: String) = text.replace(Regex("[\\s\\p{P}]+"), "")
}
