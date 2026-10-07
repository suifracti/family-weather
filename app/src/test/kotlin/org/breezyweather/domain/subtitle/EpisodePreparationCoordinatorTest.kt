package org.breezyweather.domain.subtitle

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.breezyweather.domain.multisource.model.SubtitleExtractionMethod
import org.breezyweather.domain.multisource.model.SubtitleMetadata
import org.breezyweather.domain.multisource.model.SubtitleOrigin
import org.breezyweather.domain.multisource.model.SubtitleStatus
import org.breezyweather.domain.multisource.model.SubtitleTrackEvidence
import org.breezyweather.domain.multisource.model.TimestampedCue
import org.breezyweather.domain.subtitle.gate.EpisodeSummaryGate
import org.breezyweather.domain.subtitle.local.EpisodePreparationCache
import org.breezyweather.domain.subtitle.local.EpisodePreparationCoordinator
import org.breezyweather.domain.subtitle.local.EpisodePreparationRequest
import org.breezyweather.domain.subtitle.local.LocalEpisodeArtifacts
import org.breezyweather.domain.subtitle.local.PlaybackVideoCapture
import org.breezyweather.domain.subtitle.model.EpisodeSummary
import org.breezyweather.domain.subtitle.model.EpisodeSummaryItem
import org.breezyweather.domain.subtitle.model.SubtitleManifest
import org.breezyweather.domain.subtitle.resolver.SubtitleResolutionResult
import org.breezyweather.domain.subtitle.util.Sha256Util
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.time.LocalDate

/** Controlled media/ASR boundaries prove sharing and lifecycle; they do not assert ASR accuracy. */
class EpisodePreparationCoordinatorTest {
    @TempDir lateinit var root: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var day = LocalDate.parse("2026-10-03")
    private val request = EpisodePreparationRequest("CHINA_WEATHER_LIANBO", "2026-10-01",
        "https://vod.weathertv.cn/video/2026/10/1/weather.mp4", "weather_com_cn|CHINA_WEATHER_LIANBO|3M|24209")
    private val bytes = "official-media"
    private var downloads = 0
    private var recognitions = 0
    private var failNextRecognition = false
    private val captures = mutableListOf<ControlledCapture>()
    private lateinit var coordinator: EpisodePreparationCoordinator
    private lateinit var cache: EpisodePreparationCache
    private lateinit var repository: LocalEpisodeArtifacts

    private inner class ControlledCapture {
        var file = File(root, "input-${captures.size}.mp4")
        val complete = CompletableDeferred<File>()
        val downloadStarted = CompletableDeferred<Unit>()
        val allowDownload = CompletableDeferred<Unit>()
        val capture = mockk<PlaybackVideoCapture>()
        init {
            every { capture.file } answers { file }
            every { capture.etag } returns "\"official-revision\""
            every { capture.lastModified } returns null
            every { capture.retain() } answers { Closeable {} }
            every { capture.close() } returns Unit
            every { capture.relocateEmptyForCache(any()) } answers { file = firstArg(); true }
            every { capture.persistCompleted(any()) } answers {
                val destination = firstArg<File>()
                if (destination != file) { file.copyTo(destination, overwrite = true); file = destination }
            }
            coEvery { capture.awaitRevision() } returns Unit
            coEvery { capture.awaitComplete(any()) } coAnswers { complete.await() }
            coEvery { capture.downloadMissing(any(), any()) } coAnswers {
                downloads++
                downloadStarted.complete(Unit)
                allowDownload.await()
                secondArg<(Long) -> Unit>()(bytes.length.toLong())
                finish()
            }
        }
        fun finish() {
            file.parentFile!!.mkdirs(); file.writeText(bytes); complete.complete(file)
        }
    }

    private fun setup() {
        val context = mockk<Context>()
        every { context.cacheDir } returns File(root, "cache")
        cache = EpisodePreparationCache(File(context.cacheDir, "episode-preparation"),
            File(context.cacheDir, "local-episode-asr"), { day })
        repository = mockk<LocalEpisodeArtifacts>()
        every { repository.artifactCacheFile(any()) } answers {
            File(context.cacheDir, "local-episode-asr/${firstArg<EpisodePreparationRequest>().key}.json")
        }
        coEvery { repository.resolve(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val progress = arg<suspend (String) -> Unit>(8)
            val capture = arg<PlaybackVideoCapture>(3)
            capture.awaitComplete {}
            progress("准备本机语音识别模型")
            recognitions++
            if (failNextRecognition) { failNextRecognition = false; throw IOException("Decoder cannot open audio") }
            File(context.cacheDir, "local-episode-asr/${request.key}.json").apply { parentFile!!.mkdirs(); writeText("controlled derived cache") }
            available(EpisodePreparationRequest(arg(0), arg(1), arg(2), arg(4)))
        }
        coEvery { repository.generateSummary(any(), any(), any(), any()) } coAnswers {
            val manifest = firstArg<SubtitleManifest>()
            summary().copy(program = manifest.program, episodeDate = manifest.episodeDate,
                sourceVideoUrl = manifest.sourceVideoUrl, sourceVideoSha256 = manifest.sourceVideoSha256,
                subtitleVttSha256 = manifest.vttSha256)
        }
        coordinator = EpisodePreparationCoordinator(context, cache, scope, repository,
            newCapture = { ControlledCapture().also(captures::add).capture },
            restoreCapture = { _, _, _, _ -> ControlledCapture().also { controlled ->
                captures += controlled
                controlled.file = File(cache.directory(request, cache.read(request)!!.directoryDay), "media.mp4")
                controlled.complete.complete(controlled.file)
            }.capture })
    }

    private fun available(episode: EpisodePreparationRequest = request): SubtitleResolutionResult.AVAILABLE {
        val text = "冷空气活动频繁早晚注意保暖"
        val cues = listOf(TimestampedCue(1000, 3000, text, text))
        val manifest = SubtitleManifest("1", episode.program, episode.episodeDate, episode.videoUrl,
            Sha256Util.calculateSha256(bytes), 5000, "AI_ASR_GENERATED", "AUTOMATED_ASR_EXTRACTED",
            LocalEpisodeArtifacts.MODEL, "2026-10-03T00:00:00Z", "subtitle.vtt", "b".repeat(64), 1,
            sourceIdentity = episode.sourceIdentity)
        val metadata = SubtitleMetadata(SubtitleOrigin.AI_ASR_GENERATED, SubtitleExtractionMethod.AUTOMATED_ASR_EXTRACTED,
            "official episode audio", LocalEpisodeArtifacts.MODEL, manifest.generatedAt)
        return SubtitleResolutionResult.AVAILABLE(SubtitleTrackEvidence(episode.episodeDate, metadata,
            SubtitleStatus.AVAILABLE, cues), manifest)
    }
    private fun summary(): EpisodeSummary = EpisodeSummary(request.program, request.episodeDate, request.videoUrl,
        Sha256Util.calculateSha256(bytes), "b".repeat(64), EpisodeSummaryGate.LOCAL_EXTRACTIVE_ASR_METHOD,
        listOf(EpisodeSummaryItem(1000, 3000, "冷空气活动频繁早晚注意保暖", listOf(0))))

    private suspend fun ready(lease: EpisodePreparationCoordinator.Lease): EpisodePreparationCoordinator.State.Ready =
        withTimeout(5000) {
            while (lease.state.value !is EpisodePreparationCoordinator.State.Ready) yield()
            lease.state.value as EpisodePreparationCoordinator.State.Ready
        }

    @Test fun cancellationRespondsBeforeNativeEndsAndRetryCannotStartAnotherRecognition() = runBlocking {
        setup()
        val started = CompletableDeferred<Unit>()
        val nativeEnd = CompletableDeferred<Unit>()
        coEvery { repository.resolve(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            arg<PlaybackVideoCapture>(3).awaitComplete {}
            recognitions++
            started.complete(Unit)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { nativeEnd.await() }
            available()
        }
        val home = coordinator.startHome(request)
        home.close(); captures.single().allowDownload.complete(Unit)
        withTimeout(5000) { started.await() }
        coordinator.cancelHome(request)
        assertTrue(home.state.value is EpisodePreparationCoordinator.State.Cancelling,
            "The UI must acknowledge cancellation while native work still owns the slot")
        val tooEarly = coordinator.startHome(request, retry = true)
        tooEarly.close()
        assertEquals(1, recognitions)
        assertTrue(tooEarly.state.value is EpisodePreparationCoordinator.State.Cancelling)
        nativeEnd.complete(Unit)
        withTimeout(5000) { while (home.state.value !is EpisodePreparationCoordinator.State.Cancelled) yield() }
        assertThrows(Exception::class.java) { runBlocking { home.awaitResult() } }
        val retry = coordinator.startHome(request, retry = true)
        retry.close(); ready(retry)
        assertEquals(2, recognitions)
        assertEquals(1, downloads, "Complete same-episode media is reused after cancellation")
    }

    @Test fun viewingCompletedMediaWithoutCurrentDerivedCacheDoesNotAutomaticallyRecognize() = runBlocking {
        setup()
        val home = coordinator.startHome(request)
        home.close(); captures.single().allowDownload.complete(Unit)
        val saved = ready(home).mediaFile
        assertTrue(repository.artifactCacheFile(request).delete())
        val context = mockk<Context>()
        every { context.cacheDir } returns File(root, "cache")
        val reopened = EpisodePreparationCoordinator(context, cache, scope, repository,
            newCapture = { error("Complete media must not be downloaded again") },
            restoreCapture = { _, _, _, _ -> ControlledCapture().also { controlled ->
                controlled.file = saved; controlled.complete.complete(saved); captures += controlled
            }.capture })
        assertNull(reopened.observeExisting(request))
        assertEquals(1, recognitions, "Observing a card grants no new inference")
        val explicit = reopened.startHome(request)
        explicit.close(); ready(explicit)
        assertEquals(2, recognitions)
        assertEquals(1, downloads)
    }

    @AfterEach fun tearDown() { scope.cancel(); unmockkAll() }

    @Test fun homeAndPlayerShareWorkAndClosingBothObserversDoesNotCancelHome() = runBlocking {
        setup()
        val home = coordinator.startHome(request)
        home.close()
        val player = coordinator.acquireExisting(request)!!
        player.close(); player.close()
        captures.single().allowDownload.complete(Unit)
        val state = ready(home)
        assertEquals(1, downloads, "One requested episode performs one media transfer")
        assertEquals(1, recognitions, "Borrowing the same episode must not recognize it again")
        assertEquals(summary(), state.result.manifest!!.episodeSummary)
        assertTrue(state.mediaFile.isFile)
        assertEquals("2026-10-03", state.preparedOn)
    }

    @Test fun homeArrivingAfterStreamingPlayerUsesItsTaskAndPreparationAcrossMidnight() = runBlocking {
        setup()
        val controlled = ControlledCapture().also(captures::add)
        val player = coordinator.attachPlayer(request, controlled.capture)
        assertEquals(0, downloads, "Ordinary streaming must not authorize a silent download")
        day = day.plusDays(1)
        val home = coordinator.startHome(request)
        home.close(); player.close()
        controlled.allowDownload.complete(Unit)
        val state = ready(home)
        assertEquals(1, downloads)
        assertEquals(1, recognitions)
        assertEquals("2026-10-04", state.preparedOn)
        assertTrue(state.mediaFile.isFile, "The renewed preparation day retains the existing safe path")
        day = day.plusDays(1)
        coordinator.cleanupExpired()
        assertFalse(state.mediaFile.exists())
        assertEquals(EpisodePreparationCoordinator.State.Idle, home.state.value)
    }

    @Test fun cancelledHomeCanExplicitlyRetryAndNormalPlayerRetryDoesNotDownload() = runBlocking {
        setup()
        val home = coordinator.startHome(request)
        home.close()
        coordinator.cancelHome(request)
        withTimeout(5000) {
            while (home.state.value !is EpisodePreparationCoordinator.State.Cancelled) yield()
        }
        val retry = coordinator.startHome(request, retry = true)
        retry.close()
        captures.last().allowDownload.complete(Unit)
        ready(retry)
        assertEquals(2, downloads, "Only the explicit second home action may start another transfer")

        val other = request.copy(episodeDate = "2026-10-02", videoUrl = request.videoUrl + "?next",
            sourceIdentity = "weather_com_cn|CHINA_WEATHER_LIANBO|3M|24210")
        val playerCapture = ControlledCapture().also(captures::add)
        failNextRecognition = true
        val player = coordinator.attachPlayer(other, playerCapture.capture)
        playerCapture.finish()
        withTimeout(5000) {
            while (player.state.value !is EpisodePreparationCoordinator.State.Failed) yield()
        }
        assertEquals(EpisodePreparationCoordinator.FailureKind.TRANSCRIPTION,
            (player.state.value as EpisodePreparationCoordinator.State.Failed).kind)
        val normalRetry = coordinator.retryPlayer(other, playerCapture.capture)
        ready(normalRetry)
        assertEquals(2, downloads, "Retrying ASR from an existing stream does not authorize downloads")
        player.close(); normalRetry.close()
    }

    @Test fun aDamagedLocalMediaCacheIsRejectedBeforeItCanBeBorrowedAndHomeRetryDownloadsAgain() = runBlocking {
        setup()
        val home = coordinator.startHome(request)
        home.close(); captures.single().allowDownload.complete(Unit)
        val original = ready(home).mediaFile
        original.writeText("x".repeat(bytes.length)) // Same length, different bytes: ETag/length are insufficient.
        val restored = EpisodePreparationCoordinator(mockk<Context>(), cache, scope, repository,
            newCapture = { ControlledCapture().also(captures::add).capture },
            restoreCapture = { _, file, etag, _ ->
                assertNull(etag, "Stored ETag must not be treated as a fresh remote revision")
                ControlledCapture().also { captures += it; it.file = file; it.complete.complete(file) }.capture
            })
        val borrowed = restored.acquireExisting(request)!!
        assertThrows(Exception::class.java) { runBlocking { borrowed.awaitMedia() } }
        assertTrue(borrowed.state.value is EpisodePreparationCoordinator.State.Failed)
        assertNull(cache.completed(request), "Invalid media must not be restored on every retry")
        assertEquals(1, recognitions, "Corrupted local bytes must never reach the recognizer")
        borrowed.close()
        val retry = restored.startHome(request, retry = true)
        retry.close(); captures.last().allowDownload.complete(Unit)
        assertEquals(bytes, ready(retry).mediaFile.readText())
        assertEquals(2, downloads, "An explicit retry replaces the rejected bytes through a new transfer")
    }

    @Test fun closingACardObserverDoesNotCancelOfflineDiskRestoreValidation() = runBlocking {
        setup()
        val home = coordinator.startHome(request)
        home.close(); captures.single().allowDownload.complete(Unit)
        val saved = ready(home).mediaFile
        val validationStarted = CompletableDeferred<Unit>()
        val allowValidation = CompletableDeferred<Unit>()
        coEvery { repository.resolve(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            validationStarted.complete(Unit)
            allowValidation.await()
            available()
        }
        val reopened = EpisodePreparationCoordinator(mockk<Context>(), cache, scope, repository,
            newCapture = { ControlledCapture().also(captures::add).capture },
            restoreCapture = { _, file, etag, _ ->
                assertNull(etag)
                ControlledCapture().also { captures += it; it.file = file; it.complete.complete(file) }.capture
            })
        val observer = reopened.observeExisting(request)!!
        withTimeout(5000) { validationStarted.await() }
        observer.close(); observer.close()
        assertTrue(observer.state.value is EpisodePreparationCoordinator.State.Preparing)
        allowValidation.complete(Unit)
        val restored = ready(observer)
        assertEquals(saved, restored.mediaFile)
        assertEquals(summary(), restored.result.manifest!!.episodeSummary)
        assertEquals(1, downloads, "Observing a saved episode must not authorize another download")
    }
}
