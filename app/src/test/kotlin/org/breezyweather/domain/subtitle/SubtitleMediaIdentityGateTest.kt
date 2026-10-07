/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.subtitle

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import org.breezyweather.domain.multisource.model.SubtitleOrigin
import org.breezyweather.domain.subtitle.gate.SubtitleMediaIdentityGate
import org.breezyweather.domain.subtitle.gate.SubtitleMediaIdentityResult
import org.breezyweather.domain.subtitle.model.SubtitleManifest
import org.junit.jupiter.api.Test

class SubtitleMediaIdentityGateTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun createTestManifest(
        program: String = "EVENING_WEATHER",
        episodeDate: String = "2026-09-17",
        sourceVideoUrl: String = "https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4",
        durationMs: Long = 260000L,
        schemaVersion: String = "1.0.0"
    ): SubtitleManifest {
        return SubtitleManifest(
            schemaVersion = schemaVersion,
            program = program,
            episodeDate = episodeDate,
            sourceVideoUrl = sourceVideoUrl,
            sourceVideoSha256 = "454C707852EA609C66273C79C799A9DF0287C42FA1BC129809041F020E5C2BAE",
            durationMs = durationMs,
            subtitleOrigin = SubtitleOrigin.AI_ASR_GENERATED.name,
            extractionMethod = "AUTOMATED_ASR_EXTRACTED",
            asrModel = "SenseVoiceSmall",
            generatedAt = "2026-09-18T05:46:00Z",
            vttFile = "subtitle.vtt",
            vttSha256 = "405990E82E88BD2F868964006B1B0E50D3407DA216DA262AC2FCBB2589B9BF4B",
            cueCount = 70
        )
    }

    @Test
    fun `test duration tolerance boundary 999ms pass, 1000ms pass, 1001ms reject`() {
        val baseDuration = 260000L

        // 999ms diff -> Pass
        val res999 = SubtitleMediaIdentityGate.verifyDurationTolerance(
            manifestDurationMs = baseDuration,
            playerDurationMs = baseDuration + 999L
        )
        res999 shouldBe SubtitleMediaIdentityResult.Verified

        // -999ms diff -> Pass
        val resMinus999 = SubtitleMediaIdentityGate.verifyDurationTolerance(
            manifestDurationMs = baseDuration,
            playerDurationMs = baseDuration - 999L
        )
        resMinus999 shouldBe SubtitleMediaIdentityResult.Verified

        // Exactly 1000ms diff -> Pass
        val res1000 = SubtitleMediaIdentityGate.verifyDurationTolerance(
            manifestDurationMs = baseDuration,
            playerDurationMs = baseDuration + 1000L
        )
        res1000 shouldBe SubtitleMediaIdentityResult.Verified

        // Exactly -1000ms diff -> Pass
        val resMinus1000 = SubtitleMediaIdentityGate.verifyDurationTolerance(
            manifestDurationMs = baseDuration,
            playerDurationMs = baseDuration - 1000L
        )
        resMinus1000 shouldBe SubtitleMediaIdentityResult.Verified

        // 1001ms diff -> Reject
        val res1001 = SubtitleMediaIdentityGate.verifyDurationTolerance(
            manifestDurationMs = baseDuration,
            playerDurationMs = baseDuration + 1001L
        )
        res1001.shouldBeInstanceOf<SubtitleMediaIdentityResult.Mismatch>()
        res1001.reason.contains("exceeds tolerance") shouldBe true

        // -1001ms diff -> Reject
        val resMinus1001 = SubtitleMediaIdentityGate.verifyDurationTolerance(
            manifestDurationMs = baseDuration,
            playerDurationMs = baseDuration - 1001L
        )
        resMinus1001.shouldBeInstanceOf<SubtitleMediaIdentityResult.Mismatch>()
        resMinus1001.reason.contains("exceeds tolerance") shouldBe true
    }

    @Test
    fun `test indeterminate player duration does not cause false rejection`() {
        val resZero = SubtitleMediaIdentityGate.verifyDurationTolerance(260000L, 0L)
        resZero shouldBe SubtitleMediaIdentityResult.Verified

        val resNegative = SubtitleMediaIdentityGate.verifyDurationTolerance(260000L, -1L)
        resNegative shouldBe SubtitleMediaIdentityResult.Verified
    }

    @Test
    fun `test pre-mount identity verification success`() {
        val manifest = createTestManifest()
        val result = SubtitleMediaIdentityGate.verifyPreMountIdentity(
            manifest = manifest,
            currentProgram = "EVENING_WEATHER",
            currentEpisodeDate = "2026-09-17",
            actuallyPlayingMediaUrl = "https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4"
        )
        result shouldBe SubtitleMediaIdentityResult.Verified
    }

    @Test
    fun `test pre-mount identity normalization for program slugs`() {
        val manifest = createTestManifest(program = "EVENING_WEATHER")
        val result = SubtitleMediaIdentityGate.verifyPreMountIdentity(
            manifest = manifest,
            currentProgram = "evening-weather",
            currentEpisodeDate = "2026-09-17",
            actuallyPlayingMediaUrl = "https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4"
        )
        result shouldBe SubtitleMediaIdentityResult.Verified
    }

    @Test
    fun `test pre-mount rejects episode date mismatch`() {
        val manifest = createTestManifest(episodeDate = "2026-09-17")
        val result = SubtitleMediaIdentityGate.verifyPreMountIdentity(
            manifest = manifest,
            currentProgram = "EVENING_WEATHER",
            currentEpisodeDate = "2026-09-18",
            actuallyPlayingMediaUrl = "https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4"
        )
        result.shouldBeInstanceOf<SubtitleMediaIdentityResult.Mismatch>()
        result.reason.contains("Episode date mismatch") shouldBe true
    }

    @Test
    fun `test pre-mount rejects program mismatch`() {
        val manifest = createTestManifest(program = "MORNING_WEATHER")
        val result = SubtitleMediaIdentityGate.verifyPreMountIdentity(
            manifest = manifest,
            currentProgram = "EVENING_WEATHER",
            currentEpisodeDate = "2026-09-17",
            actuallyPlayingMediaUrl = "https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4"
        )
        result.shouldBeInstanceOf<SubtitleMediaIdentityResult.Mismatch>()
        result.reason.contains("Program mismatch") shouldBe true
    }

    @Test
    fun `test pre-mount rejects source video URL mismatch`() {
        val manifest = createTestManifest()
        val result = SubtitleMediaIdentityGate.verifyPreMountIdentity(
            manifest = manifest,
            currentProgram = "EVENING_WEATHER",
            currentEpisodeDate = "2026-09-17",
            actuallyPlayingMediaUrl = "https://vod.weathertv.cn/video/2026/9/18/different_video.mp4"
        )
        result.shouldBeInstanceOf<SubtitleMediaIdentityResult.Mismatch>()
        result.reason.contains("Source video URL mismatch") shouldBe true
    }

    @Test
    fun `test publisher provenance flag is true`() {
        SubtitleMediaIdentityGate.PUBLISHER_PROVENANCE_ONLY shouldBe true
    }

    @Test
    fun `test manifest deserializes numeric schemaVersion and string schemaVersion`() {
        val numericJson = """
            {
              "schemaVersion": 1,
              "program": "EVENING_WEATHER",
              "episodeDate": "2026-09-17",
              "sourceVideoUrl": "https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4",
              "sourceVideoSha256": "454C707852EA609C66273C79C799A9DF0287C42FA1BC129809041F020E5C2BAE",
              "durationMs": 260032,
              "subtitleOrigin": "AI_ASR_GENERATED",
              "extractionMethod": "AUTOMATED_ASR_EXTRACTED",
              "asrModel": "SenseVoiceSmall",
              "generatedAt": "2026-09-18T05:46:00Z",
              "vttFile": "subtitle.vtt",
              "vttSha256": "405990E82E88BD2F868964006B1B0E50D3407DA216DA262AC2FCBB2589B9BF4B",
              "cueCount": 70
            }
        """.trimIndent()

        val parsedNumeric = json.decodeFromString<SubtitleManifest>(numericJson)
        parsedNumeric.schemaVersion shouldBe "1"
        parsedNumeric.isSupportedSchemaVersion() shouldBe true

        val stringJson = numericJson.replace("\"schemaVersion\": 1", "\"schemaVersion\": \"1.0.0\"")
        val parsedString = json.decodeFromString<SubtitleManifest>(stringJson)
        parsedString.schemaVersion shouldBe "1.0.0"
        parsedString.isSupportedSchemaVersion() shouldBe true
    }
}
