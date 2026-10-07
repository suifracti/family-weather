/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.breezyweather.domain.multisource.model.DisplaySubtitleResult
import org.breezyweather.domain.multisource.model.SubtitleEvidenceEngine
import org.breezyweather.domain.multisource.model.SubtitleExtractionMethod
import org.breezyweather.domain.multisource.model.SubtitleMetadata
import org.breezyweather.domain.multisource.model.SubtitleOrigin
import org.breezyweather.domain.multisource.model.SubtitleStatus
import org.breezyweather.domain.multisource.model.SubtitleTrackEvidence
import org.breezyweather.domain.multisource.model.TimestampedCue
import org.junit.jupiter.api.Test

class SubtitleEvidenceTest {

    @Test
    fun `test AI_ASR_GENERATED metadata forbids isOfficial and enforces AI display label`() {
        val meta = SubtitleMetadata(
            origin = SubtitleOrigin.AI_ASR_GENERATED,
            extractionMethod = SubtitleExtractionMethod.AUTOMATED_ASR_EXTRACTED,
            sourceAudioAuthority = "CHINA_WEATHER_OFFICIAL_VIDEO",
            modelName = "SenseVoiceSmall",
            generatedAt = "2026-09-18T05:46:00Z"
        )

        meta.isOfficial shouldBe false
        meta.uiLabel shouldBe "AI 自动转写字幕"
        meta.uiLabel shouldNotBe "央视字幕"
        meta.uiLabel shouldNotBe "官方字幕"

        // Impersonating official authority throws IllegalArgumentException
        shouldThrow<IllegalArgumentException> {
            SubtitleMetadata(
                origin = SubtitleOrigin.AI_ASR_GENERATED,
                extractionMethod = SubtitleExtractionMethod.AUTOMATED_ASR_EXTRACTED,
                sourceAudioAuthority = "CHINA_WEATHER_OFFICIAL_VIDEO",
                modelName = "SenseVoiceSmall",
                generatedAt = "2026-09-18T05:46:00Z",
                isOfficial = true
            )
        }
    }

    @Test
    fun `test official subtitles return official label`() {
        val meta = SubtitleMetadata(
            origin = SubtitleOrigin.OFFICIAL_SEPARATE_SUBTITLE,
            extractionMethod = SubtitleExtractionMethod.OFFICIAL_STREAM_EXTRACTED,
            sourceAudioAuthority = "CCTV_BROADCAST_FEED",
            modelName = "N/A",
            generatedAt = "2026-09-18T00:00:00Z"
        )

        meta.isOfficial shouldBe true
        meta.uiLabel shouldBe "官方字幕"
    }

    @Test
    fun `test WebVTT validation succeeds on monotonic valid cues`() {
        val cues = listOf(
            TimestampedCue(6000, 7470, "大家来看天气，", "大家来看天气，", null, "SenseVoiceSmall"),
            TimestampedCue(7470, 11190, "过去两天四川盆地", "过去两天四川盆地", null, "SenseVoiceSmall"),
            TimestampedCue(11640, 12450, "降水频繁", "降水频繁", null, "SenseVoiceSmall"),
            TimestampedCue(26000, 30150, "累计降雨量达到250毫米", "累计降雨量达到250毫米", null, "SenseVoiceSmall")
        )

        val result = SubtitleEvidenceEngine.validateCues(cues, maxDurationMs = 260054L)
        result.isValid shouldBe true
        result.isMonotonic shouldBe true
        result.exceedsDuration shouldBe false
        result.cueCount shouldBe 4
        result.validationErrors.isEmpty() shouldBe true
    }

    @Test
    fun `test WebVTT validation rejects non-monotonic timestamps`() {
        val nonMonotonicCues = listOf(
            TimestampedCue(10000, 15000, "第一句", "第一句"),
            TimestampedCue(8000, 12000, "倒流的时间戳", "倒流的时间戳") // starts earlier than cue 1
        )

        val result = SubtitleEvidenceEngine.validateCues(nonMonotonicCues, maxDurationMs = 260054L)
        result.isValid shouldBe false
        result.isMonotonic shouldBe false
        result.validationErrors.size shouldBe 1
    }

    @Test
    fun `test WebVTT validation rejects cues exceeding media duration`() {
        val durationExceededCues = listOf(
            TimestampedCue(250000, 265000, "超时字幕", "超时字幕") // 265s > 260.054s
        )

        val result = SubtitleEvidenceEngine.validateCues(durationExceededCues, maxDurationMs = 260054L)
        result.isValid shouldBe false
        result.exceedsDuration shouldBe true
        result.validationErrors.size shouldBe 1
    }

    @Test
    fun `test fail-closed when subtitle evidence is null or unavailable`() {
        // When evidence is null, subtitle is unavailable and video playback is NOT blocked
        val resultNull = SubtitleEvidenceEngine.resolveDisplaySubtitle(
            evidence = null,
            editorialBriefFallbackCandidate = "9月17日晚间天气预报：四川盆地强降雨"
        )
        resultNull.isAvailable shouldBe false
        resultNull.uiLabel shouldBe "字幕暂不可用"
        resultNull.cues.isEmpty() shouldBe true
        resultNull.playbackBlocked shouldBe false

        // Editorial brief is strictly forbidden from being used as subtitle text
        resultNull.cues.none { it.rawText.contains("四川盆地强降雨") } shouldBe true

        // When evidence status is UNAVAILABLE
        val unavailableEvidence = SubtitleTrackEvidence(
            episodeDate = "2026-09-17",
            metadata = SubtitleMetadata(
                origin = SubtitleOrigin.AI_ASR_GENERATED,
                extractionMethod = SubtitleExtractionMethod.AUTOMATED_ASR_EXTRACTED,
                sourceAudioAuthority = "CHINA_WEATHER_OFFICIAL_VIDEO",
                modelName = "SenseVoiceSmall",
                generatedAt = "2026-09-18T05:46:00Z"
            ),
            status = SubtitleStatus.UNAVAILABLE,
            cues = emptyList()
        )

        val resultUnavailable = SubtitleEvidenceEngine.resolveDisplaySubtitle(unavailableEvidence)
        resultUnavailable.isAvailable shouldBe false
        resultUnavailable.uiLabel shouldBe "字幕暂不可用"
        resultUnavailable.playbackBlocked shouldBe false
    }

    @Test
    fun `test fail-closed when subtitle evidence has error status`() {
        val errorEvidence = SubtitleTrackEvidence(
            episodeDate = "2026-09-17",
            metadata = SubtitleMetadata(
                origin = SubtitleOrigin.AI_ASR_GENERATED,
                extractionMethod = SubtitleExtractionMethod.AUTOMATED_ASR_EXTRACTED,
                sourceAudioAuthority = "CHINA_WEATHER_OFFICIAL_VIDEO",
                modelName = "faster-whisper-small",
                generatedAt = "2026-09-18T05:46:00Z"
            ),
            status = SubtitleStatus.ERROR,
            cues = emptyList()
        )

        val resultError = SubtitleEvidenceEngine.resolveDisplaySubtitle(errorEvidence)
        resultError.isAvailable shouldBe false
        resultError.uiLabel shouldBe "字幕暂不可用"
        resultError.playbackBlocked shouldBe false
        resultError.errorReason shouldBe "SUBTITLE_STATUS_ERROR"
    }
}
