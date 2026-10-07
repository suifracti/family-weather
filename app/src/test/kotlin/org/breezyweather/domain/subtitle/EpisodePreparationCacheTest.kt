package org.breezyweather.domain.subtitle

import org.breezyweather.domain.subtitle.local.EpisodePreparationCache
import org.breezyweather.domain.subtitle.local.EpisodePreparationRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.LocalDate

/** Known local-day and disk fixtures; no Android player, decoder or recognizer is used. */
class EpisodePreparationCacheTest {
    @TempDir lateinit var root: File
    private var today = LocalDate.of(2026, 10, 3)
    private lateinit var cache: EpisodePreparationCache
    private lateinit var preparationRoot: File
    private lateinit var artifactRoot: File
    private lateinit var legacySubtitleRoot: File
    private val artifactName = "a".repeat(64) + ".json"
    private val mediaText = "known episode media fixture"
    private val subtitleText = "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\n明天北方降温\n\n"

    @BeforeEach fun setup() {
        today = LocalDate.of(2026, 10, 3)
        preparationRoot = File(root, "cache/episode-preparation")
        artifactRoot = File(root, "cache/local-episode-asr").apply { mkdirs() }
        legacySubtitleRoot = File(root, "cache/subtitles")
        cache = EpisodePreparationCache(preparationRoot, artifactRoot, { today }, legacySubtitleRoot)
    }

    private fun request(id: String = "24222", episodeDate: String = "2026-10-02") =
        EpisodePreparationRequest("CHINA_WEATHER_LIANBO", episodeDate,
            "https://vod.weathertv.cn/video/$id.mp4", "weather_com_cn|CHINA_WEATHER_LIANBO|3M|$id")

    private data class Fixture(val folder: File, val media: File, val vtt: File, val artifact: File)

    private fun prepared(request: EpisodePreparationRequest, day: String,
                         name: String = artifactName): Fixture {
        cache.begin(request, day, name)
        val folder = cache.directory(request, day)
        val media = File(folder, "media.mp4").apply { writeText(mediaText) }
        val vtt = File(folder, "subtitles/known.vtt").apply { parentFile!!.mkdirs(); writeText(subtitleText) }
        val artifact = File(artifactRoot, name).apply { writeText("registered subtitle artifact") }
        return Fixture(folder, media, vtt, artifact)
    }

    @Test fun explicitBackendSwitchSharesMediaAndRetainsBothRegisteredArtifactsUntilReadersRelease() {
        val sv = request()
        val fixture = prepared(sv, "2026-10-03")
        val vosk = sv.copy(asrBackend = "vosk")
        val voskName = "b".repeat(64) + ".json"
        cache.begin(vosk, "2026-10-03", voskName)
        val voskArtifact = File(artifactRoot, voskName).apply { writeText("explicit Vosk result") }
        assertEquals(fixture.folder, cache.directory(vosk, "2026-10-03"))
        assertEquals(mediaText, fixture.media.readText())
        val player = cache.retain(vosk, "2026-10-03")
        today = today.plusDays(1); cache.cleanupExpired()
        assertTrue(fixture.artifact.exists()); assertTrue(voskArtifact.exists())
        player.close(); cache.cleanupExpired()
        assertFalse(fixture.folder.exists()); assertFalse(fixture.artifact.exists()); assertFalse(voskArtifact.exists())
    }

    @Test fun preparingYesterdaysEpisodeUsesPreparationDayAndExpiresOnTheNextDay() {
        val episode = request()
        val fixture = prepared(episode, "2026-10-03")
        val backup = File(artifactRoot, "$artifactName.bak").apply { writeText("old atomic backup") }
        val pending = File(artifactRoot, "$artifactName.new").apply { writeText("interrupted atomic write") }

        cache.cleanupExpired()
        assertEquals("2026-10-03", cache.read(episode)!!.preparedOn)
        assertEquals(mediaText, fixture.media.readText())
        assertEquals(subtitleText, fixture.vtt.readText())
        assertTrue(fixture.artifact.exists(), "Yesterday's episode prepared today must remain usable today")

        today = LocalDate.of(2026, 10, 4)
        cache.cleanupExpired()
        assertFalse(fixture.folder.exists())
        assertNull(cache.read(episode, "2026-10-03"))
        assertFalse(fixture.artifact.exists())
        assertFalse(backup.exists())
        assertFalse(pending.exists())
    }

    @Test fun playbackAndWriterLeasesPreserveMediaSubtitlesAndRecordUntilBothRelease() {
        val episode = request()
        val fixture = prepared(episode, "2026-10-03")
        val playback = cache.retain(episode, "2026-10-03")
        val writer = cache.retain(episode, "2026-10-03")
        try {
            today = LocalDate.of(2026, 10, 4)
            cache.cleanupExpired()
            assertEquals(mediaText, fixture.media.readText())
            assertEquals(subtitleText, fixture.vtt.readText())
            assertNotNull(cache.read(episode, "2026-10-03"))
            assertTrue(fixture.artifact.exists())

            playback.close()
            cache.cleanupExpired()
            assertTrue(fixture.folder.exists(), "A remaining writer still owns the previous day's files")

            writer.close()
            cache.cleanupExpired()
            assertFalse(fixture.folder.exists())
            assertFalse(fixture.artifact.exists())
            assertNull(cache.read(episode, "2026-10-03"))
        } finally {
            playback.close()
            writer.close()
        }
    }

    @Test fun deletingAnExpiredPreparationKeepsTheArtifactSharedWithAnActiveNewDay() {
        val episode = request()
        val old = prepared(episode, "2026-10-02")
        val current = prepared(episode, "2026-10-03")
        val playback = cache.retain(episode, "2026-10-03")
        try {
            cache.cleanupExpired()
            assertFalse(old.folder.exists())
            assertEquals(mediaText, current.media.readText())
            assertEquals(subtitleText, current.vtt.readText())
            assertEquals("registered subtitle artifact", current.artifact.readText())

            today = LocalDate.of(2026, 10, 4)
            cache.cleanupExpired()
            assertTrue(current.folder.exists())
            assertTrue(current.artifact.exists())

            playback.close()
            cache.cleanupExpired()
            assertFalse(current.folder.exists())
            assertFalse(current.artifact.exists())
        } finally {
            playback.close()
        }
    }

    @Test fun cleanupRemovesOnlyRegisteredPreparationsAndIdentifiedLegacyAsrFiles() {
        val expired = prepared(request(), "2026-10-02")
        val legacyName = "b".repeat(64) + ".json"
        val vttHash = "c".repeat(64)
        val legacyArtifact = File(artifactRoot, legacyName).apply {
            writeText("""{"manifest":{"program":"CHINA_WEATHER_LIANBO","episodeDate":"2026-10-01","subtitleOrigin":"AI_ASR_GENERATED","asrModel":"vosk-model-small-cn-0.22","generatedAt":"2026-10-01T10:00:00Z","vttSha256":"$vttHash"}}""")
        }
        val legacyVtt = File(legacySubtitleRoot, "evening-weather/2026-10-01/$vttHash.vtt").apply {
            parentFile!!.mkdirs(); writeText(subtitleText)
        }
        val retained = linkedMapOf(
            File(root, "files/local-asr-models/vosk-model-small-cn-0.22/am/final.mdl") to "installed model",
            File(root, "shared_prefs/settings.xml") to "user settings",
            File(root, "files/user-saved/family-video.mp4") to "user's saved media",
            File(artifactRoot, "d".repeat(64) + ".json") to """{"manifest":{"program":"CHINA_WEATHER_LIANBO","subtitleOrigin":"USER_PROVIDED"}}""",
            File(artifactRoot, "user-notes.txt") to "user notes",
            File(legacySubtitleRoot, "evening-weather/2026-10-01/user-saved.vtt") to "user's saved subtitles"
        )
        retained.forEach { (file, text) -> file.parentFile!!.mkdirs(); file.writeText(text) }

        cache.cleanupExpired()
        assertFalse(expired.folder.exists())
        assertFalse(expired.artifact.exists())
        assertFalse(legacyArtifact.exists())
        assertFalse(legacyVtt.exists())
        retained.forEach { (file, text) -> assertEquals(text, file.readText(), "Must retain ${file.name}") }
    }

    @Test fun combinedMediaReservationsRespectThe160MibLimitAcrossLocalDays() {
        val old = request("24209", "2026-10-01")
        val current = request()
        val another = request("24223")
        val mib = 1024L * 1024
        val oldLease = cache.retain(old, "2026-10-02")
        try {
            cache.reserve(old, "2026-10-02", 100 * mib)
            cache.reserve(current, "2026-10-03", 60 * mib)
            assertThrows(IOException::class.java) { cache.reserve(another, "2026-10-03", 1) }

            cache.releaseReservation(current, "2026-10-03")
            assertThrows(IOException::class.java) { cache.reserve(another, "2026-10-03", 70 * mib) }

            cache.releaseReservation(old, "2026-10-02")
            cache.reserve(another, "2026-10-03", 160 * mib)
            assertThrows(IOException::class.java) { cache.reserve(another, "2026-10-03", 160 * mib + 1) }
        } finally {
            oldLease.close()
            cache.releaseReservation(old, "2026-10-02")
            cache.releaseReservation(current, "2026-10-03")
            cache.releaseReservation(another, "2026-10-03")
        }
    }
}
