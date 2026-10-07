package org.breezyweather.domain.subtitle.local

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.Closeable
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Collections
import kotlin.coroutines.coroutineContext

/** Copies only bytes already received by the actual player. It issues no requests of its own. */
class PlaybackVideoCapture(context: Context, private val videoUrl: String) {
    companion object {
        const val MAX_BYTES = 160L * 1024 * 1024
        private val liveCaptures = Collections.synchronizedSet(mutableSetOf<PlaybackVideoCapture>())
        fun activeFiles(): Set<File> = synchronized(liveCaptures) { liveCaptures.map { it.file.canonicalFile }.toSet() }
        fun completed(context: Context, videoUrl: String, file: File, etag: String?, lastModified: String?): PlaybackVideoCapture =
            PlaybackVideoCapture(context, videoUrl).apply {
                require(file.isFile && file.length() in 1..MAX_BYTES)
                this.file = file; expectedLength = file.length(); this.etag = etag
                this.lastModified = lastModified; opened = true; complete = true; deleteWhenReleased = false
                ranges += 0L to expectedLength
            }
    }

    @Volatile var file = File(context.cacheDir, "local-episode-asr/playback-${System.nanoTime()}.mp4"); private set
    @Volatile var expectedLength: Long = -1; private set
    @Volatile var etag: String? = null; private set
    @Volatile var lastModified: String? = null; private set
    @Volatile private var opened = false
    @Volatile private var complete = false
    @Volatile private var stopped = false
    @Volatile private var failure: String? = null
    private var output: RandomAccessFile? = null
    private val ranges = mutableListOf<Pair<Long, Long>>()
    private val activeSources = mutableSetOf<DataSource>()
    private var retainCount = 1
    private var ownerReleased = false
    private var deleteWhenReleased = true
    @Volatile private var downloading = false
    private val upstream = DefaultDataSource.Factory(context)
    init { liveCaptures += this }

    val factory = DataSource.Factory {
        val source = upstream.createDataSource()
        object : DataSource {
            private var isEpisode = false
            private var position = 0L
            private var original: DataSpec? = null
            private var remaining = C.LENGTH_UNSET.toLong()
            @Volatile private var closed = false
            private var sourceOpened = false
            override fun addTransferListener(listener: TransferListener) = source.addTransferListener(listener)
            override fun getUri(): Uri? = if (isEpisode && (downloading || complete)) Uri.parse(videoUrl) else source.uri
            override fun getResponseHeaders(): Map<String, List<String>> = source.responseHeaders
            override fun open(spec: DataSpec): Long {
                isEpisode = spec.uri.toString() == videoUrl
                position = spec.position
                original = spec; remaining = spec.length; closed = false
                if (isEpisode && (downloading || complete) && awaitLocalRevision()) {
                    return if (remaining == C.LENGTH_UNSET.toLong()) expectedLength - position else remaining
                }
                synchronized(this@PlaybackVideoCapture) { if (isEpisode) activeSources += source }
                try {
                    val length = source.open(spec)
                    sourceOpened = true
                    if (isEpisode) inspect(length, spec, source.responseHeaders)
                    return length
                } catch (error: IOException) {
                    if (!isEpisode || (!downloading && !complete)) throw error
                    if (awaitLocalRevision()) return if (remaining == C.LENGTH_UNSET.toLong()) expectedLength - position else remaining
                    return source.open(spec).also { sourceOpened = true }
                }
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0L) return C.RESULT_END_OF_INPUT
                val wanted = if (remaining > 0) minOf(length.toLong(), remaining).toInt() else length
                var localRead = isEpisode && (downloading || complete)
                var count: Int
                if (localRead) { sourceOpened = false; count = readLocal(buffer, offset, wanted) }
                else {
                    try {
                        if (!sourceOpened && isEpisode) {
                            val resume = original!!.subrange(position - original!!.position,
                                if (remaining > 0) remaining else C.LENGTH_UNSET.toLong())
                            val total = source.open(resume); sourceOpened = true
                            synchronized(this@PlaybackVideoCapture) { activeSources += source }
                            inspect(total, resume, source.responseHeaders)
                        }
                        count = source.read(buffer, offset, wanted)
                    } catch (error: IOException) {
                        if (!isEpisode || (!downloading && !complete)) throw error
                        sourceOpened = false; localRead = true
                        count = readLocal(buffer, offset, wanted)
                    }
                }
                if (isEpisode && count > 0) {
                    if (!localRead) record(position, buffer, offset, count)
                    position += count
                }
                if (count > 0 && remaining > 0) remaining -= count
                return count
            }
            override fun close() {
                closed = true; sourceOpened = false
                synchronized(this@PlaybackVideoCapture) { activeSources -= source }
                source.close()
            }
            private fun awaitLocalRevision(): Boolean {
                val deadline = System.nanoTime() + 30_000_000_000L
                while (expectedLength <= 0 && !complete) {
                    if (closed || stopped) throw IOException("Playback capture is closed")
                    if (!downloading) return false
                    if (failure != null) { Thread.sleep(50); continue }
                    if (System.nanoTime() >= deadline) throw IOException("Local media preparation timed out")
                    Thread.sleep(50)
                }
                return true
            }
            private fun readLocal(buffer: ByteArray, offset: Int, length: Int): Int {
                // Download is the sole network producer; the existing player can consume its file.
                while (downloading || complete) {
                    if (closed || stopped) throw IOException("Playback capture is closed")
                    if (failure != null && !complete) { Thread.sleep(50); continue }
                    val available = synchronized(this@PlaybackVideoCapture) {
                        ranges.firstOrNull { position >= it.first && position < it.second }
                            ?.let { minOf(length.toLong(), it.second - position).toInt() } ?: 0
                    }
                    if (available > 0) return RandomAccessFile(file, "r").use { reader ->
                        reader.seek(position); reader.read(buffer, offset, available)
                    }
                    if (complete && position >= expectedLength) return C.RESULT_END_OF_INPUT
                    Thread.sleep(50)
                }
                // A failed/cancelled preparation resumes ordinary network playback on next read.
                sourceOpened = false
                return read(buffer, offset, length)
            }
        }
    }

    @Synchronized
    private fun inspect(length: Long, spec: DataSpec, headers: Map<String, List<String>>) {
        if (stopped || complete) return
        fun header(key: String) = headers.entries.firstOrNull { it.key.equals(key, true) }?.value?.firstOrNull()
        val total = header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            ?: if (length != C.LENGTH_UNSET.toLong() && spec.length == C.LENGTH_UNSET.toLong()) length + spec.position else -1
        if (total in 1..MAX_BYTES) expectedLength = total
        else if (total > MAX_BYTES) failure = "Episode exceeds capture limit"
        val currentEtag = header("ETag")
        if (opened && etag != null && currentEtag != null && etag != currentEtag) {
            failure = "Media revision changed during playback"
        }
        etag = currentEtag; lastModified = header("Last-Modified"); opened = true
    }

    @Synchronized
    private fun record(position: Long, buffer: ByteArray, offset: Int, count: Int) {
        if (complete || stopped || failure != null) return
        try {
            require(position + count <= MAX_BYTES)
            val writer = output ?: run {
                file.parentFile!!.mkdirs(); RandomAccessFile(file, "rw").also { it.setLength(0); output = it }
            }
            writer.seek(position); writer.write(buffer, offset, count)
            ranges += position to position + count
            ranges.sortBy { it.first }
            val merged = mutableListOf<Pair<Long, Long>>()
            for (range in ranges) {
                val last = merged.lastOrNull()
                if (last != null && range.first <= last.second) merged[merged.lastIndex] = last.first to maxOf(last.second, range.second)
                else merged += range
            }
            ranges.clear(); ranges.addAll(merged)
            if (expectedLength > 0 && ranges.first().first == 0L && ranges.first().second >= expectedLength) {
                writer.close(); output = null; complete = true
                Log.i("LocalEpisodeASR", "PLAYBACK_BYTES_COMPLETE size=$expectedLength url=$videoUrl")
            }
        } catch (error: Exception) {
            failure = error.message ?: "Cannot capture played video"
            try { output?.close() } catch (_: Exception) { }
            output = null
            Log.w("LocalEpisodeASR", "PLAYBACK_CAPTURE_FAILED video continues: $failure")
        }
    }

    suspend fun awaitRevision() {
        repeat(300) {
            coroutineContext.ensureActive()
            if (opened) return
            failure?.let { throw IOException(it) }
            if (stopped) throw IOException("Playback ended before revision became available")
            delay(100)
        }
        throw IOException("Player has not obtained this episode")
    }

    suspend fun awaitComplete(progress: suspend (String) -> Unit): File {
        var lastBucket = -1
        repeat(7200) {
            coroutineContext.ensureActive()
            if (complete) return file
            failure?.let { throw IOException(it) }
            if (stopped) throw IOException("Playback stopped before all audio was received")
            val bytes = synchronized(this) { ranges.sumOf { it.second - it.first } }
            val percent = if (expectedLength > 0) (bytes * 100 / expectedLength).toInt() else 0
            if (percent / 5 != lastBucket) {
                lastBucket = percent / 5
                progress("从正在播放的本期视频采集音频 $percent%")
            }
            delay(100)
        }
        throw IOException("Playback capture timed out")
    }

    /** The player's owner and each processing borrower release independently. */
    @Synchronized fun retain(): Closeable {
        check(!stopped) { "Playback capture already released" }
        retainCount++
        var released = false
        return Closeable { synchronized(this) { if (!released) { released = true; releaseReference() } } }
    }

    @Synchronized fun close() {
        if (ownerReleased) return
        ownerReleased = true
        releaseReference()
    }

    private fun releaseReference() {
        retainCount--
        if (retainCount > 0) return
        stopped = true
        liveCaptures -= this
        try { output?.close() } catch (_: Exception) { }
        output = null
        // This is only this playback's temporary byte capture, never App user data.
        if (deleteWhenReleased) file.delete()
    }

    /** Commit a complete capture to the leased daily cache, without invalidating current borrowers. */
    @Synchronized internal fun relocateEmptyForCache(destination: File): Boolean {
        if (file.canonicalFile == destination.canonicalFile) return true
        if (opened || ranges.isNotEmpty() || output != null) return false
        destination.parentFile!!.mkdirs()
        file = destination
        return true
    }

    @Synchronized fun persistCompleted(destination: File) {
        check(complete && file.isFile && file.length() == expectedLength)
        if (file.canonicalFile != destination.canonicalFile) {
            destination.parentFile!!.mkdirs()
            if (!file.renameTo(destination)) {
                val temporary = File(destination.parentFile, "media.copying")
                file.copyTo(temporary, overwrite = true)
                check(temporary.renameTo(destination)) { "Cannot retain complete episode media" }
                file.delete()
            }
            file = destination
        }
        deleteWhenReleased = false
    }

    /** A home request takes over the network producer and fetches only uncovered byte ranges. */
    suspend fun downloadMissing(progress: suspend (String) -> Unit, budget: (Long) -> Unit = {}) {
        val sources = synchronized(this) {
            if (complete) return
            check(!stopped)
            failure?.let { throw IOException(it) }
            downloading = true
            activeSources.toList().also { activeSources.clear() }
        }
        // Finish/close existing player requests before starting any range request.
        sources.forEach { source -> try { source.close() } catch (_: Exception) { } }
        try {
            val buffer = ByteArray(65536)
            while (!complete) {
                coroutineContext.ensureActive()
                failure?.let { throw IOException(it) }
                val missing = synchronized(this) {
                    var start = 0L
                    for (range in ranges) {
                        if (range.first > start) break
                        start = maxOf(start, range.second)
                    }
                    val end = ranges.firstOrNull { it.first > start }?.first
                        ?: expectedLength.takeIf { it > start }
                    start to end
                }
                val directory = file.parentFile!!.apply { mkdirs() }
                val needed = expectedLength.takeIf { it > 0 }?.minus(missing.first) ?: 8L * 1024 * 1024
                if (directory.usableSpace < needed + 4L * 1024 * 1024) throw IOException("Not enough storage for episode")
                val source = upstream.createDataSource()
                val spec = DataSpec.Builder().setUri(videoUrl).setPosition(missing.first).apply {
                    missing.second?.let { setLength(it - missing.first) }
                }.build()
                var position = missing.first
                try {
                    val length = source.open(spec)
                    inspect(length, spec, source.responseHeaders)
                    failure?.let { throw IOException(it) }
                    if (expectedLength > 0) budget(expectedLength)
                    while (!complete) {
                        coroutineContext.ensureActive()
                        val count = source.read(buffer, 0, buffer.size)
                        if (count == C.RESULT_END_OF_INPUT) {
                            synchronized(this) {
                                if (expectedLength < 0 && position > 0 && ranges.firstOrNull()?.let {
                                    it.first == 0L && it.second >= position
                                } == true) {
                                    expectedLength = position; output?.close(); output = null; complete = true
                                }
                            }
                            break
                        }
                        if (count <= 0) continue
                        if (position + count > MAX_BYTES) throw IOException("Episode exceeds capture limit")
                        if (expectedLength <= 0) budget(position + count)
                        record(position, buffer, 0, count); position += count
                        failure?.let { throw IOException(it) }
                        val bytes = synchronized(this) { ranges.sumOf { it.second - it.first } }
                        val percent = if (expectedLength > 0) (bytes * 100 / expectedLength).toInt() else 0
                        progress("静默下载本期视频 $percent%")
                    }
                    if (!complete && position == missing.first) throw IOException("Episode download was interrupted")
                } finally { source.close() }
            }
        } finally { downloading = false }
    }
}
