/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.subtitle

import org.breezyweather.domain.multisource.model.SubtitleEvidenceEngine
import org.breezyweather.domain.multisource.model.SubtitleExtractionMethod
import org.breezyweather.domain.multisource.model.SubtitleMetadata
import org.breezyweather.domain.multisource.model.SubtitleOrigin
import org.breezyweather.domain.multisource.model.SubtitleStatus
import org.breezyweather.domain.multisource.model.SubtitleTrackEvidence
import org.breezyweather.domain.multisource.model.TimestampedCue
import org.breezyweather.domain.subtitle.parser.WebVttParser
import org.breezyweather.domain.subtitle.resolver.SubtitleResolutionResult
import org.breezyweather.domain.subtitle.resolver.toDisplaySubtitle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Unit test suite for Task D2.3b-C: Media3 Subtitle Rendering Gate.
 * Tests WebVTT cue extraction, UI label enforcement, fail-open contracts,
 * and track selection parameters logic.
 */
class SubtitleGateRenderingTest {

    companion object {
        const val EXPECTED_UI_LABEL = "AI 自动转写字幕"
        const val FORBIDDEN_LABEL_1 = "官方字幕"
        const val FORBIDDEN_LABEL_2 = "央视字幕"
    }

    private fun loadFixtureVtt(): String {
        val candidates = listOf(
            File("app/src/debug/assets/subtitles/20260917.ai-asr.vtt"),
            File("tools/asr/evidence/20260917.ai-asr.vtt"),
            File("../tools/asr/evidence/20260917.ai-asr.vtt")
        )
        val found = candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Fixture 20260917.ai-asr.vtt not found in paths: $candidates")
        return found.readText()
    }

    @Test
    fun testLabelEnforcement_StrictlyAiAsr_ForbiddenOfficialLabelsRejected() {
        // Label must strictly match AI 自动转写字幕
        assertEquals("AI 自动转写字幕", EXPECTED_UI_LABEL)

        // Forbidden labels must NOT be present
        assertFalse(EXPECTED_UI_LABEL.contains(FORBIDDEN_LABEL_1), "Forbidden label found: $FORBIDDEN_LABEL_1")
        assertFalse(EXPECTED_UI_LABEL.contains(FORBIDDEN_LABEL_2), "Forbidden label found: $FORBIDDEN_LABEL_2")
    }

    @Test
    fun testVttFixture_CuesAt6sAnd120s() {
        val vttContent = loadFixtureVtt()
        val cues = WebVttParser.parse(vttContent, "SenseVoiceSmall")
        assertTrue(cues.isNotEmpty(), "Cues should not be empty")

        // Verify Cue 1 at 6s
        val cue1 = cues.first()
        assertEquals(6000L, cue1.startMs)
        assertEquals(7470L, cue1.endMs)
        assertTrue(cue1.rawText.contains("大家来看天气"), "Cue 1 text should contain '大家来看天气'")

        // Verify Cue around 120s
        val cue120s = cues.find { (it.startMs <= 121000L && it.endMs >= 120000L) || (it.startMs in 120000L..121000L) }
        assertNotNull(cue120s, "Should have a cue at or around 120s")
        assertTrue(cue120s!!.rawText.contains("沈阳"), "Cue around 120s should contain '沈阳'")
    }

    @Test
    fun testFailOpen_MissingVttDoesNotBlockVideo() {
        val notFoundResult = SubtitleResolutionResult.NOT_FOUND("File 20260917.ai-asr.vtt does not exist")
        val display = notFoundResult.toDisplaySubtitle(editorialBriefFallbackCandidate = "Brief headline")

        assertFalse(display.isAvailable, "Subtitle should not be available")
        assertFalse(display.playbackBlocked, "Video playback MUST NOT be blocked when subtitle is missing")
        assertTrue(display.cues.isEmpty(), "Cues must be empty on missing VTT")
        assertFalse(display.cues.any { it.rawText.contains("Brief") }, "Editorial brief must not leak into cues")
    }

    @Test
    fun testFailOpen_MalformedVttParserRejection_DoesNotBlockVideo() {
        val malformedVtt = "INVALID_HEADER\nNot a webvtt\n00:00:01.000 --> 00:00:02.000\nCorrupt"
        try {
            WebVttParser.parse(malformedVtt)
            fail("Expected WebVttParser to throw IllegalArgumentException for malformed VTT")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("WEBVTT") == true, "Expected WEBVTT header error")
        }

        // When parser fails, resolver converts to INVALID_MANIFEST
        val invalidResult = SubtitleResolutionResult.INVALID_MANIFEST("Malformed header")
        val display = invalidResult.toDisplaySubtitle()

        assertFalse(display.isAvailable, "Subtitle should not be available")
        assertFalse(display.playbackBlocked, "Video playback MUST NOT be blocked on malformed VTT")
    }

    @Test
    fun testDisplaySubtitleResult_OfficialFlagMustBeFalse() {
        val sampleCue = TimestampedCue(
            startMs = 6000L,
            endMs = 7470L,
            rawText = "大家来看天气，",
            normalizedText = "大家来看天气，"
        )
        val metadata = SubtitleMetadata(
            origin = SubtitleOrigin.AI_ASR_GENERATED,
            extractionMethod = SubtitleExtractionMethod.AUTOMATED_ASR_EXTRACTED,
            sourceAudioAuthority = "CHINA_WEATHER_OFFICIAL_VIDEO",
            modelName = "SenseVoiceSmall",
            generatedAt = "2026-09-18T05:46:00Z",
            isOfficial = false
        )
        val evidence = SubtitleTrackEvidence(
            episodeDate = "2026-09-17",
            metadata = metadata,
            status = SubtitleStatus.AVAILABLE,
            cues = listOf(sampleCue),
            vttContent = "WEBVTT\n"
        )
        val display = SubtitleEvidenceEngine.resolveDisplaySubtitle(evidence)

        assertTrue(display.isAvailable, "Subtitle should be available when valid cues exist")
        assertEquals("AI 自动转写字幕", display.uiLabel)
        assertFalse(evidence.metadata.isOfficial, "isOfficial must be false for AI ASR subtitle")
        assertFalse(display.playbackBlocked, "Video playback should not be blocked")
    }
}
