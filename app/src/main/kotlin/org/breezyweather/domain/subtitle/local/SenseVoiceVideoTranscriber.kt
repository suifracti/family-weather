package org.breezyweather.domain.subtitle.local

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Debug
import android.util.AtomicFile
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.ensureActive
import org.breezyweather.domain.multisource.model.TimestampedCue
import org.breezyweather.domain.subtitle.model.AsrWord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Uses the repository's single native slot. No JNI object escapes the synchronous worker. */
class SenseVoiceVideoTranscriber(private val context: Context) {
    companion object {
        private val activeDiagnostics = mutableSetOf<File>()
        @Synchronized fun cleanupDiagnostics(context: Context) {
            val root = File(context.cacheDir, "sensevoice-diagnostics")
            val today = java.time.LocalDate.now().toString()
            root.listFiles().orEmpty().filter { folder -> folder.isDirectory && folder.name != today &&
                folder.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && folder !in activeDiagnostics &&
                folder.canonicalFile.parentFile == root.canonicalFile
            }.forEach { folder ->
                folder.listFiles().orEmpty().filter { file -> file.isFile && file.canonicalFile.parentFile == folder.canonicalFile &&
                    file.name.matches(Regex("\\d+-[a-f0-9-]+\\.json(?:\\.bak|\\.new)?")) && runCatching {
                        val value = JSONObject(file.readText())
                        value.optString("backend") == "sensevoice" && value.optString("runtime") == "sherpa-onnx-1.13.8" &&
                            file.name.startsWith(value.getString("run") + ".json")
                    }.getOrDefault(false)
                }.forEach { it.delete() }
                if (folder.listFiles()?.isEmpty() == true) folder.delete()
            }
        }
    }
    suspend fun transcribe(video: File, progress: suspend (String) -> Unit): AndroidVideoTranscriber.Transcript {
        val began = System.nanoTime()
        val run = "${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}"
        val diagnostics = File(context.cacheDir, "sensevoice-diagnostics/${java.time.LocalDate.now()}")
        synchronized(Companion) { cleanupDiagnostics(context); activeDiagnostics += diagnostics }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var recognizer: OfflineRecognizer? = null
        val report = JSONObject().put("run", run).put("backend", "sensevoice").put("runtime", "sherpa-onnx-1.13.8")
            .put("pipeline", AsrBackend.SENSEVOICE.pipeline).put("humanListeningComplete", false)
        val memory = JSONArray()
        val trace = object {
            @Volatile var sampling = true
            @Volatile var phase = "beforeLoad"
        }
        fun elapsed() = (System.nanoTime() - began) / 1e6
        fun sample(): JSONObject {
            val d = Debug.MemoryInfo(); Debug.getMemoryInfo(d)
            val j = Runtime.getRuntime()
            return JSONObject().put("elapsedMs", elapsed()).put("phase", trace.phase).put("pssKiB", d.totalPss)
                .put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize()).put("javaUsedBytes", j.totalMemory() - j.freeMemory())
        }
        val sampler = Thread({ while (trace.sampling) { try {
            val value = sample(); synchronized(memory) { memory.put(value) }; Thread.sleep(250)
        } catch (_: InterruptedException) { break } } }, "sensevoice-pss").also { it.start() }
        try {
            extractor.setDataSource(video.absolutePath)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val duration = formats.filter { it.containsKey(MediaFormat.KEY_DURATION) }.maxOf { it.getLong(MediaFormat.KEY_DURATION) / 1000 }
            require(duration in 1000..1_200_000)
            val track = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            require(track >= 0) { "Media has no audio" }; extractor.selectTrack(track)
            val format = formats[track]
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(rate in 16000..96000 && channels in 1..8)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            progress("SenseVoice：按需加载本机模型")
            coroutineContext.ensureActive()
            trace.phase = "modelLoad"; val load = System.nanoTime()
            val config = OfflineRecognizerConfig().apply {
                modelConfig.senseVoice.model = "sensevoice/model.int8.onnx"
                modelConfig.senseVoice.language = "zh"
                modelConfig.senseVoice.useInverseTextNormalization = false
                modelConfig.tokens = "sensevoice/tokens.txt"
                modelConfig.numThreads = 1; modelConfig.provider = "cpu"; decodingMethod = "greedy_search"
            }
            recognizer = OfflineRecognizer(context.assets, config)
            report.put("modelLoadMs", (System.nanoTime() - load) / 1e6)
            Log.i("SenseVoiceASR", "MODEL_LOADED run=$run")
            coroutineContext.ensureActive()
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!); codec = decoder
            decoder.configure(format, null, null, 0); decoder.start()
            val pcmDigest = MessageDigest.getInstance("SHA-256")
            val words = mutableListOf<AsrWord>(); val cues = mutableListOf<TimestampedCue>(); val raw = mutableListOf<String>()
            // A bounded float window; no whole-video WAV/byte-array copies. Same integer channel average as baseline.
            var window = FloatArray(rate * 40)
            var used = 0; var windowStart = 0L; var firstPts: Long? = null; var totalSamples = 0L
            var silence = 0; var decodeMs = 0.0
            suspend fun flush() {
                if (used == 0) return
                coroutineContext.ensureActive(); trace.phase = "decode"
                progress("SenseVoice 本机转写 ${windowStart / 1000}s / ${duration / 1000}s")
                val stream = recognizer!!.createStream()
                try {
                    val input = if (used == window.size) window else window.copyOf(used)
                    stream.acceptWaveform(input, rate)
                    coroutineContext.ensureActive()
                    Log.i("SenseVoiceASR", "NATIVE_START run=$run offset=$windowStart samples=$used")
                    val t = System.nanoTime(); recognizer!!.decode(stream); decodeMs += (System.nanoTime() - t) / 1e6
                    Log.i("SenseVoiceASR", "NATIVE_RETURN run=$run offset=$windowStart")
                    coroutineContext.ensureActive() // Cancelled native work has no derived output.
                    val r = recognizer!!.getResult(stream)
                    report.put("lastWindowOffsetMs", windowStart).put("lastWindowTextLength", r.text.length)
                        .put("lastWindowTokenCount", r.tokens.size).put("lastWindowTimestampCount", r.timestamps.size)
                    val end = minOf(duration, windowStart + used * 1000L / rate)
                    val data = JSONObject().put("text", r.text).put("tokens", JSONArray(r.tokens.toList()))
                        .put("timestamps", JSONArray(r.timestamps.toList())).put("durations", JSONArray(r.durations.toList()))
                        .put("confidence", JSONObject.NULL).put("offsetMs", windowStart).put("endMs", end)
                        .put("sampleRate", rate).put("samples", used)
                    raw += data.toString()
                    val tokens = r.tokens.map { it.replace("▁", " ") }
                    val starts = r.timestamps
                    if (r.text.isNotBlank()) require(tokens.size == starts.size && starts.toList().zipWithNext().all { (a,b) -> a <= b }) { "Missing/nonmonotonic token times" }
                    // Do not invent a text/token alignment when special tokens or normalization disagree.
                    if (r.text.isBlank()) {
                        report.put("emptyWindows", report.optInt("emptyWindows") + 1)
                        // Silence/music may legitimately return no text; its raw result still remains in the transcript.
                    } else if (tokens.joinToString("").replace(" ", "") == r.text.replace(" ", "") && tokens.isNotEmpty()) {
                        var left = 0
                        while (left < tokens.size) {
                            var right = left + 1
                            while (right < tokens.size && tokens.subList(left, right + 1).sumOf { it.length } <= 32 && starts[right] - starts[left] <= 5) right++
                            val a = (windowStart + starts[left] * 1000).toLong().coerceIn(windowStart, end)
                            val b = if (right < starts.size) (windowStart + starts[right] * 1000).toLong().coerceAtMost(end) else end
                            val text = tokens.subList(left, right).joinToString("").replace(" ", "").trim()
                            if (text.isNotEmpty() && b > a) cues += TimestampedCue(a, b, text, text, confidence = null, model = AsrBackend.SENSEVOICE.model)
                            left = right
                        }
                        tokens.forEachIndexed { i, token ->
                            val a = (windowStart + starts[i] * 1000).toLong().coerceIn(windowStart, end)
                            val b = if (i + 1 < starts.size) (windowStart + starts[i+1] * 1000).toLong().coerceIn(a, end) else end
                            words += AsrWord(token, a, b, null, true)
                        }
                    } else throw java.io.IOException("SenseVoice text/token alignment unavailable")
                } finally { stream.release() }
                windowStart = (firstPts ?: 0) + totalSamples * 1000L / rate; used = 0; silence = 0; trace.phase = "pcmDecode"
            }
            val info = MediaCodec.BufferInfo(); var inputDone = false; var outputDone = false
            trace.phase = "pcmDecode"
            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val input = decoder.dequeueInputBuffer(10000)
                    if (input >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(input)!!, 0)
                        if (size < 0) { decoder.queueInputBuffer(input,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { decoder.queueInputBuffer(input,0,size,extractor.sampleTime,0); extractor.advance() }
                    }
                }
                val output = decoder.dequeueOutputBuffer(info,10000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val actual = decoder.outputFormat
                    require(actual.getInteger(MediaFormat.KEY_SAMPLE_RATE) == rate) { "Decoder changed input rate" }
                    channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    require(channels in 1..8 && (!actual.containsKey(MediaFormat.KEY_PCM_ENCODING) || actual.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT))
                } else if (output >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            if (firstPts == null) { firstPts = maxOf(0,info.presentationTimeUs / 1000); windowStart = firstPts!! }
                            val buffer = decoder.getOutputBuffer(output)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset); buffer.limit(info.offset + info.size); pcmDigest.update(buffer.duplicate())
                            while (buffer.remaining() >= channels * 2) {
                                var sum = 0; repeat(channels) { sum += buffer.short.toInt() }
                                val mono = sum / channels
                                window[used++] = mono / 32768f; totalSamples++
                                silence = if (kotlin.math.abs(mono) < 150) silence + 1 else 0
                                // Prefer a short silence after 20s; otherwise cap at40s. No dropped/overlapped samples.
                                if (used == window.size || used >= rate * 20 && silence >= rate / 3) flush()
                            }
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { decoder.releaseOutputBuffer(output,false) }
                }
            }
            flush(); window = FloatArray(0)
            coroutineContext.ensureActive(); report.put("status", "completed").put("decodeMs", decodeMs).put("audioSeconds", totalSamples.toDouble()/rate)
            return AndroidVideoTranscriber.Transcript(duration,cues,words,raw,AsrBackend.SENSEVOICE.pipeline,
                pcmDigest.digest().joinToString("") { "%02x".format(it) },rate,decoder.name,(System.nanoTime()-began)/1_000_000)
        } catch (e: Throwable) {
            report.put("status", if (e is kotlinx.coroutines.CancellationException) "cancelled" else "failed").put("error", e.toString()); throw e
        } finally {
            trace.phase = "release"; Log.i("SenseVoiceASR", "RELEASE_START run=$run")
            try { recognizer?.release() } finally {
                recognizer = null
                try { codec?.stop() } catch (_: Exception) { }; codec?.release(); extractor.release()
                Log.i("SenseVoiceASR", "RELEASE_END run=$run")
                report.put("afterRelease",sample()); System.gc(); trace.phase = "afterRelease2s"; Thread.sleep(2000)
                report.put("afterRelease2s",sample()); trace.sampling = false; sampler.join()
                var peak = 0; synchronized(memory) { for (i in 0 until memory.length()) peak = maxOf(peak,memory.getJSONObject(i).getInt("pssKiB")) }
                report.put("peakPssKiB",peak).put("memory",memory).put("totalMs",elapsed())
                // Diagnostics contain no recognized content and are written even after cancellation.
                val root = diagnostics.apply { mkdirs() }
                val atomic = AtomicFile(File(root,"$run.json")); val out = atomic.startWrite()
                try { out.write(report.toString(2).toByteArray()); atomic.finishWrite(out) } catch(e: Exception) { atomic.failWrite(out); Log.w("SenseVoiceASR","Diagnostics save failed",e) }
                synchronized(Companion) { activeDiagnostics -= diagnostics; cleanupDiagnostics(context) }
            }
        }
    }
}
