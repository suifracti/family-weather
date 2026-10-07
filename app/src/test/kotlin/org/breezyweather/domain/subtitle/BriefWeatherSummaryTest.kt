package org.breezyweather.domain.subtitle

import org.breezyweather.domain.multisource.model.TimestampedCue
import org.breezyweather.domain.subtitle.local.StructuredWeatherSummary
import org.breezyweather.domain.subtitle.model.SubtitleManifest
import org.breezyweather.domain.subtitle.gate.EpisodeSummaryGate
import org.breezyweather.domain.subtitle.gate.EpisodeSummaryResult
import org.breezyweather.domain.subtitle.local.BriefWeatherNotes
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BriefWeatherSummaryTest {
    private val manifest = SubtitleManifest("1", "CHINA_WEATHER_LIANBO", "2026-10-02",
        "https://vod.weathertv.cn/video/2026/10/2/202610021790947621655.mp4", "a".repeat(64), 300000,
        "AI_ASR_GENERATED", "AUTOMATED_ASR_EXTRACTED", "sensevoice", "2026-10-06T00:00:00Z",
        "subtitle.vtt", "b".repeat(64), 1, sourceIdentity = "weather_com_cn|CHINA_WEATHER_LIANBO|3M|24222")
    private fun cue(text: String) = TimestampedCue(1000, 12000, text, text)

    @Test fun naturalNotesKeepRelativeEvidenceAndRegionalSeverity() {
        val text = "今天晚上到明天南方仍然会有大范围的降雨其中在贵州还有湖南北部等地将会有大到暴雨甚至是大暴雨"
        val item = StructuredWeatherSummary.generate(manifest, listOf(cue(text)))!!.items.single()
        assertEquals("10月2日晚至3日，南方大范围降雨；贵州、湖南北部等地有大到暴雨，甚至大暴雨。", item.text)
        assertEquals("今天晚上到明天", item.time!!.value)
        assertEquals(text, item.sourceText)
    }

    @Test fun compactWordingDoesNotEraseNegationOrProbability() {
        val negative = StructuredWeatherSummary.generate(manifest, listOf(cue("明天山东不会有大雨")))!!.items.single()
        assertEquals("10月3日，山东不会有大雨。", negative.text)
        val local = StructuredWeatherSummary.generate(manifest, listOf(cue("明天贵州部分地区可能有大雨")))!!.items.single()
        assertEquals("10月3日，贵州部分地区可能有大雨。", local.text)
    }

    @Test fun unverifiedOrCrossMonthDatesAreNotGuessed() {
        val text = "明天内蒙古中西部多地将会降温四到十二度"
        val unverified = StructuredWeatherSummary.generate(manifest.copy(sourceIdentity = null), listOf(cue(text)))!!.items.single()
        assertEquals("明天，内蒙古中西部多地降温4–12℃。", unverified.text)
        val monthEnd = manifest.copy(episodeDate = "2026-10-31",
            sourceVideoUrl = "https://vod.weathertv.cn/video/2026/10/31/20261031.mp4")
        val crossMonth = StructuredWeatherSummary.generate(monthEnd, listOf(cue(text)))!!.items.single()
        assertEquals("明天，内蒙古中西部多地降温4–12℃。", crossMonth.text)
        val ambiguous = StructuredWeatherSummary.generate(monthEnd, listOf(cue("一号二号辽宁有大雨")))!!.items.single()
        assertEquals("一号二号，辽宁有大雨。", ambiguous.text)
        assertEquals("十十月四号", BriefWeatherNotes.dateLabel("十十月四号", manifest.episodeDate, true))
        assertEquals("四号到十十月五号", BriefWeatherNotes.dateLabel("四号到十十月五号", manifest.episodeDate, true))
    }

    private fun verify(summary: org.breezyweather.domain.subtitle.model.EpisodeSummary, cues: List<TimestampedCue>,
                       source: SubtitleManifest = manifest) = EpisodeSummaryGate.verify(summary, source,
        source.program, source.episodeDate, source.sourceVideoUrl, cues)

    @Test fun cityCatalogueNeverBorrowsNarrativeDateAndRetainsAllFiveSourceRanges() {
        val texts = listOf("五号到七号辽宁有大雨下面来关注具体的城市天气预报合肥小",
            "雨十七到十八度南京小雨十七到十九度", "上海阴转小雨二十到二十四度武汉小雨转中雨",
            "十七到十九度长沙小雨转中雨十七到十八度")
        val cues = texts.mapIndexed { i, t -> TimestampedCue(1000L + i*6000, 7000L + i*6000, t, t) }
        val result = StructuredWeatherSummary.generate(manifest, cues)!!
        assertEquals(1, result.items.size)
        assertEquals(5, result.reviewItems.size)
        assertTrue(result.reviewItems.all { it.time == null && it.reviewReasons == listOf("预报时间待核") })
        assertEquals("合肥小雨17–18℃。", result.reviewItems.first().text)
        assertEquals("合肥小雨十七到十八度", result.reviewItems.first().sourceText)
        assertEquals(listOf(0, 1), result.reviewItems.first().supportingCueIds)
        assertTrue(verify(result, cues) is EpisodeSummaryResult.Accepted)
        assertFalse(result.reviewItems.any { "10月" in it.text || "五号" in it.text })
    }

    @Test fun reviewGateRejectsStrippedQualifiersAndFabricatedConflictLabels() {
        val cues = listOf(cue("贵州可能有大雨"))
        val result = StructuredWeatherSummary.generate(manifest, cues)!!
        val original = result.reviewItems.single()
        val stripped = original.copy(weather = original.weather!!.let { it.copy(value = "有大雨", start = it.start + 2) })
        val changed = stripped.copy(text = BriefWeatherNotes.display(result, stripped, review = true))
        assertTrue(verify(result.copy(reviewItems = listOf(changed)), cues) is EpisodeSummaryResult.Rejected)
        val conflict = original.copy(reviewReasons = listOf(BriefWeatherNotes.SNOW_REVIEW))
        val forged = conflict.copy(text = BriefWeatherNotes.display(result, conflict, review = true))
        assertTrue(verify(result.copy(reviewItems = listOf(forged)), cues) is EpisodeSummaryResult.Rejected)
    }

    @Test fun lowConfidenceAndIncompleteNumbersRemainReplayableReviewNotMainNotes() {
        val text = "明天四川盆地有大雨"
        val lowCues = listOf(TimestampedCue(1000, 12000, text, text, 0.3f))
        val low = StructuredWeatherSummary.generate(manifest, lowCues)!!
        assertTrue(low.items.isEmpty())
        assertTrue(low.reviewItems.single().reviewReasons.any { "低置信度" in it })
        assertTrue(verify(low, lowCues) is EpisodeSummaryResult.Accepted)
        val brokenCues = listOf(cue("天津多云转十五到二十五度"))
        val broken = StructuredWeatherSummary.generate(manifest, brokenCues)!!
        assertTrue(broken.items.isEmpty())
        assertNull(broken.reviewItems.single().weather)
        assertEquals("天津多云转十五到二十五度", broken.reviewItems.single().sourceText)
        assertTrue(verify(broken, brokenCues) is EpisodeSummaryResult.Accepted)
    }

    @Test fun knownConflictsAreScopedToFrozenArtifactAndDoNotBecomeDatedNotes() {
        val frozen = manifest.copy(sourceVideoSha256 = "463caa62875f2d89ee160dec89fd4cd28b68e962dc4653b820ad27a32eb311fe",
            vttSha256 = "cd4ac5eebc8cf5c008551272e48f2872a29ec42b378fd733c5807944409905a0")
        val texts = listOf("今天晚上到明天内蒙古多地将会刮起五到七级风华北北部一带降水也将会发展增多在内蒙古东部的部分地区还会有大雨局地可能会飘落雪花",
            "五号到七号涂上大片区域将会陆续刷新立秋以后气温的新低像福州还有南宁将会是今年下半年以来首次最低气温来到二十度以下")
        val cues = texts.mapIndexed { i,t -> TimestampedCue(1000L+i*16000, 16000L+i*16000, t,t) }
        val result = StructuredWeatherSummary.generate(frozen, cues)!!
        assertEquals(1, result.items.size)
        assertEquals(2, result.reviewItems.size)
        assertTrue(result.items.single().text.contains("。\n华北北部一带降水增多"))
        assertFalse(result.items.single().text.contains("雪花"))
        assertEquals(texts.first(), result.items.single().sourceText)
        assertEquals("福州、南宁最低气温将低于20℃。", result.reviewItems.last().text)
        assertTrue(verify(result, cues, frozen) is EpisodeSummaryResult.Accepted)
        val other = StructuredWeatherSummary.generate(manifest, cues)!!
        assertTrue(other.items.first().text.contains("雪花"), "Do not transfer a film-specific conflict to another artifact")
    }

    @Test fun staleV5AndTamperedDateConversionsCannotBeCacheHits() {
        val cues = listOf(cue("明天北京有大雨"))
        val result = StructuredWeatherSummary.generate(manifest, cues)!!
        assertTrue(verify(result.copy(summaryMethod = "LOCAL_STRUCTURED_VERBATIM_FROM_AI_ASR_V5"), cues) is EpisodeSummaryResult.Rejected)
        assertTrue(verify(result.copy(dateContextVerified = false), cues) is EpisodeSummaryResult.Rejected)
    }

    @Test fun malformedTemperatureCannotBeFormattedAsACompleteCityForecast() {
        val text = "广州雷阵雨二十四四到三十一度"
        val result = StructuredWeatherSummary.generate(manifest, listOf(cue(text)))!!
        assertTrue(result.items.isEmpty())
        assertNull(result.reviewItems.single().weather)
        assertTrue(result.reviewItems.single().reviewReasons.any { "温度" in it })
        assertEquals(text, result.reviewItems.single().sourceText)
    }
}
