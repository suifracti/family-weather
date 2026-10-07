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
import okhttp3.OkHttpClient
import org.breezyweather.domain.multisource.model.SubtitleOrigin
import org.breezyweather.domain.subtitle.cache.InMemorySubtitleArtifactCache
import org.breezyweather.domain.subtitle.cache.SubtitleCacheKey
import org.breezyweather.domain.subtitle.model.SubtitleManifest
import org.breezyweather.domain.subtitle.resolver.SubtitleArtifactResolver
import org.breezyweather.domain.subtitle.resolver.SubtitleResolutionResult
import org.breezyweather.domain.subtitle.resolver.toDisplaySubtitle
import org.breezyweather.domain.subtitle.source.HttpSubtitleArtifactSource
import org.breezyweather.domain.subtitle.util.Sha256Util
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class SubtitleArtifactResolverTest {

    private lateinit var server: HttpServer
    private var baseUrl: String = ""
    private val json = Json { ignoreUnknownKeys = true }

    // Dynamic routing map for test mock responses
    private val pathResponses = mutableMapOf<String, Pair<Int, String>>()

    private val validVttContent = """
        WEBVTT
        NOTE episodeDate=2026-09-17
        NOTE asrModel=SenseVoiceSmall
        NOTE subtitleOrigin=AI_ASR_GENERATED

        1
        00:00:06.000 --> 00:00:07.470
        大家来看天气，

        2
        00:00:07.470 --> 00:00:11.190
        过去两天四川盆地降雨频繁
    """.trimIndent()

    private val validVttSha256 = Sha256Util.calculateSha256(validVttContent)

    private fun createValidManifest(
        program: String = "EVENING_WEATHER",
        episodeDate: String = "2026-09-17",
        vttSha256: String = validVttSha256,
        schemaVersion: String = "1.0.0",
        subtitleOrigin: String = SubtitleOrigin.AI_ASR_GENERATED.name,
        cueCount: Int = 2,
        durationMs: Long = 260000L
    ): SubtitleManifest {
        return SubtitleManifest(
            schemaVersion = schemaVersion,
            program = program,
            episodeDate = episodeDate,
            sourceVideoUrl = "https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4",
            sourceVideoSha256 = "454C707852EA609C66273C79C799A9DF0287C42FA1BC129809041F020E5C2BAE",
            durationMs = durationMs,
            subtitleOrigin = subtitleOrigin,
            extractionMethod = "AUTOMATED_ASR_EXTRACTED",
            asrModel = "SenseVoiceSmall",
            generatedAt = "2026-09-18T05:46:00Z",
            vttFile = "$episodeDate.ai-asr.vtt",
            vttSha256 = vttSha256,
            cueCount = cueCount
        )
    }

    @BeforeEach
    fun setUp() {
        pathResponses.clear()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange: HttpExchange ->
            val path = exchange.requestURI.path
            val normPath = path.lowercase().replace('_', '-')
            val response = pathResponses[path]
                ?: pathResponses.entries.find { it.key.lowercase().replace('_', '-') == normPath }?.value
            if (response != null) {
                val bytes = response.second.toByteArray(Charsets.UTF_8)
                // COS may return attachment disposition; the resolver must parse the body normally.
                exchange.responseHeaders.add("Content-Disposition", "attachment")
                exchange.responseHeaders.add(
                    "Content-Type",
                    if (path.endsWith("manifest.json")) {
                        "application/json; charset=utf-8"
                    } else {
                        "text/vtt; charset=utf-8"
                    }
                )
                exchange.sendResponseHeaders(response.first, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.sendResponseHeaders(404, -1)
            }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `test EXACT_SUBTITLE_RESOLUTION = VERIFIED`() = runTest {
        val program = "EVENING_WEATHER"
        val episodeDate = "2026-09-17"
        val manifest = createValidManifest(program, episodeDate)

        pathResponses["/subtitles/$program/$episodeDate/manifest.json"] = 200 to json.encodeToString(manifest)
        pathResponses["/subtitles/$program/$episodeDate/${manifest.vttFile}"] = 200 to validVttContent

        val source = HttpSubtitleArtifactSource(baseUrl = baseUrl, client = OkHttpClient())
        val cache = InMemorySubtitleArtifactCache()
        val resolver = SubtitleArtifactResolver(source = source, cache = cache)

        val result = resolver.resolve(program, episodeDate)
        result.shouldBeInstanceOf<SubtitleResolutionResult.AVAILABLE>()

        val evidence = result.evidence
        evidence.episodeDate shouldBe episodeDate
        evidence.metadata.origin shouldBe SubtitleOrigin.AI_ASR_GENERATED
        evidence.metadata.isOfficial shouldBe false
        evidence.metadata.uiLabel shouldBe "AI 自动转写字幕"
        evidence.cues.size shouldBe 2
        evidence.cues[0].rawText shouldBe "大家来看天气，"
        evidence.cues[1].rawText shouldBe "过去两天四川盆地降雨频繁"

        // Display mapping should be available without blocking video playback
        val display = result.toDisplaySubtitle()
        display.isAvailable shouldBe true
        display.playbackBlocked shouldBe false
        display.uiLabel shouldBe "AI 自动转写字幕"
    }

    @Test
    fun `test HASH_VALIDATION = VERIFIED with tamper rejection and fail-closed`() = runTest {
        val program = "EVENING_WEATHER"
        val episodeDate = "2026-09-17"
        // Manifest expects validVttSha256
        val manifest = createValidManifest(program, episodeDate, vttSha256 = validVttSha256)

        // Server returns tampered VTT content
        val tamperedVtt = validVttContent.replace("降雨频繁", "暴雨突袭")
        pathResponses["/subtitles/$program/$episodeDate/manifest.json"] = 200 to json.encodeToString(manifest)
        pathResponses["/subtitles/$program/$episodeDate/${manifest.vttFile}"] = 200 to tamperedVtt

        val source = HttpSubtitleArtifactSource(baseUrl = baseUrl, client = OkHttpClient())
        val cache = InMemorySubtitleArtifactCache()
        val resolver = SubtitleArtifactResolver(source = source, cache = cache)

        val result = resolver.resolve(program, episodeDate)
        result.shouldBeInstanceOf<SubtitleResolutionResult.HASH_MISMATCH>()

        result.expectedSha256 shouldBe validVttSha256
        result.actualSha256 shouldBe Sha256Util.calculateSha256(tamperedVtt)

        // Corrupted/tampered VTT must NOT be cached
        cache.size() shouldBe 0

        // Fail-closed: Subtitle unavailable, but playback is NEVER blocked
        val display = result.toDisplaySubtitle()
        display.isAvailable shouldBe false
        display.playbackBlocked shouldBe false
        display.cues.isEmpty() shouldBe true
        display.errorReason?.contains("HASH_MISMATCH") shouldBe true
    }

    @Test
    fun `test CACHE_ISOLATION = VERIFIED prevents cross-date reuse and isolates hash versions`() = runTest {
        val program = "EVENING_WEATHER"
        val sep17Date = "2026-09-17"
        val sep18Date = "2026-09-18"

        val sep17Manifest = createValidManifest(program, sep17Date)
        pathResponses["/subtitles/$program/$sep17Date/manifest.json"] = 200 to json.encodeToString(sep17Manifest)
        pathResponses["/subtitles/$program/$sep17Date/${sep17Manifest.vttFile}"] = 200 to validVttContent

        val source = HttpSubtitleArtifactSource(baseUrl = baseUrl, client = OkHttpClient())
        val cache = InMemorySubtitleArtifactCache()
        val resolver = SubtitleArtifactResolver(source = source, cache = cache)

        // 1. Resolve for Sep 17
        val sep17Result = resolver.resolve(program, sep17Date)
        sep17Result.shouldBeInstanceOf<SubtitleResolutionResult.AVAILABLE>()
        cache.size() shouldBe 1

        // Verify key strictly includes program + sep17Date + hash
        val sep17Key = SubtitleCacheKey(program, sep17Date, sep17Manifest.vttSha256)
        cache.get(sep17Key) shouldNotBe null

        // 2. Request Sep 18 (which has no manifest on server)
        val sep18Result = resolver.resolve(program, sep18Date)
        sep18Result.shouldBeInstanceOf<SubtitleResolutionResult.NOT_FOUND>()

        // Cache must NOT reuse Sep 17's subtitles for Sep 18!
        val sep18Key = SubtitleCacheKey(program, sep18Date, sep17Manifest.vttSha256)
        cache.get(sep18Key) shouldBe null

        // 3. Cache isolation by VTT hash
        val differentHashKey = SubtitleCacheKey(program, sep17Date, "0000000000000000000000000000000000000000000000000000000000000000")
        cache.get(differentHashKey) shouldBe null
    }

    @Test
    fun `test NETWORK_FAIL_CLOSED = VERIFIED on 404 and 500 errors`() = runTest {
        val program = "EVENING_WEATHER"
        val episodeDate = "2026-09-17"

        val source = HttpSubtitleArtifactSource(baseUrl = baseUrl, client = OkHttpClient())
        val resolver = SubtitleArtifactResolver(source = source)

        // 1. Test 404 NOT_FOUND
        val notFoundResult = resolver.resolve(program, episodeDate)
        notFoundResult.shouldBeInstanceOf<SubtitleResolutionResult.NOT_FOUND>()
        val display404 = notFoundResult.toDisplaySubtitle()
        display404.isAvailable shouldBe false
        display404.playbackBlocked shouldBe false

        // 2. Test 500 SERVER_ERROR
        pathResponses["/subtitles/$program/$episodeDate/manifest.json"] = 500 to "Internal Server Error"
        val errorResult = resolver.resolve(program, episodeDate)
        errorResult.shouldBeInstanceOf<SubtitleResolutionResult.NETWORK_ERROR>()
        val display500 = errorResult.toDisplaySubtitle()
        display500.isAvailable shouldBe false
        display500.playbackBlocked shouldBe false
    }

    @Test
    fun `test NO_EDITORIAL_BRIEF_FALLBACK = VERIFIED forbids using CCTV brief as subtitles`() = runTest {
        val editorialBrief = "9月17日晚间天气预报：四川盆地及华西地区持续降雨，累计降雨量达250毫米。"

        // When NOT_FOUND
        val notFound = SubtitleResolutionResult.NOT_FOUND("Not found")
        val displayNotFound = notFound.toDisplaySubtitle(editorialBriefFallbackCandidate = editorialBrief)
        displayNotFound.isAvailable shouldBe false
        displayNotFound.cues.none { it.rawText.contains("四川盆地") } shouldBe true
        displayNotFound.cues.isEmpty() shouldBe true
        displayNotFound.playbackBlocked shouldBe false

        // When HASH_MISMATCH
        val hashMismatch = SubtitleResolutionResult.HASH_MISMATCH("expected", "actual")
        val displayMismatch = hashMismatch.toDisplaySubtitle(editorialBriefFallbackCandidate = editorialBrief)
        displayMismatch.isAvailable shouldBe false
        displayMismatch.cues.none { it.rawText.contains("四川盆地") } shouldBe true
        displayMismatch.cues.isEmpty() shouldBe true

        // When NETWORK_ERROR
        val netError = SubtitleResolutionResult.NETWORK_ERROR("Connection timeout")
        val displayNet = netError.toDisplaySubtitle(editorialBriefFallbackCandidate = editorialBrief)
        displayNet.isAvailable shouldBe false
        displayNet.cues.none { it.rawText.contains("四川盆地") } shouldBe true
        displayNet.cues.isEmpty() shouldBe true

        // When INVALID_MANIFEST
        val invalidManifest = SubtitleResolutionResult.INVALID_MANIFEST("Invalid schema")
        val displayInvalid = invalidManifest.toDisplaySubtitle(editorialBriefFallbackCandidate = editorialBrief)
        displayInvalid.isAvailable shouldBe false
        displayInvalid.cues.none { it.rawText.contains("四川盆地") } shouldBe true
        displayInvalid.cues.isEmpty() shouldBe true
    }

    @Test
    fun `test exact episode identity mismatch rejects manifest`() = runTest {
        val requestedProgram = "EVENING_WEATHER"
        val requestedDate = "2026-09-18"

        // Server erroneously returns Sep 17 manifest when Sep 18 was requested
        val mismatchedManifest = createValidManifest(program = requestedProgram, episodeDate = "2026-09-17")
        pathResponses["/subtitles/$requestedProgram/$requestedDate/manifest.json"] = 200 to json.encodeToString(mismatchedManifest)

        val source = HttpSubtitleArtifactSource(baseUrl = baseUrl, client = OkHttpClient())
        val resolver = SubtitleArtifactResolver(source = source)

        val result = resolver.resolve(requestedProgram, requestedDate)
        result.shouldBeInstanceOf<SubtitleResolutionResult.INVALID_MANIFEST>()
        result.reason.contains("Episode date identity mismatch") shouldBe true
    }

    @Test
    fun `test invalid subtitleOrigin is rejected`() = runTest {
        val program = "EVENING_WEATHER"
        val episodeDate = "2026-09-17"

        // Manifest falsely claims official track instead of AI_ASR_GENERATED
        val manifest = createValidManifest(
            program = program,
            episodeDate = episodeDate,
            subtitleOrigin = "OFFICIAL_SEPARATE_SUBTITLE"
        )
        pathResponses["/subtitles/$program/$episodeDate/manifest.json"] = 200 to json.encodeToString(manifest)

        val source = HttpSubtitleArtifactSource(baseUrl = baseUrl, client = OkHttpClient())
        val resolver = SubtitleArtifactResolver(source = source)

        val result = resolver.resolve(program, episodeDate)
        result.shouldBeInstanceOf<SubtitleResolutionResult.INVALID_MANIFEST>()
        result.reason.contains("Invalid subtitleOrigin") shouldBe true
    }

    @Test
    fun `test unsupported schemaVersion is rejected`() = runTest {
        val program = "EVENING_WEATHER"
        val episodeDate = "2026-09-17"

        val manifest = createValidManifest(
            program = program,
            episodeDate = episodeDate,
            schemaVersion = "2.0.0"
        )
        pathResponses["/subtitles/$program/$episodeDate/manifest.json"] = 200 to json.encodeToString(manifest)

        val source = HttpSubtitleArtifactSource(baseUrl = baseUrl, client = OkHttpClient())
        val resolver = SubtitleArtifactResolver(source = source)

        val result = resolver.resolve(program, episodeDate)
        result.shouldBeInstanceOf<SubtitleResolutionResult.INVALID_MANIFEST>()
        result.reason.contains("Unsupported schemaVersion") shouldBe true
    }

    @Test
    fun `test VTT duration boundary violation is rejected`() = runTest {
        val program = "EVENING_WEATHER"
        val episodeDate = "2026-09-17"

        // Manifest says duration is 10 seconds (10000ms), but cue 2 ends at 11190ms
        val manifest = createValidManifest(
            program = program,
            episodeDate = episodeDate,
            durationMs = 10000L
        )
        pathResponses["/subtitles/$program/$episodeDate/manifest.json"] = 200 to json.encodeToString(manifest)
        pathResponses["/subtitles/$program/$episodeDate/${manifest.vttFile}"] = 200 to validVttContent

        val source = HttpSubtitleArtifactSource(baseUrl = baseUrl, client = OkHttpClient())
        val resolver = SubtitleArtifactResolver(source = source)

        val result = resolver.resolve(program, episodeDate)
        result.shouldBeInstanceOf<SubtitleResolutionResult.INVALID_MANIFEST>()
        result.reason.contains("exceeds media duration") shouldBe true
    }
}
