package org.breezyweather.domain.subtitle.local

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.ensureActive
import org.breezyweather.domain.multisource.model.TimestampedCue
import org.json.JSONObject
import org.breezyweather.domain.subtitle.model.AsrWord
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Android decodes PCM; Vosk/Kaldi resamples at the actual input rate with its low-pass filter. */
class AndroidVideoTranscriber {
    companion object {
        const val PIPELINE_VERSION = "android-pcm16-mono-vosk-native-resample-v2"
        const val LEGACY_VERSION = "android-pcm16-mono-stride16k-v1"
    }
    data class Transcript(
        val durationMs: Long, val cues: List<TimestampedCue>,
        val words: List<AsrWord> = emptyList(), val rawResults: List<String> = emptyList(),
        val pipelineVersion: String = PIPELINE_VERSION, val decodedPcmSha256: String = "",
        val inputRate: Int = 0, val decoderName: String = "", val elapsedMs: Long = 0
    )

    suspend fun transcribe(video: File, modelDirectory: File, progress: suspend (String) -> Unit): Transcript =
        transcribeInternal(video, modelDirectory, false, progress)

    /** Only for the approved same-media comparison; never selected by production preparation. */
    internal suspend fun transcribeLegacyForComparison(video: File, modelDirectory: File,
                                                      progress: suspend (String) -> Unit): Transcript {
        check(org.breezyweather.BuildConfig.DEBUG)
        return transcribeInternal(video, modelDirectory, true, progress)
    }

    private suspend fun transcribeInternal(video: File, modelDirectory: File, legacy: Boolean,
                                           progress: suspend (String) -> Unit): Transcript {
        val began = System.nanoTime()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(video.absolutePath)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val durationMs = formats.filter { it.containsKey(MediaFormat.KEY_DURATION) }
                .maxOfOrNull { it.getLong(MediaFormat.KEY_DURATION) / 1000L } ?: error("No media duration")
            require(durationMs in 1000..1_200_000) { "Unsupported episode duration" }
            val track = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            require(track >= 0) { "Video has no audio track" }
            extractor.selectTrack(track)
            val format = formats[track]
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val recognizerRate = if (legacy) 16000 else sampleRate
            if (!legacy && sampleRate != 16000) {
                val config = File(modelDirectory, "conf/mfcc.conf").readText()
                require(config.lineSequence().any { it.trim() == "--allow-downsample=true" }) {
                    "Bundled model must allow native resampling"
                }
            }
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val cues = mutableListOf<TimestampedCue>()
            val recognizedWords = mutableListOf<AsrWord>()
            val rawResults = mutableListOf<String>()
            val pcmDigest = MessageDigest.getInstance("SHA-256")
            var offsetMs: Long? = null
            fun addResult(raw: String) {
                rawResults += raw
                val words = JSONObject(raw).optJSONArray("result") ?: return
                var text = StringBuilder()
                var start = 0L; var end = 0L
                var confidences = mutableListOf<Double>()
                fun flush() {
                    val clean = text.toString().replace(" ", "").replace("\n", "").trim()
                    val safeStart = maxOf(start, cues.lastOrNull()?.endMs ?: 0L)
                    val safeEnd = minOf(end, durationMs)
                    if (clean.isNotBlank() && safeEnd > safeStart) cues += TimestampedCue(
                        safeStart, safeEnd, clean, clean,
                        confidence = confidences.minOrNull()?.toFloat(), model = LocalEpisodeArtifacts.MODEL
                    )
                    text = StringBuilder()
                    confidences = mutableListOf()
                }
                for (i in 0 until words.length()) {
                    val word = words.getJSONObject(i)
                    val a = (word.getDouble("start") * 1000).toLong() + (offsetMs ?: 0)
                    val b = (word.getDouble("end") * 1000).toLong() + (offsetMs ?: 0)
                    val value = word.getString("word")
                    val confidence = if (word.has("conf")) word.getDouble("conf") else null
                    recognizedWords += AsrWord(value, a, b, confidence)
                    if (text.isNotEmpty() && (text.length + value.length > 32 || b - start > 5000 || a - end > 600)) flush()
                    if (text.isEmpty()) start = a
                    confidence?.let(confidences::add)
                    text.append(value); end = b
                }
                flush()
            }
            Model(modelDirectory.absolutePath).use { model ->
                Recognizer(model, recognizerRate.toFloat()).use { recognizer ->
                    recognizer.setWords(true)
                    val info = MediaCodec.BufferInfo()
                    var inputDone = false; var outputDone = false
                    var frameIndex = 0L; var nextOutput = 0L; var lastProgress = -1L
                    while (!outputDone) {
                        coroutineContext.ensureActive()
                        if (!inputDone) {
                            val input = decoder.dequeueInputBuffer(10000)
                            if (input >= 0) {
                                val buffer = decoder.getInputBuffer(input)!!
                                val size = extractor.readSampleData(buffer, 0)
                                if (size < 0) {
                                    decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    decoder.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        val output = decoder.dequeueOutputBuffer(info, 10000)
                        if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val actual = decoder.outputFormat
                            sampleRate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val encoding = if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                actual.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            require(encoding ==
                                AudioFormat.ENCODING_PCM_16BIT) { "Decoder must produce 16 bit PCM" }
                            require(sampleRate >= 16000 && channels in 1..8) { "Unsupported PCM format" }
                            require(legacy || sampleRate == recognizerRate) { "Decoder changed input sample rate" }
                        } else if (output >= 0) {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                if (offsetMs == null) offsetMs = maxOf(0, info.presentationTimeUs / 1000)
                                val buffer = decoder.getOutputBuffer(output)!!.order(ByteOrder.LITTLE_ENDIAN)
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                pcmDigest.update(buffer.duplicate())
                                val mono = ShortArray(info.size / (2 * channels) + 1)
                                var count = 0
                                while (buffer.remaining() >= channels * 2) {
                                    var total = 0
                                    repeat(channels) { total += buffer.short.toInt() }
                                    if (!legacy || frameIndex * 16000 >= nextOutput * sampleRate) {
                                        mono[count++] = (total / channels).toShort(); nextOutput++
                                    }
                                    frameIndex++
                                }
                                if (count > 0 && recognizer.acceptWaveForm(mono, count)) addResult(recognizer.result)
                                val seconds = info.presentationTimeUs / 1_000_000
                                if (seconds / 10 != lastProgress) {
                                    lastProgress = seconds / 10
                                    progress("本机转写 ${seconds}s / ${durationMs / 1000}s")
                                }
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            decoder.releaseOutputBuffer(output, false)
                        }
                    }
                    addResult(recognizer.finalResult)
                }
            }
            return Transcript(durationMs, cues, recognizedWords, rawResults,
                if (legacy) LEGACY_VERSION else PIPELINE_VERSION,
                pcmDigest.digest().joinToString("") { "%02x".format(it) }, sampleRate, decoder.name,
                (System.nanoTime() - began) / 1_000_000)
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            codec?.release()
            extractor.release()
        }
    }
}
