package org.breezyweather.domain.subtitle

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.breezyweather.domain.multisource.model.TimestampedCue
import org.breezyweather.domain.subtitle.gate.EpisodeSummaryGate
import org.breezyweather.domain.subtitle.gate.EpisodeSummaryResult
import org.breezyweather.domain.subtitle.local.LocalWeatherSummary
import org.breezyweather.domain.subtitle.model.SubtitleManifest
import org.junit.jupiter.api.Test

class LocalWeatherSummaryTest {
    private val manifest = SubtitleManifest("1", "EVENING_WEATHER", "2026-09-29",
        "https://vod.weathertv.cn/20260929.mp4", "a".repeat(64), 180000,
        "AI_ASR_GENERATED", "AUTOMATED_ASR_EXTRACTED", "vosk-model-small-cn-0.22",
        "2026-09-30T00:00:00Z", "subtitle.vtt", "b".repeat(64), 4)
    private fun cue(a: Long, b: Long, text: String) = TimestampedCue(a, b, text, text)
    private val cues = listOf(
        cue(0, 4000, "观众朋友晚上好欢迎收看今天的节目"),
        cue(10000, 16000, "明天四川盆地有大到暴雨注意防范强降雨"),
        cue(50000, 56000, "冷空气带来降温北方气温下降六到八度"),
        cue(120000, 126000, "台风带来大风沿海地区注意出行安全"))

    @Test
    fun `automatic summary is tied to recognized forecast content and rejects another episode`() {
        val summary = LocalWeatherSummary.generate(manifest, cues)!!
        summary.items.size shouldBe 3
        summary.items.map { it.text } shouldBe listOf(
            "明天四川盆地有大到暴雨注意防范强降雨",
            "冷空气带来降温北方气温下降六到八度",
            "台风带来大风沿海地区注意出行安全")
        fun verify(date: String, items: List<org.breezyweather.domain.subtitle.model.EpisodeSummaryItem> = summary.items) =
            EpisodeSummaryGate.verify(summary.copy(items = items), manifest, manifest.program, date,
                manifest.sourceVideoUrl, cues)
        verify("2026-09-29").shouldBeInstanceOf<EpisodeSummaryResult.Accepted>()
        verify("2026-09-28").shouldBeInstanceOf<EpisodeSummaryResult.Rejected>()
        verify("2026-09-29", summary.items.map { it.copy(text = "明天全国晴天") })
            .shouldBeInstanceOf<EpisodeSummaryResult.Rejected>()
        EpisodeSummaryGate.verify(summary.copy(summaryMethod = "LOCAL_EXTRACTIVE_FROM_AI_ASR_V1"),
            manifest, manifest.program, manifest.episodeDate, manifest.sourceVideoUrl, cues)
            .shouldBeInstanceOf<EpisodeSummaryResult.Rejected>()
        EpisodeSummaryGate.verify(summary.copy(program = "CHINA_WEATHER_LIANBO"),
            manifest, manifest.program, manifest.episodeDate, manifest.sourceVideoUrl, cues)
            .shouldBeInstanceOf<EpisodeSummaryResult.Rejected>()
    }

    @Test
    fun `silence or irrelevant speech produces no invented weather summary`() {
        LocalWeatherSummary.generate(manifest, emptyList()) shouldBe null
        LocalWeatherSummary.generate(manifest, listOf(cues.first())) shouldBe null
    }

    @Test
    fun `a long forecast keeps its preceding time range parallel regions risk and advice together`() {
        val forecast = listOf(
            cue(5000, 10000, "这次降水从今天晚上到"),
            cue(12500, 18000, "明天重庆东部湖北西部贵州东北部"),
            cue(18500, 25000, "可能出现大到暴雨需警惕山洪风险"),
            cue(27500, 32000, "注意及时排水无需提前停止所有出行"))
        val summary = LocalWeatherSummary.generate(manifest.copy(program = "CHINA_WEATHER_LIANBO"), forecast)!!
        summary.items.size shouldBe 1
        val item = summary.items.single()
        item.startMs shouldBe 5000L
        item.endMs shouldBe 32000L
        item.text shouldBe "这次降水从今天晚上到明天重庆东部湖北西部贵州东北部可能出现大到暴雨需警惕山洪风险注意及时排水无需提前停止所有出行"
        item.replayOnly shouldBe false
        EpisodeSummaryGate.verify(summary, manifest.copy(program = "CHINA_WEATHER_LIANBO"),
            "CHINA_WEATHER_LIANBO", manifest.episodeDate, manifest.sourceVideoUrl, forecast)
            .shouldBeInstanceOf<EpisodeSummaryResult.Accepted>()
        // Keeping the words while clipping their source time still loses the complete replay window.
        EpisodeSummaryGate.verify(summary.copy(items = listOf(item.copy(startMs = 6000))),
            manifest.copy(program = "CHINA_WEATHER_LIANBO"), "CHINA_WEATHER_LIANBO",
            manifest.episodeDate, manifest.sourceVideoUrl, forecast)
            .shouldBeInstanceOf<EpisodeSummaryResult.Rejected>()
    }

    @Test
    fun `unfinished speech remains a replay fallback rather than a repaired weather assertion`() {
        val forecast = listOf(
            cue(0, 5000, "今晚北方冷空气带来降温大家早晚注意"),
            cue(6000, 10000, "眼下南方降水持续到"),
            cue(14000, 18000, "未来几天部分地区可能有暴雨请注意安全"),
            cue(19000, 22000, "最后来看城市天气预报"),
            cue(23000, 26000, "北京多云十四到二十一度"))
        val summary = LocalWeatherSummary.generate(manifest, forecast)!!
        summary.items.map { it.text } shouldBe listOf(
            "今晚北方冷空气带来降温大家早晚注意",
            "眼下南方降水持续到未来几天部分地区可能有暴雨请注意安全")
        summary.items.map { it.replayOnly } shouldBe listOf(true, false)
        summary.items.last().endMs shouldBe 18000L
    }

    @Test
    fun `recognized topic introductions retain narrative passages and a misheard city transition excludes city readings`() {
        // Uses October 1 ASR's misheard boundary phrases without repairing the words or numbers.
        val forecast = listOf(
            cue(6090, 25350, "预计今天晚上到明天南方仍然会有大范围的降雨其中在贵州还有湖南北部等地讲"),
            cue(25440, 30360, "有大到暴雨甚至是大暴雨这一代的秋熟工作角回短暂的受阻"),
            cue(30600, 40300, "另外随着新的一股冷空气的到来今天晚上到明天内蒙古兜底将会挂起舞蹈七级风"),
            cue(40300, 65519, "华北北部一带降水也将会发展增多内蒙古东部的部分地区还会有大雨局地可能会飘着雪花"),
            cue(65940, 95550, "清方面受到冷空气的影响明天内蒙古中西部多的将会降温此道十二点"),
            cue(95580, 100560, "另外早晚湿度下降体感毁舒爽许多"),
            cue(100590, 104250, "了解更多天气信息咱们关注具体的城市挺去吧"),
            cue(106440, 108390, "北京多元十一的二十二摄氏度"),
            cue(116790, 118860, "沈阳跟着雷阵雨三导师角度"),
            cue(120540, 122790, "天津蹲着振宇十五到二十五度"))
        val summary = LocalWeatherSummary.generate(manifest, forecast)!!
        summary.items.map { it.text } shouldBe listOf(
            "预计今天晚上到明天南方仍然会有大范围的降雨其中在贵州还有湖南北部等地讲有大到暴雨甚至是大暴雨这一代的秋熟工作角回短暂的受阻",
            "另外随着新的一股冷空气的到来今天晚上到明天内蒙古兜底将会挂起舞蹈七级风华北北部一带降水也将会发展增多内蒙古东部的部分地区还会有大雨局地可能会飘着雪花",
            "清方面受到冷空气的影响明天内蒙古中西部多的将会降温此道十二点另外早晚湿度下降体感毁舒爽许多")
        summary.items.map { it.replayOnly } shouldBe listOf(false, false, false)
        summary.items.map { it.startMs to it.endMs } shouldBe listOf(
            6090L to 30360L, 30600L to 65519L, 65940L to 100560L)
    }
}
