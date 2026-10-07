package org.breezyweather.domain.subtitle

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.breezyweather.domain.multisource.model.TimestampedCue
import org.breezyweather.domain.subtitle.gate.SubtitleMediaIdentityGate
import org.breezyweather.domain.subtitle.gate.SubtitleMediaIdentityResult
import org.breezyweather.domain.subtitle.local.*
import org.breezyweather.domain.subtitle.model.SubtitleManifest
import org.breezyweather.domain.subtitle.model.EpisodeSummary
import org.breezyweather.domain.subtitle.model.EpisodeSummaryItem
import org.breezyweather.domain.subtitle.gate.EpisodeSummaryGate
import org.breezyweather.domain.subtitle.resolver.SubtitleResolutionResult
import org.breezyweather.domain.subtitle.util.Sha256Util
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.objenesis.ObjenesisStd
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Controlled Android/native substitutes verify the repository protocol, not ASR quality. */
class LocalEpisodeArtifactsConcurrencyTest {
    @TempDir lateinit var root: File
    private lateinit var repository: LocalEpisodeArtifacts
    private val program = "EVENING_WEATHER"
    private val date = "2026-09-29"
    private val url = "https://vod.weathertv.cn/video/test/20260929.mp4"
    private val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\n国庆冷空气频繁，早晚注意保暖\n\n"
    private val cues = listOf(TimestampedCue(1000, 3000, "国庆冷空气频繁，早晚注意保暖", "国庆冷空气频繁，早晚注意保暖"))
    private val json = Json { encodeDefaults = true }

    @BeforeEach fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.isLoggable(any(), any()) } returns false
        mockkConstructor(AtomicFile::class)
        mockkConstructor(AndroidVideoTranscriber::class)
        val context = mockk<Context>()
        every { context.cacheDir } returns File(root, "cache")
        every { context.filesDir } returns File(root, "files")
        val model = File(context.filesDir, "local-asr-models/${LocalEpisodeArtifacts.MODEL}")
        File(model, "am").mkdirs()
        File(model, "am/final.mdl").writeText("test native substitute")
        File(model, "installed.sha256").writeText(LocalEpisodeArtifacts.MODEL_SHA256)
        repository = LocalEpisodeArtifacts(context)
        coEvery { anyConstructed<AndroidVideoTranscriber>().transcribe(any(), any(), any()) } returns
            AndroidVideoTranscriber.Transcript(5000, cues)
    }

    @AfterEach fun cleanup() { unmockkAll() }

    private fun cacheFile(d: String = date, u: String = url): File {
        val key = Sha256Util.calculateSha256("$program|$d|$u|${LocalEpisodeArtifacts.MODEL}|qwen-0.5b-q4-v1")
        val file = File(root, "cache/local-episode-asr/$key.json")
        file.parentFile!!.mkdirs()
        every { constructedWith<AtomicFile>(EqMatcher(file)).baseFile } returns file
        every { constructedWith<AtomicFile>(EqMatcher(file)).openRead() } answers { file.inputStream() }
        every { constructedWith<AtomicFile>(EqMatcher(file)).startWrite() } answers { file.outputStream() }
        every { constructedWith<AtomicFile>(EqMatcher(file)).finishWrite(any()) } answers { firstArg<java.io.FileOutputStream>().close() }
        every { constructedWith<AtomicFile>(EqMatcher(file)).failWrite(any()) } answers { firstArg<java.io.FileOutputStream>().close() }
        return file
    }

    private fun media(text: String = "test media"): File = File(root, "media-${System.nanoTime()}").apply { writeText(text) }
    private fun hash(video: File) = Sha256Util.calculateSha256(video.readText())
    private fun manifest(video: File, d: String = date, u: String = url) = SubtitleManifest(
        "1", program, d, u, hash(video), 5000, "AI_ASR_GENERATED", "AUTOMATED_ASR_EXTRACTED",
        LocalEpisodeArtifacts.MODEL, "2026-09-30T00:00:00Z", "subtitle.vtt", Sha256Util.calculateSha256(vtt), 1,
        asrPipelineVersion = AndroidVideoTranscriber.PIPELINE_VERSION,
        asrModelSha256 = LocalEpisodeArtifacts.MODEL_SHA256
    )
    private fun seed(file: File, m: SubtitleManifest, text: String = vtt, etag: String = "\"revision-1\"") {
        file.writeText(buildJsonObject {
            put("manifest", json.encodeToJsonElement(m)); put("vtt", text); put("etag", etag)
        }.toString())
    }
    private fun capture(video: File, etag: String? = "\"revision-1\"") = mockk<PlaybackVideoCapture> {
        every { this@mockk.etag } returns etag
        every { lastModified } returns null
        coEvery { awaitRevision() } just Runs
        coEvery { awaitComplete(any()) } returns video
        every { close() } just Runs
    }

    @Test fun senseVoiceRejectsVoskOrObsoletePipelineEvenWithMatchingStrongEtag() = runBlocking {
        val video = media()
        val target = cacheFile()
        val borrowed = capture(video)
        every { anyConstructed<AtomicFile>().openRead() } answers { target.inputStream() }
        coEvery { borrowed.awaitComplete(any()) } throws IOException("Full media required for selected backend")
        val wrongBackend = manifest(video)
        val wrongPipeline = wrongBackend.copy(asrModel = AsrBackend.SENSEVOICE.model,
            asrModelSha256 = AsrBackend.SENSEVOICE.sha256,
            asrPipelineVersion = "android-pcm16-intmono-sv-native-lowpass-window40-v1")
        for (cached in listOf(wrongBackend, wrongPipeline)) {
            seed(target, cached)
            val original = target.readBytes()
            val error = assertThrows(IOException::class.java) {
                runBlocking { repository.resolve(program, date, url, borrowed, backend = AsrBackend.SENSEVOICE) }
            }
            assertEquals("Full media required for selected backend", error.message,
                "Matching ETag must not deliver another backend/pipeline's cached transcript")
            assertArrayEquals(original, target.readBytes(), "Rejected old evidence is preserved")
        }
    }

    @Test fun pipelineChangeRegeneratesOnlyDerivedResultAndRetainsTheBorrowedMedia() = runBlocking {
        val video = media()
        val original = video.readBytes()
        val target = cacheFile()
        seed(target, manifest(video).copy(asrPipelineVersion = null, asrModelSha256 = null))
        val unrelated = cacheFile("2026-09-28", "$url?unrelated")
        seed(unrelated, manifest(video, "2026-09-28", "$url?unrelated"))
        val unrelatedBytes = unrelated.readBytes()
        val borrowed = capture(video)
        val result = repository.resolve(program, date, url, borrowed)
        assertTrue(result is SubtitleResolutionResult.AVAILABLE && !result.fromCache)
        assertArrayEquals(original, video.readBytes())
        assertArrayEquals(unrelatedBytes, unrelated.readBytes())
        coVerify(exactly = 1) { anyConstructed<AndroidVideoTranscriber>().transcribe(video, any(), any()) }
        verify(exactly = 0) { borrowed.close() }
        assertTrue(target.readText().contains(AndroidVideoTranscriber.PIPELINE_VERSION))
    }

    @Test fun waitingEpisodeDoesNotBlockOtherCachedEpisode() = runBlocking {
        val video = media()
        seed(cacheFile(), manifest(video))
        val aFile = cacheFile("2026-09-28", "$url?old")
        val a = capture(video)
        val waiting = CompletableDeferred<Unit>()
        coEvery { a.awaitComplete(any()) } coAnswers { waiting.complete(Unit); awaitCancellation() }
        val job = launch { repository.resolve(program, "2026-09-28", "$url?old", a) }
        try {
            withTimeout(5000) { waiting.await() }
            val b = capture(video)
            val result = withTimeout(5000) { repository.resolve(program, date, url, b) }
            assertTrue(result is SubtitleResolutionResult.AVAILABLE && result.fromCache)
            coVerify(exactly = 0) { anyConstructed<AndroidVideoTranscriber>().transcribe(any(), any(), any()) }
        } finally { job.cancelAndJoin() }
        assertFalse(aFile.exists())
    }

    @Test fun duplicateRequestsRecheckCacheBeforeNativeWork() = runBlocking {
        cacheFile()
        val video = media()
        val nativeStarted = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val secondReady = CompletableDeferred<Unit>()
        coEvery { anyConstructed<AndroidVideoTranscriber>().transcribe(any(), any(), any()) } coAnswers {
            nativeStarted.complete(Unit); finish.await(); AndroidVideoTranscriber.Transcript(5000, cues)
        }
        val first = async { repository.resolve(program, date, url, capture(video)) }
        val second = async {
            nativeStarted.await()
            val c = capture(video)
            coEvery { c.awaitComplete(any()) } coAnswers { secondReady.complete(Unit); video }
            repository.resolve(program, date, url, c)
        }
        try {
            withTimeout(5000) { secondReady.await() }
            finish.complete(Unit)
            assertTrue(withTimeout(5000) { first.await() } is SubtitleResolutionResult.AVAILABLE)
            val result = withTimeout(5000) { second.await() }
            assertTrue(result is SubtitleResolutionResult.AVAILABLE && result.fromCache)
            coVerify(exactly = 1) { anyConstructed<AndroidVideoTranscriber>().transcribe(any(), any(), any()) }
        } finally { first.cancelAndJoin(); second.cancelAndJoin() }
    }

    @Test fun cancellationAfterLateNativeReturnDoesNotWriteOrDeliverOldResult() = runBlocking {
        val file = cacheFile()
        val video = media()
        val nativeStarted = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var delivered = false
        coEvery { anyConstructed<AndroidVideoTranscriber>().transcribe(any(), any(), any()) } coAnswers {
            withContext(NonCancellable) { nativeStarted.complete(Unit); finish.await() }
            AndroidVideoTranscriber.Transcript(5000, cues)
        }
        val job = launch { repository.resolve(program, date, url, capture(video)); delivered = true }
        withTimeout(5000) { nativeStarted.await() }
        job.cancel(); finish.complete(Unit)
        withTimeout(5000) { job.join() }
        assertFalse(file.exists()); assertFalse(delivered)
        assertTrue(SubtitleMediaIdentityGate.verifyPreMountIdentity(manifest(video), program, "2026-09-30", url)
            is SubtitleMediaIdentityResult.Mismatch)
    }

    @Test fun wrongDateUrlAndDamagedVttCannotUseStrongEtagFastPath() = runBlocking {
        val video = media()
        val file = cacheFile()
        for (bad in listOf(manifest(video).copy(episodeDate = "2026-09-28"), manifest(video).copy(sourceVideoUrl = "$url?other"))) {
            seed(file, bad)
            val result = repository.resolve(program, date, url, capture(video))
            assertTrue(result is SubtitleResolutionResult.AVAILABLE && !result.fromCache)
            assertEquals(date, (result as SubtitleResolutionResult.AVAILABLE).manifest!!.episodeDate)
            assertEquals(url, result.manifest!!.sourceVideoUrl)
        }
        seed(file, manifest(video), "corrupt vtt")
        val result = repository.resolve(program, date, url, capture(video))
        assertTrue(result is SubtitleResolutionResult.AVAILABLE && !result.fromCache)
        coVerify(exactly = 3) { anyConstructed<AndroidVideoTranscriber>().transcribe(any(), any(), any()) }
    }

    @Test fun changedOrWeakRevisionRequiresMediaHashAndChangedBytesRequireNewAsr() = runBlocking {
        val old = media("old media"); val changed = media("new media")
        val file = cacheFile()
        seed(file, manifest(old))
        val weak = capture(old, "W/\"revision-1\"")
        val same = repository.resolve(program, date, url, weak)
        assertTrue(same is SubtitleResolutionResult.AVAILABLE && same.fromCache)
        coVerify(exactly = 1) { weak.awaitComplete(any()) }
        val newer = repository.resolve(program, date, url, capture(changed, "\"revision-2\""))
        assertTrue(newer is SubtitleResolutionResult.AVAILABLE && !newer.fromCache)
        assertEquals(hash(changed), (newer as SubtitleResolutionResult.AVAILABLE).manifest!!.sourceVideoSha256)
        coVerify(exactly = 1) { anyConstructed<AndroidVideoTranscriber>().transcribe(any(), any(), any()) }
    }

    private fun summary(m: SubtitleManifest) = EpisodeSummary(m.program, m.episodeDate, m.sourceVideoUrl,
        m.sourceVideoSha256, m.vttSha256, EpisodeSummaryGate.LOCAL_GENERATIVE_ASR_METHOD,
        listOf(EpisodeSummaryItem(1000, 3000, "国庆冷空气频繁，注意保暖", listOf(0))))

    @Test fun phoneNotesReplaceLegacyGeneratedSummaryAndReuseSameMediaArtifact() = runBlocking {
        val video = media(); val m = manifest(video)
        seed(cacheFile(), m.copy(episodeSummary = summary(m)))
        val result = repository.generateSummary(m, cues) {}
        assertTrue(result != null)
        assertEquals(EpisodeSummaryGate.LOCAL_STRUCTURED_ASR_METHOD, result!!.summaryMethod)
        assertTrue(result.items.isEmpty()) // V4 does not invent regional weather fields from this generic cue.
        assertEquals(result, repository.generateSummary(m, cues) {})
    }

    @Test fun summaryCannotOverwriteChangedVideoOrVttArtifact() = runBlocking {
        val video = media(); val m = manifest(video); val file = cacheFile()
        seed(file, m)
        var rejected = false
        try { repository.generateSummary(m, cues) {
            seed(file, m.copy(vttSha256 = "a".repeat(64)))
        } } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        assertEquals(JsonNull, json.parseToJsonElement(file.readText()).jsonObject["manifest"]!!.jsonObject["episodeSummary"])
    }

    @Test fun failedAttemptRetainsCompleteMediaForSameSessionRetry() = runBlocking {
        val video = media("retained media after controlled ASR failure")
        val c = capture(video, null)
        every { c.close() } answers { video.delete(); Unit }
        val file = cacheFile()
        var failFirstTranscription = true
        coEvery { anyConstructed<AndroidVideoTranscriber>().transcribe(any(), any(), any()) } coAnswers {
            if (failFirstTranscription) {
                failFirstTranscription = false
                throw IOException("controlled recognition failure")
            }
            AndroidVideoTranscriber.Transcript(5000, cues)
        }
        var failed = false
        try { repository.resolve(program, date, url, c) }
        catch (error: IOException) { failed = error.message == "controlled recognition failure" }
        assertTrue(failed)
        assertTrue(video.exists(), "A recognition failure must retain player-session bytes")
        assertFalse(file.exists(), "Failed transcription must not leave a usable artifact")
        val retried = repository.resolve(program, date, url, c)
        assertTrue(retried is SubtitleResolutionResult.AVAILABLE && !retried.fromCache)
        assertEquals("国庆冷空气频繁，早晚注意保暖",
            (retried as SubtitleResolutionResult.AVAILABLE).evidence.cues.single().normalizedText)
        assertTrue(video.exists())
        assertTrue(file.exists())
        c.close()
        assertFalse(video.exists(), "The owning session can still release its media")
        assertTrue(file.exists(), "Session release must leave the subtitle artifact available")
    }

    @Test fun cancellingAnAttemptRetainsMediaUntilPlayerSessionRelease() = runBlocking {
        val video = media("partially captured session media")
        val c = capture(video, null)
        every { c.close() } answers { video.delete(); Unit }
        val waiting = CompletableDeferred<Unit>()
        coEvery { c.awaitComplete(any()) } coAnswers { waiting.complete(Unit); awaitCancellation() }
        val job = launch { repository.resolve(program, date, url, c) }
        withTimeout(5000) { waiting.await() }
        job.cancelAndJoin()
        assertTrue(video.exists(), "Cancelling recognition must not release the player's capture")
        assertFalse(cacheFile().exists())
        coEvery { c.awaitComplete(any()) } returns video
        val retried = repository.resolve(program, date, url, c)
        assertTrue(retried is SubtitleResolutionResult.AVAILABLE && !retried.fromCache)
        assertEquals("国庆冷空气频繁，早晚注意保暖",
            (retried as SubtitleResolutionResult.AVAILABLE).evidence.cues.single().normalizedText)
        c.close()
        assertFalse(video.exists())
    }

    /** Exercises production close only; no Android constructor, playback, decoding or ASR is executed. */
    @Test fun ownerReleaseWaitsForBorrowerBeforeDeletingItsMedia() {
        val video = media("this session's temporary media")
        val otherSession = media("another session's temporary media")
        val artifact = cacheFile()
        seed(artifact, manifest(video))
        val writer = RandomAccessFile(video, "rw")
        val owned = ObjenesisStd().newInstance(PlaybackVideoCapture::class.java)
        PlaybackVideoCapture::class.java.getDeclaredField("file").apply {
            isAccessible = true
            set(owned, video)
        }
        PlaybackVideoCapture::class.java.getDeclaredField("output").apply {
            isAccessible = true
            set(owned, writer)
        }
        PlaybackVideoCapture::class.java.getDeclaredField("retainCount").apply {
            isAccessible = true
            setInt(owned, 1)
        }
        PlaybackVideoCapture::class.java.getDeclaredField("deleteWhenReleased").apply {
            isAccessible = true
            setBoolean(owned, true)
        }
        val borrower = owned.retain()
        try {
            owned.close()
            assertTrue(video.exists(), "A preparation borrower must survive player owner release")
            writer.write(1) // The retained writer is still usable after player owner release.
            borrower.close()
            assertFalse(video.exists(), "Release must delete this session's media")
            var writerClosed = false
            try { writer.write(1) } catch (_: IOException) { writerClosed = true }
            assertTrue(writerClosed, "Release must close the actual file handle")
            assertEquals("another session's temporary media", otherSession.readText())
            assertEquals(vtt, json.parseToJsonElement(artifact.readText()).jsonObject["vtt"]!!.jsonPrimitive.content)
            owned.close() // Releasing the same session twice is safe.
        } finally {
            owned.close()
            borrower.close()
            writer.close()
        }
    }

    @Test fun v5InvalidatesOnlyOldSummaryAndKeepsExactTranscriptMediaAndHistory() = runBlocking {
        val video = media(); val m = manifest(video); val target = cacheFile()
        val old = summary(m).copy(summaryMethod = "LOCAL_STRUCTURED_VERBATIM_FROM_AI_ASR_V4")
        seed(target, m.copy(episodeSummary = old))
        val original = target.readBytes(); val videoBytes = video.readBytes()
        val result = repository.resolve(program, date, url, capture(video)) as SubtitleResolutionResult.AVAILABLE
        assertTrue(result.fromCache, "A summary version change must not invalidate ASR")
        val upgraded = repository.generateSummary(result.manifest!!, result.evidence.cues) {}!!
        assertEquals(EpisodeSummaryGate.LOCAL_STRUCTURED_ASR_METHOD, upgraded.summaryMethod)
        val newManifest = json.parseToJsonElement(target.readText()).jsonObject["manifest"]!!.jsonObject
        val oldManifest = json.parseToJsonElement(original.toString(Charsets.UTF_8)).jsonObject["manifest"]!!.jsonObject
        assertEquals(oldManifest - "episodeSummary", newManifest - "episodeSummary")
        assertArrayEquals(videoBytes, video.readBytes())
        val history = File(root, "files/local-summary-history").listFiles().orEmpty()
        assertEquals(1, history.size)
        assertArrayEquals(original, history.single().readBytes())
        assertEquals(upgraded, repository.generateSummary(result.manifest!!, result.evidence.cues) {})
        assertEquals(1, File(root, "files/local-summary-history").listFiles()!!.size)
        coVerify(exactly = 0) { anyConstructed<AndroidVideoTranscriber>().transcribe(any(), any(), any()) }
    }

}
