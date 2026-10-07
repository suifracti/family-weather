package org.breezyweather.domain.subtitle

import org.breezyweather.domain.multisource.model.TimestampedCue
import org.breezyweather.domain.subtitle.gate.EpisodeSummaryGate
import org.breezyweather.domain.subtitle.gate.EpisodeSummaryResult
import org.breezyweather.domain.subtitle.local.StructuredWeatherSummary
import org.breezyweather.domain.subtitle.model.SubtitleManifest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StructuredWeatherSummaryTest {
    private val manifest = SubtitleManifest("1", "CHINA_WEATHER_LIANBO", "2026-09-19",
        "https://vod.weathertv.cn/20260919.mp4", "a".repeat(64), 180000,
        "AI_ASR_GENERATED", "AUTOMATED_ASR_EXTRACTED", "vosk-model-small-cn-0.22",
        "2026-09-20T00:00:00Z", "subtitle.vtt", "b".repeat(64), 1)
    private fun cue(text: String, confidence: Float? = null) = TimestampedCue(3000, 10000, text, text, confidence)
    private fun verify(summary: org.breezyweather.domain.subtitle.model.EpisodeSummary, cues: List<TimestampedCue>) =
        EpisodeSummaryGate.verify(summary, manifest, manifest.program, manifest.episodeDate, manifest.sourceVideoUrl, cues)

    @Test fun keepsParallelRegionsTimeQualifiersAndOnlyExplicitAdvice() {
        val cues = listOf(cue("今晚到明天四川盆地还有重庆东部可能有大到暴雨注意防范山洪风险"))
        val summary = StructuredWeatherSummary.generate(manifest, cues)!!
        val item = summary.items.single()
        assertEquals("四川盆地还有重庆东部", item.region!!.value)
        assertEquals("今晚到明天", item.time!!.value)
        assertEquals("可能有大到暴雨", item.weather!!.value)
        assertEquals("注意防范山洪风险", item.advice!!.value)
        assertNotEquals(cues.single().rawText, item.text)
        assertEquals(cues.single().rawText, item.sourceText)
        assertTrue(verify(summary, cues) is EpisodeSummaryResult.Accepted)
        assertTrue(verify(summary.copy(items = listOf(item.copy(text = "明天全国有暴雨"))), cues) is EpisodeSummaryResult.Rejected)
        assertTrue(verify(summary.copy(items = listOf(item.copy(startMs = 5000))), cues) is EpisodeSummaryResult.Rejected)
        assertTrue(EpisodeSummaryGate.verify(summary, manifest, manifest.program, "2026-09-20", manifest.sourceVideoUrl, cues) is EpisodeSummaryResult.Rejected)
    }

    @Test fun excludesPromotionAndNeverAddsAdviceToAWeatherChange() {
        val cues = listOf(cue("明天河北北部将会降温六到八度扫描二维码明天北京有大雨注意出行安全"))
        val summary = StructuredWeatherSummary.generate(manifest, cues)!!
        assertEquals(2, summary.items.size)
        val item = summary.items.first()
        assertEquals("河北北部", item.region!!.value)
        assertEquals("将会降温六到八度", item.weather!!.value)
        assertNull(item.advice)
        assertFalse(item.sourceText!!.contains("扫描"))
    }

    @Test fun brokenNumbersAndTimeRangesAreNotRepairedOrUsedAsItems() {
        val cues = listOf(cue("明天内蒙古中西部多的将会降温此道十二点"))
        val summary = StructuredWeatherSummary.generate(manifest, cues)!!
        assertTrue(summary.items.isEmpty())
        assertTrue(summary.omissions.any { "数值" in it })
        assertTrue(verify(summary, cues) is EpisodeSummaryResult.Accepted)
        val brokenDate = StructuredWeatherSummary.generate(manifest, listOf(cue("五号到好辽宁有大雨")))!!
        assertTrue(brokenDate.items.isEmpty())
        assertTrue(brokenDate.omissions.any { "时间范围" in it })
    }

    @Test fun preservesNegationAndLocalProbability() {
        val summary = StructuredWeatherSummary.generate(manifest, listOf(cue("明天山东不会有大雨")))!!
        assertEquals("不会有大雨", summary.items.single().weather!!.value)
        val local = StructuredWeatherSummary.generate(manifest, listOf(cue("明天贵州部分地区可能有大雨")))!!
        assertEquals("贵州部分地区", local.items.single().region!!.value)
        assertEquals("可能有大雨", local.items.single().weather!!.value)
        val parallel = StructuredWeatherSummary.generate(manifest,
            listOf(cue("明天辽宁吉林还有黑龙江等地的部分地区可能有大雨")))!!
        assertEquals("辽宁吉林还有黑龙江等地的部分地区", parallel.items.single().region!!.value)
        assertEquals("可能有大雨", parallel.items.single().weather!!.value)
    }

    @Test fun missingTimeAndLowConfidenceAreExplanationsNotForecasts() {
        val shiftedSubject = StructuredWeatherSummary.generate(manifest,
            listOf(cue("今天白天南方大部分地区都被厚厚的云层所覆盖北晴南雨")))!!
        assertTrue(shiftedSubject.items.isEmpty(), "Clear northern skies cannot be attributed to the cloudy south")
        assertTrue(shiftedSubject.omissions.any { "未解析" in it })
        val noTime = StructuredWeatherSummary.generate(manifest, listOf(cue("四川盆地有大雨")))!!
        assertTrue(noTime.items.isEmpty())
        assertTrue(noTime.omissions.any { "时间" in it })
        val risky = StructuredWeatherSummary.generate(manifest, listOf(cue("明天四川盆地有大雨", 0.3f)))!!
        assertTrue(risky.items.isEmpty())
        assertTrue(risky.omissions.any { "低置信度" in it })
        // High confidence is merely eligible for extraction, never labelled human verified.
        assertFalse(StructuredWeatherSummary.generate(manifest, listOf(cue("明天四川盆地有大雨", 1f)))!!
            .summaryMethod.contains("HUMAN"))
    }
    @Test fun impactSentenceWindIsNotAppendedToNortheastRain() {
        val cues = listOf(cue("四号五号辽宁吉林还有黑龙江等地的部分地区会先后出现大到暴雨大风和降水同样会给这一带的秋收工作带来不利的影响"))
        val summary = StructuredWeatherSummary.generate(manifest, cues)!!
        assertEquals("会先后出现大到暴雨", summary.items.single().weather!!.value)
        assertFalse(summary.items.single().text.contains("暴雨大风"))
        assertNull(summary.items.single().advice, "Impact statements must not be turned into invented instructions")
    }

    @Test fun sameEventCombinesGeneralRainAndRegionalSeverityWithoutFlatteningThem() {
        val cues = listOf(cue("今天晚上到明天南方仍然会有大范围的降雨其中在贵州还有湖南北部等地将会有大到暴雨甚至是大暴雨"))
        val summary = StructuredWeatherSummary.generate(manifest, cues)!!
        assertEquals(1, summary.items.size, "One event, with a separate assertion for each region")
        assertTrue(summary.items.single().text.contains("南方大范围降雨"))
        assertTrue(summary.items.single().text.contains("贵州、湖南北部等地有大到暴雨，甚至大暴雨"))
        assertTrue(verify(summary, cues) is EpisodeSummaryResult.Accepted)
    }

    @Test fun realNarrativeRetainsNortheastRainCoolingAndDifferentWindContent() {
        val texts = listOf(
            "预计今天晚上到明天南方仍然会有大范围的降雨其中在贵州还有湖南北部等地将会有大到暴雨甚至是大暴雨",
            "另外随着新的一股冷空气的到来今天晚上到明天内蒙古多地将会刮起五到七级风华北北部一带降水也将会发展增多在内蒙古东部的部分地区还会有大雨局地可能会飘落雪花",
            "四号五号降水会进一步的向东北一带转移辽宁吉林还有黑龙江等地的部分地区会先后出现大到暴雨大风和降水同样会给这一带的秋收工作带来不利的影响",
            "不过六号七号我国的降水将会明显的收敛大部分地区都会和阳光相伴",
            "气温方面受到冷空气的影响明天内蒙古中西部多地将会降温四到十二度",
            "之后冷空气还会进一步的东移南下降温也将会覆盖到更多的地方假期的后半段五号到七号涂上大片区域将会陆续刷新立秋以后气温的新低像福州还有南宁将会是今年下半年以来首次最低气温来到二十度以下"
        )
        val cues = texts.mapIndexed { i, text -> TimestampedCue(1000L+i*15000, 14000L+i*15000, text, text) }
        val summary = StructuredWeatherSummary.generate(manifest, cues)!!
        val display = summary.items.joinToString("\n") { it.text }
        assertTrue(display.contains("有5–7级风"))
        assertTrue(display.contains("局地可能会飘落雪花"), "Retain probability and locality")
        assertTrue(display.contains("辽宁、吉林、黑龙江等地部分地区"))
        assertFalse(display.contains("暴雨大风"))
        assertTrue(display.contains("内蒙古中西部多地"))
        assertTrue(display.contains("降温4–12℃"))
        assertTrue(display.contains("福州、南宁"))
        assertTrue(display.contains("20℃以下"))
        assertTrue(display.contains("降水明显减少"))
        assertEquals(6, summary.items.size, "Group repeated event assertions before limiting coverage")
        assertTrue(verify(summary, cues) is EpisodeSummaryResult.Accepted)
    }

    @Test fun numericalWeatherRequiresItsOwnUnitsPlaceAndTime() {
        for (text in listOf("明天内蒙古多地刮起到七级风", "明天福州最低气温来到以下", "明天北京多云转十五到二十五度", "天多云转雨十五到二十五度")) {
            val summary = StructuredWeatherSummary.generate(manifest, listOf(cue(text)))!!
            assertTrue(summary.items.isEmpty(), text)
        }
        val summary = StructuredWeatherSummary.generate(manifest,
            listOf(cue("明天内蒙古五到七级风之后南宁最低气温来到十九度以下")))!!
        assertFalse(summary.items.any { it.text.contains("南宁") }, "Do not borrow tomorrow across an unbound new clause")
    }

    @Test fun repeatedForecastDoesNotProduceDuplicateEventCards() {
        val cues = listOf(TimestampedCue(1000, 6000, "明天北京有大雨", "明天北京有大雨"),
            TimestampedCue(7000, 12000, "明天北京有大雨", "明天北京有大雨"))
        assertEquals(1, StructuredWeatherSummary.generate(manifest, cues)!!.items.size)
    }

    @Test fun sourceSpanGateRejectsStrippedLocalityAndProbability() {
        val cues = listOf(cue("明天贵州部分地区可能有大雨"))
        val original = StructuredWeatherSummary.generate(manifest, cues)!!
        val item = original.items.single()
        val strippedProbability = item.copy(weather = item.weather!!.let { it.copy(value = "有大雨", start = it.start + 2) })
        val strippedLocality = item.copy(region = item.region!!.let { it.copy(value = "贵州", end = it.start + 2) })
        for (changed in listOf(strippedProbability, strippedLocality)) {
            val altered = changed.copy(text = StructuredWeatherSummary.display(changed))
            assertTrue(verify(original.copy(items = listOf(altered)), cues) is EpisodeSummaryResult.Rejected)
        }
    }

    @Test fun missingCityFieldsDoNotFloodTheBriefSummaryWithRepeatedExplanations() {
        val cues = listOf(cue("明天内蒙古多地降温四到十二度之后北京多云天津多云哈尔滨多云长春多云沈阳多云乌鲁木齐晴银川晴西宁晴"))
        val summary = StructuredWeatherSummary.generate(manifest, cues)!!
        assertEquals(1, summary.items.size)
        assertTrue(summary.omissions.size <= 5)
        assertEquals(8, summary.reviewItems.size)
        assertTrue(summary.reviewItems.all { it.time == null && "预报时间待核" in it.reviewReasons })
        assertTrue(summary.omissions.last().contains("转写未人工听核"))
    }

}
