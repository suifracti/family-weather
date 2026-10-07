package org.breezyweather.debug

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.util.Log
import android.widget.TextView
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.breezyweather.domain.subtitle.local.AndroidVideoTranscriber
import org.breezyweather.domain.subtitle.local.LocalEpisodeArtifacts
import org.breezyweather.domain.subtitle.local.PlaybackVideoCapture
import org.breezyweather.domain.subtitle.resolver.SubtitleResolutionResult
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/** Explicit debug-only content checks; no player, network, fault injection or cache clearing. */
class ContentAudioCheckActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "本机音频检查，不播放视频" })
        Thread {
            val folder = File(filesDir, "content-audio-check").apply { mkdirs() }
            try {
                if (intent.getStringExtra("check") == "compare") {
                    runBlocking { compare(folder) }
                    runOnUiThread { finish() }
                    return@Thread
                }
                if (intent.getStringExtra("check") == "summary") {
                    runBlocking { summarize(folder) }
                    runOnUiThread { finish() }
                    return@Thread
                }
                val modelPath = File(filesDir, "local-asr-models/vosk-model-small-cn-0.22")
                val config = File(modelPath, "conf/mfcc.conf").readText()
                check(config.contains("--allow-downsample=true"))
                val result = JSONObject().put("aar", "vosk-android-0.3.75")
                    .put("mfccConfig", config).put("inputRate", 48000)
                Model(modelPath.path).use { model ->
                    Recognizer(model, 48000f).use { recognizer ->
                        recognizer.setWords(true)
                        recognizer.acceptWaveForm(ShortArray(48000), 48000)
                        result.put("result", JSONObject(recognizer.finalResult))
                    }
                }
                result.put("status", "48k-input-accepted-by-packaged-native-library")
                File(folder, "rate-probe.json").writeText(result.toString(2))
                Log.i("ContentAudioCheck", "RATE_PROBE_COMPLETE")
            } catch (error: Throwable) {
                File(folder, "rate-probe-error.txt").writeText(error.stackTraceToString())
                Log.e("ContentAudioCheck", "RATE_PROBE_FAILED", error)
            }
            runOnUiThread { finish() }
        }.start()
    }

    private suspend fun compare(folder: File) {
        // Frozen identity, used only by this explicit debug check; no injected failure or network request.
        val source = "https://vod.weathertv.cn/video/2026/10/2/202610021790947621655.mp4"
        val identity = "weather_com_cn|CHINA_WEATHER_LIANBO|3M|24222"
        val media = File(cacheDir, "episode-preparation/2026-10-03-0e50d2ad1b12de8ab8a1a296173e12dca0d46d3335cd9eb52e1bafb2bac6edde/media.mp4")
        check(media.isFile && media.length() == 52749094L)
        val model = File(filesDir, "local-asr-models/${LocalEpisodeArtifacts.MODEL}")
        check(File(model, "installed.sha256").readText() == LocalEpisodeArtifacts.MODEL_SHA256)
        val repository = LocalEpisodeArtifacts(this)
        val serializer = Json { encodeDefaults = true }
        if (!File(folder, "before-stride.json").exists()) {
            val memory = MemorySample()
            try {
                val transcript = AndroidVideoTranscriber().transcribeLegacyForComparison(media, model) {
                    Log.i("ContentAudioCheck", "LEGACY $it")
                }
                val result = JSONObject().put("pipelineVersion", transcript.pipelineVersion)
                    .put("modelSha256", LocalEpisodeArtifacts.MODEL_SHA256).put("mediaBytes", media.length())
                    .put("decodedPcmSha256", transcript.decodedPcmSha256).put("inputRate", transcript.inputRate)
                    .put("decoder", transcript.decoderName).put("elapsedMs", transcript.elapsedMs)
                    .put("words", JSONArray(serializer.encodeToString(transcript.words)))
                    .put("rawResults", JSONArray(transcript.rawResults))
                    .put("cues", JSONArray().apply { transcript.cues.forEach { cue ->
                        put(JSONObject().put("startMs", cue.startMs).put("endMs", cue.endMs)
                            .put("text", cue.rawText).put("confidence", cue.confidence))
                    } })
                memory.stop()
                result.put("memory", memory.json())
                File(folder, "before-stride.json").writeText(result.toString(2))
                Log.i("ContentAudioCheck", "LEGACY_COMPLETE")
            } finally { memory.stop() }
        }
        val capture = PlaybackVideoCapture.completed(this, source, media, null, "Fri, 02 Oct 2026 13:29:06 GMT")
        val memory = MemorySample()
        try {
            val result = repository.resolve("CHINA_WEATHER_LIANBO", "2026-10-02", source, capture, identity) {
                Log.i("ContentAudioCheck", "NATIVE $it")
            }
            check(result is SubtitleResolutionResult.AVAILABLE && !result.fromCache) { "Expected a new native-rate transcript" }
            memory.stop()
            File(folder, "after-native.json").writeText(serializer.encodeToString(result.manifest!!))
            File(folder, "after-native-memory.json").writeText(memory.json().toString(2))
            Log.i("ContentAudioCheck", "NATIVE_COMPLETE")
        } finally { memory.stop(); capture.close() }
        File(folder, "compare-complete.txt").writeText("same preserved media and bundled model; no listening verification")
    }

    private class MemorySample {
        @Volatile private var active = true
        private var pssKb = 0; private var nativeBytes = 0L; private var javaBytes = 0L; private var samples = 0
        private val worker = Thread {
            while (active) {
                val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info)
                pssKb = maxOf(pssKb, info.totalPss)
                nativeBytes = maxOf(nativeBytes, Debug.getNativeHeapAllocatedSize())
                javaBytes = maxOf(javaBytes, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                samples++
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
        }.apply { start() }
        fun stop() { active = false; worker.interrupt(); worker.join() }
        fun json() = JSONObject().put("samplingMs", 500).put("sampledPeakPssKiB", pssKb)
            .put("sampledPeakNativeHeapBytes", nativeBytes).put("sampledPeakJavaHeapBytes", javaBytes).put("samples", samples)
    }

    private suspend fun summarize(folder: File) {
        val source = "https://vod.weathertv.cn/video/2026/10/2/202610021790947621655.mp4"
        val identity = "weather_com_cn|CHINA_WEATHER_LIANBO|3M|24222"
        val media = File(cacheDir, "episode-preparation/2026-10-03-0e50d2ad1b12de8ab8a1a296173e12dca0d46d3335cd9eb52e1bafb2bac6edde/media.mp4")
        val capture = PlaybackVideoCapture.completed(this, source, media, null, "Fri, 02 Oct 2026 13:29:06 GMT")
        try {
            val repository = LocalEpisodeArtifacts(this)
            val result = repository.resolve("CHINA_WEATHER_LIANBO", "2026-10-02", source, capture, identity)
            check(result is SubtitleResolutionResult.AVAILABLE && result.fromCache) { "Summary must reuse native transcript" }
            val start = System.nanoTime()
            val memory = MemorySample()
            val summary = try { repository.generateSummary(result.manifest!!, result.evidence.cues) {} }
                finally { memory.stop() }
            File(folder, "after-structured.json").writeText(Json { encodeDefaults = true }.encodeToString(
                result.manifest!!.copy(episodeSummary = summary)))
            File(folder, "summary-measurement.json").writeText(memory.json()
                .put("elapsedMs", (System.nanoTime() - start) / 1_000_000).put("fromCachedTranscript", true).toString(2))
            Log.i("ContentAudioCheck", "STRUCTURED_COMPLETE items=${summary?.items?.size}")
        } finally { capture.close() }
    }
}
