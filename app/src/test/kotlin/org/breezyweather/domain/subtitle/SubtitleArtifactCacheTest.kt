/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.subtitle

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.breezyweather.domain.subtitle.cache.DiskSubtitleArtifactCache
import org.breezyweather.domain.subtitle.cache.SubtitleCacheKey
import org.breezyweather.domain.subtitle.model.SubtitleManifest
import org.breezyweather.domain.subtitle.resolver.SubtitleArtifactResolver
import org.breezyweather.domain.subtitle.resolver.SubtitleResolutionResult
import org.breezyweather.domain.subtitle.source.HttpSubtitleArtifactSource
import org.breezyweather.domain.subtitle.source.SubtitleNetworkStats
import org.breezyweather.domain.subtitle.util.Sha256Util
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class SubtitleArtifactCacheTest {

    private lateinit var server: HttpServer
    private var baseUrl: String = ""
    private val json = Json { ignoreUnknownKeys = true }

    private val validVttContent = """
        WEBVTT
        NOTE episodeDate=2026-09-17
        NOTE asrModel=whisper-large-v3
        NOTE subtitleOrigin=AI_ASR_GENERATED

        1
        00:00:06.000 --> 00:00:07.470
        大家来看天气，

        2
        00:00:07.470 --> 00:00:11.190
        过去两天四川盆地降雨频繁
    """.trimIndent()

    private val validVttSha256 = Sha256Util.calculateSha256(validVttContent)

    private val manifest = SubtitleManifest(
        schemaVersion = "1.0",
        program = "EVENING_WEATHER",
        episodeDate = "2026-09-17",
        vttFile = "20260917.ai-asr.vtt",
        vttSha256 = validVttSha256,
        sourceVideoUrl = "https://tv.cctv.com/2026/09/17/VIDE123456789.shtml",
        sourceVideoSha256 = "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855",
        durationMs = 125000L,
        cueCount = 2,
        asrModel = "whisper-large-v3",
        generatedAt = "2026-09-17T19:35:00Z",
        subtitleOrigin = "AI_ASR_GENERATED",
        extractionMethod = "AUTOMATED_ASR_EXTRACTED"
    )

    @BeforeEach
    fun setUp() {
        SubtitleNetworkStats.reset()
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.executor = Executors.newCachedThreadPool()

        server.createContext("/") { exchange ->
            val normPath = exchange.requestURI.path.lowercase().replace('_', '-')
            when (normPath) {
                "/subtitles/evening-weather/2026-09-17/manifest.json" -> {
                    val bytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                "/subtitles/evening-weather/2026-09-17/20260917.ai-asr.vtt" -> {
                    val bytes = validVttContent.toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                else -> {
                    val bytes = "Not found".toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(404, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
        }
        server.start()
        baseUrl = "http://localhost:${server.address.port}"
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `test DiskSubtitleArtifactCache disk persistence and reuse without refetching`(@TempDir tempDir: File) = runTest {
        val diskCache = DiskSubtitleArtifactCache(tempDir)
        val cacheKey = SubtitleCacheKey("EVENING_WEATHER", "2026-09-17", validVttSha256)

        // Cold get -> null
        val coldResult = diskCache.get(cacheKey)
        coldResult shouldBe null

        val source = HttpSubtitleArtifactSource(baseUrl = baseUrl)
        val resolver1 = SubtitleArtifactResolver(source = source, cache = diskCache)

        // First resolution: cold fetch from network
        val res1 = resolver1.resolve("EVENING_WEATHER", "2026-09-17")
        res1.shouldBeInstanceOf<SubtitleResolutionResult.AVAILABLE>()
        res1.fromCache shouldBe false
        SubtitleNetworkStats.manifestRequestCount.get() shouldBe 1
        SubtitleNetworkStats.vttRequestCount.get() shouldBe 1

        // Verify disk file actually written
        val diskFile = diskCache.getDiskFile(cacheKey)
        diskFile.exists() shouldBe true
        diskFile.length() shouldNotBe 0L

        // Second resolution with a brand new resolver instance sharing the same disk cache
        // Simulates activity reopen / recreation
        val resolver2 = SubtitleArtifactResolver(source = source, cache = diskCache)
        val res2 = resolver2.resolve("EVENING_WEATHER", "2026-09-17")
        res2.shouldBeInstanceOf<SubtitleResolutionResult.AVAILABLE>()
        res2.fromCache shouldBe true

        // Manifest requested again to verify freshness, but VTT is REUSED from disk!
        SubtitleNetworkStats.manifestRequestCount.get() shouldBe 2
        SubtitleNetworkStats.vttRequestCount.get() shouldBe 1 // VTT count unchanged!
    }

    @Test
    fun `test DiskSubtitleArtifactCache corrupt disk file rejected and fail-closed`(@TempDir tempDir: File) = runTest {
        val diskCache = DiskSubtitleArtifactCache(tempDir)
        val cacheKey = SubtitleCacheKey("EVENING_WEATHER", "2026-09-17", validVttSha256)

        // Pre-create corrupt disk file with wrong content (tampered)
        val diskFile = diskCache.getDiskFile(cacheKey)
        diskFile.parentFile?.mkdirs()
        diskFile.writeText("WEBVTT\nTampered malicious content", Charsets.UTF_8)

        // Cache get should detect hash mismatch, delete corrupted file, and return null
        val cached = diskCache.get(cacheKey)
        cached shouldBe null
        diskFile.exists() shouldBe false
    }
}
