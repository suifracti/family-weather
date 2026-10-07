package org.breezyweather.domain.subtitle.local

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import org.breezyweather.domain.subtitle.model.EpisodeSummary
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.breezyweather.domain.subtitle.model.SubtitleManifest
import org.breezyweather.domain.subtitle.resolver.SubtitleArtifactResolver
import org.breezyweather.domain.subtitle.resolver.SubtitleResolutionResult
import org.breezyweather.domain.subtitle.source.SubtitleArtifactSource
import org.breezyweather.domain.subtitle.source.SubtitleSourceResult
import org.breezyweather.domain.subtitle.util.Sha256Util
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** No computer, published subtitle index, cloud ASR, or periodic backend is needed. */
class LocalEpisodeArtifacts(private val context: Context) {
    @Serializable
    private data class Artifact(
        val manifest: SubtitleManifest,
        val vtt: String,
        val etag: String? = null,
        val lastModified: String? = null
    )

    companion object {
        const val MODEL = "vosk-model-small-cn-0.22"
        const val MODEL_SHA256 = "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba"
        private const val TAG = "LocalEpisodeASR"
        // One native recognizer at a time; cancellation releases both decoder and recognizer.
        private val processing = Mutex()
        private val artifacts = Mutex()
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    }

    /** The player session owns capture; a failed or cancelled recognition attempt only borrows it. */
    suspend fun resolve(
        program: String, episodeDate: String, videoUrl: String, capture: PlaybackVideoCapture,
        sourceIdentity: String? = null, backend: AsrBackend = AsrBackend.VOSK,
        commitGuard: PreparationCommit? = null,
        allowRecognition: Boolean = true,
        progress: suspend (String) -> Unit = {}
    ): SubtitleResolutionResult = withContext(Dispatchers.IO) {
        require(program in setOf("EVENING_WEATHER", "CHINA_WEATHER_LIANBO") &&
            Regex("\\d{4}-\\d{2}-\\d{2}").matches(episodeDate))
        if (program == "CHINA_WEATHER_LIANBO") {
            require(sourceIdentity?.matches(Regex("weather_com_cn\\|CHINA_WEATHER_LIANBO\\|3M\\|\\d+")) == true)
        }
        requireOfficialVideo(videoUrl)
        val cache = cacheFile(program, episodeDate, videoUrl, sourceIdentity, backend)
        progress("检查本地字幕缓存")
        val usable = usableArtifact(cache, program, episodeDate, videoUrl, sourceIdentity, backend)
        if (usable == null && !allowRecognition) return@withContext SubtitleResolutionResult.NOT_FOUND("Explicit preparation required for this backend/pipeline")
        progress("等待播放器取得本期媒体信息")
        capture.awaitRevision()
        val revisionEtag = capture.etag
        val revisionModified = capture.lastModified
        // Waiting for a paused player must not reserve the single native-model slot.
        if (usable != null && revisionEtag != null && !revisionEtag.startsWith("W/") &&
            revisionEtag == usable.etag) {
            Log.i(TAG, "LOCAL_ASR_CACHE_HIT date=$episodeDate revision=playback-etag")
            return@withContext validate(usable, true)
        }
        val video = capture.awaitComplete(progress)
        val videoHash = fileHash(video)
        if (usable?.manifest?.sourceVideoSha256 == videoHash) {
            Log.i(TAG, "LOCAL_ASR_CACHE_HIT date=$episodeDate videoSha256=$videoHash")
            return@withContext validate(usable, true)
        }
        if (!allowRecognition) return@withContext SubtitleResolutionResult.NOT_FOUND("Cached media identity changed; prepare explicitly")
        processing.withLock {
            coroutineContext.ensureActive()
            // Another request may have finished this exact media while we waited.
            val latest = usableArtifact(cache, program, episodeDate, videoUrl, sourceIdentity, backend)
            if (latest?.manifest?.sourceVideoSha256 == videoHash) {
                Log.i(TAG, "LOCAL_ASR_CACHE_HIT date=$episodeDate revision=after-processing-wait")
                return@withLock validate(latest, true)
            }
            Log.i(TAG, "LOCAL_ASR_START date=$episodeDate videoSha256=$videoHash backend=${backend.id}")
            progress("准备本机语音识别模型")
            val transcript = when (backend) {
                AsrBackend.VOSK -> AndroidVideoTranscriber().transcribe(video, installModel(), progress)
                AsrBackend.SENSEVOICE -> SenseVoiceVideoTranscriber(context).transcribe(video, progress)
            }
            coroutineContext.ensureActive()
            if (transcript.cues.isEmpty()) {
                Log.i(TAG, "LOCAL_ASR_NO_CONTENT date=$episodeDate")
                return@withLock SubtitleResolutionResult.NOT_FOUND("This episode has no recognized speech")
            }
            val vtt = buildString {
                append("WEBVTT\n\n")
                transcript.cues.forEach { cue ->
                    append("${timestamp(cue.startMs)} --> ${timestamp(cue.endMs)}\n")
                    append(cue.normalizedText.replace("-->", "→")); append("\n\n")
                }
            }
            val manifest = SubtitleManifest(
                "1", program, episodeDate, videoUrl, videoHash, transcript.durationMs,
                "AI_ASR_GENERATED", "AUTOMATED_ASR_EXTRACTED", backend.model, Instant.now().toString(),
                "subtitle.vtt", Sha256Util.calculateSha256(vtt), transcript.cues.size,
                sourceIdentity = sourceIdentity,
                asrPipelineVersion = transcript.pipelineVersion, asrModelSha256 = backend.sha256,
                asrWords = transcript.words, asrRawResults = transcript.rawResults,
                decodedPcmSha256 = transcript.decodedPcmSha256, asrInputRate = transcript.inputRate,
                asrDecoder = transcript.decoderName, asrElapsedMs = transcript.elapsedMs,
                asrRuntime = if (backend == AsrBackend.SENSEVOICE) "sherpa-onnx-1.13.8" else "vosk-android-0.3.75",
                asrSelection = if (backend == AsrBackend.SENSEVOICE) "candidate-default" else "explicit-or-legacy-vosk"
            )
            progress("本机字幕转写完成")
            val artifact = Artifact(manifest.copy(episodeSummary = null), vtt, revisionEtag, revisionModified)
            val result = validate(artifact)
            if (result is SubtitleResolutionResult.AVAILABLE) {
                writeArtifact(cache, artifact, commitGuard)
                Log.i(TAG, "LOCAL_ASR_COMPLETE date=$episodeDate cues=${transcript.cues.size} " +
                    "summaryItems=0 videoSha256=$videoHash")
            }
            result
        }
    }

    /** Extracts conservative source fields on the phone, with no language-model dependency. */
    suspend fun generateSummary(
        manifest: SubtitleManifest,
        cues: List<org.breezyweather.domain.multisource.model.TimestampedCue>,
        commitGuard: PreparationCommit? = null,
        progress: suspend (String) -> Unit
    ): EpisodeSummary? = withContext(Dispatchers.IO) {
        processing.withLock {
            val backend = AsrBackend.fromModel(manifest.asrModel)
            val cache = cacheFile(manifest.program, manifest.episodeDate, manifest.sourceVideoUrl, manifest.sourceIdentity, backend)
            val existing = usableArtifact(cache, manifest.program, manifest.episodeDate, manifest.sourceVideoUrl, manifest.sourceIdentity, backend)
            check(existing != null && sameMedia(existing.manifest, manifest)) { "Summary cache identity changed" }
            existing.manifest.episodeSummary?.let { summary ->
                if (summary.summaryMethod == org.breezyweather.domain.subtitle.gate.EpisodeSummaryGate.LOCAL_STRUCTURED_ASR_METHOD &&
                    org.breezyweather.domain.subtitle.gate.EpisodeSummaryGate.verify(summary, manifest,
                        manifest.program, manifest.episodeDate, manifest.sourceVideoUrl, cues) is
                    org.breezyweather.domain.subtitle.gate.EpisodeSummaryResult.Accepted) return@withLock summary
            }
            coroutineContext.ensureActive()
            progress("正在整理本期节目要点")
            val summary = StructuredWeatherSummary.generate(manifest, cues) ?: return@withLock null
            val accepted = org.breezyweather.domain.subtitle.gate.EpisodeSummaryGate.verify(summary, manifest,
                manifest.program, manifest.episodeDate, manifest.sourceVideoUrl, cues)
            check(accepted is org.breezyweather.domain.subtitle.gate.EpisodeSummaryResult.Accepted) { "Generated summary failed identity/evidence checks" }
            // Do not overwrite a changed media revision or persist after cancellation.
            artifacts.withLock {
                coroutineContext.ensureActive()
                val snapshot = readSnapshotUnlocked(cache)
                val artifact = snapshot?.let(::decodeArtifact)
                check(artifact != null && sameMedia(artifact.manifest, manifest)) { "Summary cache identity changed" }
                val updated = artifact.copy(manifest = artifact.manifest.copy(episodeSummary = summary))
                fun persist() {
                    if (artifact.manifest.episodeSummary != null &&
                        artifact.manifest.episodeSummary.summaryMethod != summary.summaryMethod) {
                        preserveSummaryHistory(cache, snapshot!!)
                    }
                    writeArtifactUnlocked(cache, updated)
                }
                if (commitGuard != null) commitGuard.protect { persist() } else persist()
            }
            summary
        }
    }

    /** Preserve the exact old derived artifact before a summary-only upgrade; never used as a cache hit. */
    private fun preserveSummaryHistory(cache: AtomicFile, snapshot: String) {
        val root = File(context.filesDir, "local-summary-history").apply { mkdirs() }
        val hash = Sha256Util.calculateSha256(snapshot)
        val target = File(root, "${cache.baseFile.nameWithoutExtension}-$hash.json")
        if (target.isFile) {
            check(Sha256Util.calculateSha256(target.readText()) == hash) { "Summary history integrity mismatch" }
            return
        }
        val temporary = File.createTempFile("summary-", ".new", root)
        try {
            temporary.outputStream().use { output -> output.write(snapshot.toByteArray(Charsets.UTF_8)); output.fd.sync() }
            if (!temporary.renameTo(target)) throw IOException("Could not preserve old summary")
        } finally { temporary.delete() }
    }

    private fun cacheFile(program: String, date: String, url: String, sourceIdentity: String? = null,
                          backend: AsrBackend = AsrBackend.VOSK): AtomicFile {
        // Retain the ASR namespace (structured-v3 suffix is historical); summaryMethod versions
        // are checked independently so summary-only repairs reuse the exact transcript without recognition.
        val key = Sha256Util.calculateSha256(if (backend == AsrBackend.SENSEVOICE)
            "$program|$date|$url|$sourceIdentity|${backend.model}|${backend.sha256}|sherpa-onnx-1.13.8|${backend.pipeline}|structured-v3"
        else if (sourceIdentity == null)
            "$program|$date|$url|$MODEL|qwen-0.5b-q4-v1"
        else "$program|$date|$url|$MODEL|$sourceIdentity|phone-notes-v2")
        val root = File(context.cacheDir, "local-episode-asr").apply { mkdirs() }
        return AtomicFile(File(root, "$key.json"))
    }

    internal fun artifactCacheFile(request: EpisodePreparationRequest): File =
        cacheFile(request.program, request.episodeDate, request.videoUrl, request.sourceIdentity,
            AsrBackend.fromId(request.asrBackend)).baseFile

    private suspend fun usableArtifact(cache: AtomicFile, program: String, date: String, url: String,
                                       sourceIdentity: String? = null, backend: AsrBackend = AsrBackend.VOSK): Artifact? {
        val snapshot = artifacts.withLock { readSnapshotUnlocked(cache) } ?: return null
        val cached = decodeArtifact(snapshot) ?: return null
        val usable = cached.takeIf {
            it.manifest.program == program && it.manifest.episodeDate == date &&
                it.manifest.sourceVideoUrl == url && it.manifest.sourceIdentity == sourceIdentity && it.manifest.asrModel == backend.model &&
                it.manifest.asrPipelineVersion == backend.pipeline &&
                it.manifest.asrModelSha256 == backend.sha256 &&
                it.manifest.sourceVideoSha256.matches(Regex("[0-9a-f]{64}")) &&
                Sha256Util.calculateSha256(it.vtt) == it.manifest.vttSha256
        }?.takeIf { validate(it) is SubtitleResolutionResult.AVAILABLE }
        if (usable == null) Log.w(TAG, "LOCAL_ASR_CACHE_REJECTED identity/integrity")
        return usable
    }

    private fun readSnapshotUnlocked(cache: AtomicFile): String? = try {
        cache.openRead().bufferedReader().use { it.readText() }
    } catch (_: Exception) { null }

    private fun decodeArtifact(snapshot: String): Artifact? = try {
        json.decodeFromString<Artifact>(snapshot)
    } catch (_: Exception) { null }

    private fun readArtifactUnlocked(cache: AtomicFile): Artifact? =
        readSnapshotUnlocked(cache)?.let(::decodeArtifact)

    private fun sameMedia(a: SubtitleManifest, b: SubtitleManifest) =
        a.program == b.program && a.episodeDate == b.episodeDate && a.sourceVideoUrl == b.sourceVideoUrl &&
            a.sourceVideoSha256 == b.sourceVideoSha256 && a.vttSha256 == b.vttSha256 && a.sourceIdentity == b.sourceIdentity &&
            a.asrModel == b.asrModel && a.asrPipelineVersion == b.asrPipelineVersion && a.asrModelSha256 == b.asrModelSha256

    private suspend fun writeArtifact(cache: AtomicFile, artifact: Artifact, commitGuard: PreparationCommit? = null) = artifacts.withLock {
        coroutineContext.ensureActive()
        if (commitGuard != null) commitGuard.protect { writeArtifactUnlocked(cache, artifact) }
        else writeArtifactUnlocked(cache, artifact)
    }

    private fun writeArtifactUnlocked(cache: AtomicFile, artifact: Artifact) {
        val output = cache.startWrite()
        try { output.write(json.encodeToString(artifact).toByteArray(Charsets.UTF_8)); cache.finishWrite(output) }
        catch (error: Exception) { cache.failWrite(output); throw error }
    }

    private suspend fun validate(artifact: Artifact, fromCache: Boolean = false): SubtitleResolutionResult {
        val source = object : SubtitleArtifactSource {
            override suspend fun fetchManifest(program: String, episodeDate: String) = SubtitleSourceResult.Success(artifact.manifest)
            override suspend fun fetchVtt(program: String, episodeDate: String, vttFileOrUrl: String) = SubtitleSourceResult.Success(artifact.vtt)
        }
        return when (val result = SubtitleArtifactResolver(source).resolve(artifact.manifest.program, artifact.manifest.episodeDate)) {
            is SubtitleResolutionResult.AVAILABLE -> result.copy(fromCache = fromCache,
                evidence = result.evidence.copy(cues = result.evidence.cues.map { cue ->
                    val confidence = artifact.manifest.asrWords.filter {
                        it.startMs < cue.endMs && it.endMs > cue.startMs
                    }.mapNotNull { it.confidence }.minOrNull()?.toFloat()
                    cue.copy(confidence = confidence, model = artifact.manifest.asrModel)
                }))
            else -> result
        }
    }

    /** Debug-only silence fixture uses the real Android decoder and native recognizer. */
    suspend fun verifyNoSpeechFixture(): SubtitleResolutionResult = withContext(Dispatchers.IO) {
        check(org.breezyweather.BuildConfig.DEBUG)
        processing.withLock {
            val fixture = File(context.cacheDir, "local-asr-silence-fixture.mp4")
            try {
                context.assets.open("asr-test/silence.mp4").use { input -> fixture.outputStream().use(input::copyTo) }
                val transcript = AndroidVideoTranscriber().transcribe(fixture, installModel()) {}
                check(transcript.cues.isEmpty()) { "Silent fixture unexpectedly produced speech" }
                Log.i(TAG, "LOCAL_ASR_REAL_SILENCE_NO_CONTENT duration=${transcript.durationMs}")
                SubtitleResolutionResult.NOT_FOUND("No speech in real decoded silent audio")
            } finally { fixture.delete() }
        }
    }

    private suspend fun installModel(): File {
        val parent = File(context.filesDir, "local-asr-models").apply { mkdirs() }
        val model = File(parent, MODEL)
        val marker = File(model, "installed.sha256")
        if (marker.isFile && marker.readText() == MODEL_SHA256 && File(model, "am/final.mdl").isFile) return model
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open("asr/$MODEL.zip").use { input ->
            val buffer = ByteArray(65536)
            while (true) { coroutineContext.ensureActive(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        check(hex(digest.digest()) == MODEL_SHA256) { "Bundled model checksum mismatch" }
        context.assets.open("asr/$MODEL.zip").use { input -> ZipInputStream(input).use { zip ->
            var extracted = 0L
            while (true) {
                coroutineContext.ensureActive()
                val entry = zip.nextEntry ?: break
                val target = File(parent, entry.name)
                require(target.canonicalPath.startsWith(model.canonicalPath + File.separator) || target.canonicalPath == model.canonicalPath)
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile!!.mkdirs()
                    target.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            coroutineContext.ensureActive(); val count = zip.read(buffer); if (count < 0) break
                            extracted += count; require(extracted <= 200L * 1024 * 1024)
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        } }
        check(File(model, "am/final.mdl").isFile)
        marker.writeText(MODEL_SHA256)
        return model
    }

    private fun requireOfficialVideo(url: String) {
        val uri = URI(url); val host = uri.host?.lowercase(Locale.ROOT) ?: ""
        require(uri.scheme in listOf("https", "http") && uri.userInfo == null &&
            (host == "weathertv.cn" || host.endsWith(".weathertv.cn") || host == "weather.com.cn" ||
                host.endsWith(".weather.com.cn") || host.endsWith(".cctv.com") || host.endsWith(".cntv.cn"))) {
            "Only resolved official episode media can be transcribed"
        }
    }

    private suspend fun fileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { coroutineContext.ensureActive(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun timestamp(ms: Long) = String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
}
