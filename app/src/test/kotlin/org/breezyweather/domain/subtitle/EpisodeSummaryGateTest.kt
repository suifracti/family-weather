/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.subtitle

import io.kotest.matchers.types.shouldBeInstanceOf
import org.breezyweather.domain.multisource.model.TimestampedCue
import org.breezyweather.domain.subtitle.gate.EpisodeSummaryGate
import org.breezyweather.domain.subtitle.gate.EpisodeSummaryResult
import org.breezyweather.domain.subtitle.model.EpisodeSummary
import org.breezyweather.domain.subtitle.model.EpisodeSummaryItem
import org.breezyweather.domain.subtitle.model.SubtitleManifest
import org.junit.jupiter.api.Test

class EpisodeSummaryGateTest {
    private val videoUrl = "https://vod.weathertv.cn/video/2026/9/23/202609231790169820592.mp4"
    private val manifest = SubtitleManifest(
        schemaVersion = "1",
        program = "EVENING_WEATHER",
        episodeDate = "2026-09-23",
        sourceVideoUrl = videoUrl,
        sourceVideoSha256 = "A".repeat(64),
        durationMs = 260032L,
        subtitleOrigin = "AI_ASR_GENERATED",
        extractionMethod = "AUTOMATED_ASR_EXTRACTED",
        asrModel = "SenseVoiceSmall",
        generatedAt = "2026-09-23T13:45:14Z",
        vttFile = "subtitle.vtt",
        vttSha256 = "B".repeat(64),
        cueCount = 3
    )
    private val cues = listOf(
        TimestampedCue(34000L, 43000L, "中秋假期的地区天气", "中秋假期的地区天气"),
        TimestampedCue(53000L, 67000L, "今晚到明天部分地区可能有大到暴雨", "今晚到明天部分地区可能有大到暴雨"),
        TimestampedCue(209000L, 223000L, "南宁多云24到33度", "南宁多云24到33度")
    )
    private val summary = EpisodeSummary(
        program = "EVENING_WEATHER",
        episodeDate = "2026-09-23",
        sourceVideoUrl = videoUrl,
        sourceVideoSha256 = "A".repeat(64),
        subtitleVttSha256 = "B".repeat(64),
        summaryMethod = EpisodeSummaryGate.HUMAN_REVIEWED_ASR_METHOD,
        items = listOf(
            EpisodeSummaryItem(34000L, 43000L, "节目概述中秋假期期间南北方的天气特点。"),
            EpisodeSummaryItem(53000L, 67000L, "节目提到今晚到明天部分地区可能出现大到暴雨。"),
            EpisodeSummaryItem(209000L, 223000L, "本期城市预报提到南宁多云，24–33℃。")
        )
    )

    @Test
    fun `accepts only a same episode summary whose items overlap actual subtitle cues`() {
        EpisodeSummaryGate.verify(
            summary = summary,
            manifest = manifest,
            currentProgram = "EVENING_WEATHER",
            currentEpisodeDate = "2026-09-23",
            actuallyPlayingMediaUrl = videoUrl,
            cues = cues
        ).shouldBeInstanceOf<EpisodeSummaryResult.Accepted>()

        EpisodeSummaryGate.verify(
            summary = summary.copy(episodeDate = "2026-09-22"),
            manifest = manifest,
            currentProgram = "EVENING_WEATHER",
            currentEpisodeDate = "2026-09-23",
            actuallyPlayingMediaUrl = videoUrl,
            cues = cues
        ).shouldBeInstanceOf<EpisodeSummaryResult.Rejected>()

        EpisodeSummaryGate.verify(
            summary = summary.copy(items = summary.items.toMutableList().also {
                it[0] = it[0].copy(startMs = 12000L, endMs = 13000L)
            }),
            manifest = manifest,
            currentProgram = "EVENING_WEATHER",
            currentEpisodeDate = "2026-09-23",
            actuallyPlayingMediaUrl = videoUrl,
            cues = cues
        ).shouldBeInstanceOf<EpisodeSummaryResult.Rejected>()
    }
}
