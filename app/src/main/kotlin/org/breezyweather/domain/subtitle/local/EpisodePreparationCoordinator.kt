package org.breezyweather.domain.subtitle.local

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import org.breezyweather.domain.subtitle.resolver.SubtitleResolutionResult
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** One phone task per concrete episode/local preparation day; UI observers never own its job. */
class EpisodePreparationCoordinator internal constructor(
    private val context: Context,
    private val cache: EpisodePreparationCache = EpisodePreparationCache(
        File(context.cacheDir, "episode-preparation"), File(context.cacheDir, "local-episode-asr")),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val repository: LocalEpisodeArtifacts = LocalEpisodeArtifacts(context),
    private val newCapture: (EpisodePreparationRequest) -> PlaybackVideoCapture = {
        PlaybackVideoCapture(context, it.videoUrl)
    },
    private val restoreCapture: (EpisodePreparationRequest, File, String?, String?) -> PlaybackVideoCapture = { request, file, etag, modified ->
        PlaybackVideoCapture.completed(context, request.videoUrl, file, etag, modified)
    }
) {
    companion object {
        @Volatile private var instance: EpisodePreparationCoordinator? = null
        fun get(context: Context): EpisodePreparationCoordinator = instance ?: synchronized(this) {
            instance ?: EpisodePreparationCoordinator(context.applicationContext).also { instance = it }
        }
    }

    enum class Stage { DOWNLOADING, TRANSCRIBING, NOTES }
    enum class FailureKind { NO_SPACE, LIMIT, TIMEOUT, INTERRUPTED, NETWORK, TRANSCRIPTION, NO_CONTENT }
    sealed interface State {
        data object Idle : State
        data class Preparing(val stage: Stage, val message: String, val preparedOn: String) : State
        data class Ready(val mediaFile: File, val result: SubtitleResolutionResult.AVAILABLE, val preparedOn: String) : State {
            val notesAvailable: Boolean get() = result.manifest?.episodeSummary?.items?.any { !it.replayOnly } == true
        }
        data class Failed(val reason: String, val kind: FailureKind, val preparedOn: String) : State
        data class Cancelled(val preparedOn: String) : State
        data class Cancelling(val preparedOn: String) : State
    }

    internal class Entry(
        val request: EpisodePreparationRequest,
        @Volatile var preparedOn: String,
        val directory: File,
        val capture: PlaybackVideoCapture,
        val ownsCapture: Boolean,
        val restoreOnly: Boolean,
        @Volatile var record: EpisodePreparationCache.Record,
        val state: MutableStateFlow<State> = MutableStateFlow(State.Idle),
        val media: CompletableDeferred<File> = CompletableDeferred(),
        val result: CompletableDeferred<SubtitleResolutionResult.AVAILABLE> = CompletableDeferred(),
        val downloadRequests: Channel<Unit> = Channel(Channel.CONFLATED)
    ) {
        @Volatile var homeOwned = false
        var players = 0
        val cacheDay = record.directoryDay
        var job: Job? = null
        @Volatile var downloadJob: Job? = null
        val commit = PreparationCommit()
    }

    inner class Lease internal constructor(private val entry: Entry, private val player: Boolean) : Closeable {
        val state: StateFlow<State> get() = entry.state
        val directory: File get() = entry.directory
        val preparedOn: String get() = entry.preparedOn
        private val files = cache.retain(entry.request, entry.cacheDay)
        private var released = false
        suspend fun awaitMedia(): File = entry.media.await()
        suspend fun awaitResult(): SubtitleResolutionResult.AVAILABLE = entry.result.await()
        override fun close() {
            synchronized(this@EpisodePreparationCoordinator) {
                if (released) return
                released = true
                files.close()
                if (player) {
                    entry.players--
                    if (entry.players == 0 && !entry.homeOwned && entry.job?.isActive == true) {
                        entry.commit.cancel(); entry.job?.cancel()
                    }
                }
                cleanupExpired()
            }
        }
    }

    private val entries = mutableMapOf<String, Entry>()
    private val choices = mutableMapOf<String, String>()
    private fun effective(request: EpisodePreparationRequest) = choices[request.key]?.let { request.copy(asrBackend = it) } ?: request
    private fun mapKey(request: EpisodePreparationRequest, day: String = cache.currentDay()) = "$day-${request.key}-${request.asrBackend}"
    private fun existing(request: EpisodePreparationRequest): Entry? = entries[mapKey(request)] ?: entries.values.firstOrNull {
        it.request == request && (it.preparedOn == cache.currentDay() || it.job?.isActive == true || cache.inUse(request, it.cacheDay))
    }
    private fun lease(entry: Entry, player: Boolean): Lease {
        if (player) entry.players++
        return Lease(entry, player)
    }

    /** Explicit home action is the only permission to start silent network completion. */
    @Synchronized fun startHome(input: EpisodePreparationRequest, retry: Boolean = false): Lease {
        val request = effective(input)
        cleanupExpired()
        val previous = existing(request)
        // A cancelled native call still owns its capture/writer and the global model slot.
        if (previous?.state?.value is State.Cancelling) return lease(previous, player = false)
        val entry = if (previous == null || retry && previous.state.value.let { it is State.Failed || it is State.Cancelled }) {
            val record = cache.completed(request)
            val reusePlayerCapture = record == null && previous != null && previous.players > 0
            val capture = if (reusePlayerCapture) previous!!.capture else if (record != null) restoreCapture(request,
                File(cache.directory(request, record.directoryDay), "media.mp4"), null, record.lastModified)
            else newCapture(request)
            create(request, capture, ownsCapture = !reusePlayerCapture)
        } else previous
        if (entry.preparedOn != cache.currentDay()) {
            entry.record = cache.renew(entry.record, cache.currentDay())
            entry.preparedOn = entry.record.preparedOn
            entry.state.value = when (val state = entry.state.value) {
                is State.Preparing -> state.copy(preparedOn = entry.preparedOn)
                is State.Ready -> state.copy(preparedOn = entry.preparedOn)
                else -> state
            }
        }
        entry.homeOwned = true
        entry.downloadRequests.trySend(Unit)
        return lease(entry, player = false)
    }

    /** A prepared/home task may be borrowed without starting another network request or ASR. */
    @Synchronized fun acquireExisting(input: EpisodePreparationRequest): Lease? {
        val request = effective(input)
        cleanupExpired()
        val entry = existingOrRestore(request) ?: return null
        return lease(entry, player = true)
    }

    /** An observing card does not own playback or cancel offline cache validation when recycled. */
    @Synchronized fun observeExisting(input: EpisodePreparationRequest): Lease? {
        val request = effective(input)
        cleanupExpired()
        val entry = existingOrRestore(request) ?: return null
        return lease(entry, player = false)
    }

    private fun existingOrRestore(request: EpisodePreparationRequest): Entry? =
        existing(request) ?: cache.completed(request)?.let { record ->
            val artifact = repository.artifactCacheFile(request)
            // Viewing a card restores only a matching derived artifact, never silently loads a new model.
            if (record.artifactName != artifact.name || !artifact.isFile) return@let null
            create(request, restoreCapture(request, File(cache.directory(request, record.directoryDay), "media.mp4"),
                null, record.lastModified), ownsCapture = true, restoreOnly = true)
        }

    /** Normal streaming uses only the player's bytes until a later explicit home action. */
    @Synchronized fun attachPlayer(input: EpisodePreparationRequest, capture: PlaybackVideoCapture): Lease {
        val request = effective(input)
        cleanupExpired()
        return lease(existing(request) ?: create(request, capture, ownsCapture = false), player = true)
    }

    /** Retrying normal playback never grants silent-download permission. */
    @Synchronized fun retryPlayer(input: EpisodePreparationRequest, capture: PlaybackVideoCapture): Lease {
        val request = effective(input)
        cleanupExpired()
        val previous = existing(request)
        if (previous?.job?.isActive == true) return lease(previous, player = true)
        return lease(create(request, capture, ownsCapture = false), player = true)
    }

    @Synchronized fun cancelHome(input: EpisodePreparationRequest) {
        val request = effective(input)
        val entry = existing(request) ?: return
        if (entry.job?.isActive != true) return
        entry.homeOwned = false
        entry.commit.cancel()
        entry.state.value = State.Cancelling(entry.preparedOn)
        android.util.Log.i("SenseVoiceASR", "CANCEL_REQUESTED key=${request.key} backend=${request.asrBackend}")
        entry.job?.cancel()
    }

    /** Offered only after failure. No automatic downgrade, same media and same coordinator. */
    @Synchronized fun startVoskFallback(input: EpisodePreparationRequest): Lease {
        val previous = existing(effective(input))
        check(previous?.state?.value is State.Failed && previous.job?.isActive != true) { "Wait for failed task to release" }
        choices[input.key] = AsrBackend.VOSK.id
        android.util.Log.i("SenseVoiceASR", "EXPLICIT_VOSK_FALLBACK key=${input.key}")
        return startHome(input, retry = true)
    }

    /** Called on IO by app/worker; running tasks and actual playback leases postpone deletion. */
    @Synchronized fun cleanupExpired() {
        cache.cleanupExpired()
        SenseVoiceVideoTranscriber.cleanupDiagnostics(context)
        val current = cache.currentDay()
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.preparedOn != current && !cache.inUse(entry.request, entry.cacheDay)) {
                entry.state.value = State.Idle
                iterator.remove()
            }
        }
    }

    private fun create(request: EpisodePreparationRequest, capture: PlaybackVideoCapture, ownsCapture: Boolean,
                       restoreOnly: Boolean = false): Entry {
        val day = cache.currentDay()
        val previousJob = entries[mapKey(request, day)]?.job
        val record = cache.begin(request, day, repository.artifactCacheFile(request).name)
        val files = cache.retain(request, record.directoryDay)
        val captureLease = capture.retain()
        val entry = Entry(request, day, cache.directory(request, record.directoryDay), capture, ownsCapture, restoreOnly, record)
        val stablePath = capture.relocateEmptyForCache(File(entry.directory, "media.mp4"))
        entries[mapKey(request, day)] = entry
        entry.state.value = State.Preparing(Stage.DOWNLOADING, "准备本期媒体", day)
        entry.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                previousJob?.join() // A cancelled predecessor must release its writer before retry reuses this path.
                if (record.mediaLength > 0 && !cache.verifyRecordedMedia(record)) {
                    throw IOException("Local media cache integrity mismatch")
                }
                withTimeout(15 * 60_000L) {
                    coroutineScope {
                        val media = async {
                            waitForMedia(entry)
                            coroutineContext.ensureActive()
                            // A late, unregistered legacy capture may already have a reader of its old path.
                            if (!stablePath) entry.result.await()
                            cache.saveMedia(entry.record, capture).also { entry.media.complete(it) }
                        }
                        val result = async {
                            var transcribing = false
                            val resolved = try { repository.resolve(request.program, request.episodeDate, request.videoUrl,
                                capture, request.sourceIdentity, AsrBackend.fromId(request.asrBackend), entry.commit, !entry.restoreOnly) { message ->
                                val stage = if (message.contains("等待") || message.contains("缓存") || message.contains("采集"))
                                    Stage.DOWNLOADING else Stage.TRANSCRIBING
                                if (stage == Stage.TRANSCRIBING) transcribing = true
                                if (entry.state.value !is State.Cancelling) entry.state.value = State.Preparing(stage, message, entry.preparedOn)
                            } } catch (error: IOException) {
                                if (transcribing) throw TranscriptionException(error) else throw error
                            }
                            if (resolved !is SubtitleResolutionResult.AVAILABLE) throw NoContentException()
                            val manifest = resolved.manifest ?: throw IOException("本期字幕身份不可用")
                            val summary = try { repository.generateSummary(manifest, resolved.evidence.cues, entry.commit) { message ->
                                entry.state.value = State.Preparing(Stage.NOTES, message, entry.preparedOn)
                            } } catch (error: IOException) { throw TranscriptionException(error) }
                            coroutineContext.ensureActive()
                            entry.commit.protect { resolved.copy(manifest = manifest.copy(episodeSummary = summary)).also { entry.result.complete(it) } }
                        }
                        val mediaFile = media.await()
                        val available = result.await()
                        entry.commit.protect { entry.state.value = State.Ready(mediaFile, available, entry.preparedOn) }
                    }
                }
            } catch (error: TimeoutCancellationException) {
                fail(entry, error, FailureKind.TIMEOUT, "本期准备超时，可点重试")
            } catch (error: CancellationException) {
                entry.state.value = State.Cancelled(entry.preparedOn)
                entry.media.completeExceptionally(error); entry.result.completeExceptionally(error)
            } catch (error: Exception) {
                val message = error.message.orEmpty()
                val kind = when {
                    error is NoContentException -> FailureKind.NO_CONTENT
                    message.contains("storage", true) || message.contains("space", true) -> FailureKind.NO_SPACE
                    message.contains("limit", true) -> FailureKind.LIMIT
                    message.contains("timed out", true) || message.contains("timeout", true) -> FailureKind.TIMEOUT
                    message.contains("interrupted", true) || message.contains("stopped", true) ||
                        message.contains("ended", true) || message.contains("integrity", true) -> FailureKind.INTERRUPTED
                    error is IOException -> FailureKind.NETWORK
                    else -> FailureKind.TRANSCRIPTION
                }
                val reason = when (kind) {
                    FailureKind.NO_SPACE -> "手机空间不足，请腾出空间后重试"
                    FailureKind.LIMIT -> if (message.contains("cache capacity", true))
                        "本地视频缓存达到160MiB上限，仍可正常播放" else "本期视频超过160MiB上限，仍可正常播放"
                    FailureKind.TIMEOUT -> "本期准备超时，可点重试"
                    FailureKind.INTERRUPTED -> if (message.contains("integrity", true))
                        "本地视频校验失败，请重新准备" else "本期准备已中断，可点重试"
                    FailureKind.NO_CONTENT -> "未取得可用语音字幕，仍可正常播放"
                    FailureKind.NETWORK -> "下载未完成，请联网后重试"
                    FailureKind.TRANSCRIPTION -> "本机字幕整理未完成，可点重试"
                }
                fail(entry, error, kind, reason)
            } finally {
                captureLease.close()
                if (ownsCapture) capture.close()
                cache.releaseReservation(request, entry.cacheDay)
                files.close()
                cleanupExpired()
            }
        }.also { it.start() }
        return entry
    }

    private suspend fun waitForMedia(entry: Entry): File = coroutineScope {
        while (true) {
            val captureWait = async { entry.capture.awaitComplete { message ->
                entry.state.value = State.Preparing(Stage.DOWNLOADING, message, entry.preparedOn)
            } }
            val complete = select<File?> {
                captureWait.onAwait { it }
                entry.downloadRequests.onReceive { null }
            }
            if (complete != null) return@coroutineScope complete
            captureWait.cancel()
            if (!entry.homeOwned) continue
            val download = async {
                entry.capture.downloadMissing({ message ->
                    entry.state.value = State.Preparing(Stage.DOWNLOADING, message, entry.preparedOn)
                }, { bytes -> cache.reserve(entry.request, entry.cacheDay, bytes) })
                entry.capture.awaitComplete {}
            }
            entry.downloadJob = download
            try { return@coroutineScope download.await() }
            catch (error: CancellationException) {
                coroutineContext.ensureActive()
                if (entry.homeOwned) throw error
            } finally { entry.downloadJob = null }
        }
        @Suppress("UNREACHABLE_CODE") error("Media wait ended")
    }

    private fun fail(entry: Entry, error: Exception, kind: FailureKind, reason: String) {
        entry.state.value = State.Failed(reason, kind, entry.preparedOn)
        entry.media.completeExceptionally(error); entry.result.completeExceptionally(error)
    }
    private class NoContentException : IOException()
    private class TranscriptionException(cause: Throwable) : Exception("本机转写未完成", cause)
}
