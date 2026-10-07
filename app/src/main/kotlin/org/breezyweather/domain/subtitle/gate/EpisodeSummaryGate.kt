/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.subtitle.gate

import org.breezyweather.domain.multisource.model.TimestampedCue
import org.breezyweather.domain.subtitle.model.EpisodeSummary
import org.breezyweather.domain.subtitle.model.SubtitleManifest

sealed interface EpisodeSummaryResult {
    data class Accepted(val summary: EpisodeSummary) : EpisodeSummaryResult
    data class Rejected(val reason: String) : EpisodeSummaryResult
}

/** Keeps a summary attached to the exact episode, video revision, and parsed subtitle cues. */
object EpisodeSummaryGate {
    const val LOCAL_GENERATIVE_ASR_METHOD = "LOCAL_QWEN_GENERATED_FROM_AI_ASR_V1"
    const val LOCAL_EXTRACTIVE_ASR_METHOD = "LOCAL_EXTRACTIVE_FROM_AI_ASR_V3"
    const val LOCAL_STRUCTURED_ASR_METHOD = "LOCAL_STRUCTURED_BRIEF_FROM_AI_ASR_V6"
    const val HUMAN_REVIEWED_ASR_METHOD = "HUMAN_REVIEWED_FROM_AI_ASR"

    fun verify(
        summary: EpisodeSummary?,
        manifest: SubtitleManifest,
        currentProgram: String,
        currentEpisodeDate: String,
        actuallyPlayingMediaUrl: String,
        cues: List<TimestampedCue>
    ): EpisodeSummaryResult {
        summary ?: return EpisodeSummaryResult.Rejected("Summary is absent")

        if (normalizeProgram(summary.program) != normalizeProgram(currentProgram) ||
            normalizeProgram(summary.program) != normalizeProgram(manifest.program)
        ) return EpisodeSummaryResult.Rejected("Program identity mismatch")
        if (summary.episodeDate != currentEpisodeDate || summary.episodeDate != manifest.episodeDate) {
            return EpisodeSummaryResult.Rejected("Episode date mismatch")
        }
        if (summary.sourceVideoUrl != manifest.sourceVideoUrl ||
            summary.sourceVideoUrl != actuallyPlayingMediaUrl
        ) return EpisodeSummaryResult.Rejected("Source video URL mismatch")
        if (!summary.sourceVideoSha256.equals(manifest.sourceVideoSha256, ignoreCase = true)) {
            return EpisodeSummaryResult.Rejected("Source video hash mismatch")
        }
        if (!summary.subtitleVttSha256.equals(manifest.vttSha256, ignoreCase = true)) {
            return EpisodeSummaryResult.Rejected("Subtitle VTT hash mismatch")
        }
        if (summary.summaryMethod !in setOf(HUMAN_REVIEWED_ASR_METHOD, LOCAL_EXTRACTIVE_ASR_METHOD, LOCAL_GENERATIVE_ASR_METHOD, LOCAL_STRUCTURED_ASR_METHOD)) {
            return EpisodeSummaryResult.Rejected("Unsupported summary method")
        }
        val itemRange = when (summary.summaryMethod) {
            LOCAL_STRUCTURED_ASR_METHOD -> 0..6
            HUMAN_REVIEWED_ASR_METHOD -> 3..5
            else -> 1..5
        }
        if (summary.items.size !in itemRange) return EpisodeSummaryResult.Rejected("Invalid summary item count")
        if (summary.items.isEmpty() && summary.omissions.isEmpty() && summary.reviewItems.isEmpty()) return EpisodeSummaryResult.Rejected("Missing extraction explanation")
        if (summary.dateContextVerified != org.breezyweather.domain.subtitle.local.BriefWeatherNotes.verifiedDateContext(manifest) &&
            summary.summaryMethod == LOCAL_STRUCTURED_ASR_METHOD) return EpisodeSummaryResult.Rejected("Unverified date conversion")
        if (summary.reviewItems.isNotEmpty() && summary.summaryMethod != LOCAL_STRUCTURED_ASR_METHOD)
            return EpisodeSummaryResult.Rejected("Unsupported review evidence")

        for ((item, review) in summary.items.map { it to false } + summary.reviewItems.map { it to true }) {
            if (item.startMs < 0L || item.endMs <= item.startMs || item.endMs > manifest.durationMs) {
                return EpisodeSummaryResult.Rejected("Summary time range is outside the episode")
            }
            if (item.text.isBlank()) return EpisodeSummaryResult.Rejected("Summary item is empty")
            if (summary.summaryMethod == LOCAL_STRUCTURED_ASR_METHOD) {
                val ids = item.supportingCueIds
                if (ids.isEmpty() || ids.any { it !in cues.indices } ||
                    ids != (ids.first()..ids.last()).toList() ||
                    item.startMs != cues[ids.first()].startMs || item.endMs != cues[ids.last()].endMs)
                    return EpisodeSummaryResult.Rejected("Structured source timeline mismatch")
                val actual = ids.joinToString("") { cues[it].normalizedText }
                val source = item.sourceText ?: return EpisodeSummaryResult.Rejected("Structured source text absent")
                if (item.sourceOffset < 0 || item.sourceOffset + source.length > actual.length ||
                    actual.substring(item.sourceOffset, item.sourceOffset + source.length) != source)
                    return EpisodeSummaryResult.Rejected("Structured source sentence mismatch")
                if (item.region == null || (!review && (item.time == null || item.weather == null)) || item.replayOnly)
                    return EpisodeSummaryResult.Rejected("Structured forecast lacks required fields")
                if (review && item.reviewReasons.isEmpty() || !review && item.reviewReasons.isNotEmpty())
                    return EpisodeSummaryResult.Rejected("Review classification mismatch")
                if (org.breezyweather.domain.subtitle.local.BriefWeatherNotes.SNOW_REVIEW in item.reviewReasons &&
                    !org.breezyweather.domain.subtitle.local.BriefWeatherNotes.hasSnowConflict(summary, item))
                    return EpisodeSummaryResult.Rejected("Snow conflict does not match source artifact")
                if (org.breezyweather.domain.subtitle.local.BriefWeatherNotes.PERIOD_REVIEW in item.reviewReasons &&
                    !org.breezyweather.domain.subtitle.local.BriefWeatherNotes.hasPeriodConflict(summary, item))
                    return EpisodeSummaryResult.Rejected("Period conflict does not match source artifact")
                if (item.relatedForecasts.size > 3 || item.relatedForecasts.any { it.time != item.time })
                    return EpisodeSummaryResult.Rejected("Structured event has unrelated time anchors")
                val forecasts = if (item.time != null && item.weather != null) listOf(org.breezyweather.domain.subtitle.model.SummaryForecastEvidence(
                    item.region, item.time, item.weather, item.advice)) + item.relatedForecasts else item.relatedForecasts
                if (forecasts.any { it.time.end > it.region.start || it.region.end > it.weather.start } ||
                    forecasts.zipWithNext().any { (a, b) -> a.weather.end > b.region.start })
                    return EpisodeSummaryResult.Rejected("Structured regional assertion ordering mismatch")
                val fields = listOfNotNull(item.region, item.time, item.weather, item.advice) +
                    item.relatedForecasts.flatMap { listOfNotNull(it.region, it.time, it.weather, it.advice) }
                if ((listOfNotNull(item.weather) + item.relatedForecasts.map { it.weather }).any {
                        !org.breezyweather.domain.subtitle.local.BriefWeatherNotes.completeNumbers(it.value)
                    }) return EpisodeSummaryResult.Rejected("Weather numeric field is incomplete")
                for (field in fields) {
                    if (field.value.isBlank() || field.start < 0 || field.end <= field.start || field.end > source.length ||
                        source.substring(field.start, field.end) != field.value)
                        return EpisodeSummaryResult.Rejected("Structured field is not a literal source span")
                }
                if (forecasts.any {
                        Regex("不|无|没有|可能|局地|部分地区|大部分地区").containsMatchIn(source.substring(it.region.end, it.weather.start))
                    }) return EpisodeSummaryResult.Rejected("Structured qualifier was removed from its source fields")
                if (item.time?.end?.let { it > item.region.start } == true ||
                    item.weather?.start?.let { it < item.region.end } == true)
                    return EpisodeSummaryResult.Rejected("Review regional ordering mismatch")
                if (item.weather != null && Regex("不|无|没有|可能|局地|部分地区|大部分地区").containsMatchIn(
                        source.substring(item.region.end, item.weather.start)))
                    return EpisodeSummaryResult.Rejected("Review qualifier was removed from source fields")
                if (!review && org.breezyweather.domain.subtitle.local.BriefWeatherNotes.hasPeriodConflict(summary, item))
                    return EpisodeSummaryResult.Rejected("Known time conflict belongs in review")
                if (item.text != org.breezyweather.domain.subtitle.local.BriefWeatherNotes.display(summary, item, review))
                    return EpisodeSummaryResult.Rejected("Structured display differs from its source fields")
            }
            if (summary.summaryMethod == LOCAL_GENERATIVE_ASR_METHOD) {
                val ids = item.supportingCueIds.distinct().sorted()
                if (ids.isEmpty() || ids.any { it !in cues.indices }) {
                    return EpisodeSummaryResult.Rejected("Generated summary references unknown cues")
                }
                if (item.startMs != cues[ids.first()].startMs || item.endMs != cues[ids.last()].endMs) {
                    return EpisodeSummaryResult.Rejected("Generated summary timeline does not match its supporting cues")
                }
                val sourceText = ids.joinToString("") { cues[it].normalizedText }
                org.breezyweather.domain.subtitle.local.LocalSummaryQuality.rejection(item.text, sourceText)?.let { reason ->
                    return EpisodeSummaryResult.Rejected("Generated summary quality rejected: $reason")
                }
            }
            if (summary.summaryMethod == LOCAL_EXTRACTIVE_ASR_METHOD) {
                val matchingIds = cues.indices.filter { cues[it].startMs < item.endMs && cues[it].endMs > item.startMs }
                if (matchingIds.isEmpty() || item.startMs != cues[matchingIds.first()].startMs ||
                    item.endMs != cues[matchingIds.last()].endMs || item.supportingCueIds != matchingIds
                ) return EpisodeSummaryResult.Rejected("Local selection does not match its complete source window")
                val actualText = matchingIds.joinToString("") { cues[it].normalizedText }
                if (item.text != actualText) {
                    return EpisodeSummaryResult.Rejected("Local summary is not grounded in the episode transcript")
                }
            }
            if (cues.none { cue -> cue.startMs < item.endMs && cue.endMs > item.startMs }) {
                return EpisodeSummaryResult.Rejected("Summary item has no matching subtitle cue")
            }
        }

        return EpisodeSummaryResult.Accepted(summary)
    }

    private fun normalizeProgram(program: String): String =
        program.trim().uppercase().replace('-', '_')
}
