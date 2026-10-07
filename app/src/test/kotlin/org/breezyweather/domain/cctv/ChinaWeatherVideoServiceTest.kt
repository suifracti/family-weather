/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.cctv

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import okhttp3.OkHttpClient
import org.breezyweather.domain.multisource.engine.PlaybackCandidateSelector
import org.breezyweather.domain.multisource.model.EpisodeQuery
import org.breezyweather.domain.multisource.model.PlaybackCandidate
import org.breezyweather.domain.multisource.model.ResolutionVerification
import org.breezyweather.domain.multisource.model.ResolutionVerificationStatus
import org.breezyweather.domain.multisource.model.SourceRole
import org.breezyweather.domain.multisource.model.StreamFormat
import org.breezyweather.domain.multisource.model.StreamHealthProfile
import org.breezyweather.domain.multisource.model.SubtitleType
import org.breezyweather.domain.multisource.model.VideoProgram
import org.breezyweather.domain.multisource.model.VideoProvider
import org.breezyweather.domain.multisource.model.VideoQualityEvidenceStatus
import org.breezyweather.domain.multisource.model.VideoResolution
import org.breezyweather.domain.multisource.model.WeatherVideoEpisode
import org.junit.jupiter.api.Test

class ChinaWeatherVideoServiceTest {

    private val service = ChinaWeatherVideoService(OkHttpClient())

    private val sampleJsonp = """getLbDatas({"data":[{"title":"《晚间天气预报》 20260917","pubDate":"2026-09-17 19:48:00","url":"https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4"},{"title":"《晚间天气预报》 20260916","pubDate":"2026-09-16 19:48:00","url":"https://vod.weathertv.cn/video/2026/9/16/202609161789651595541.mp4"}]})"""

    @Test
    fun `test extractDateCode parses various date formats and titles`() {
        service.extractDateCode("20260917") shouldBe "20260917"
        service.extractDateCode("2026-09-17") shouldBe "20260917"
        service.extractDateCode("2026/09/17") shouldBe "20260917"
        service.extractDateCode("《晚间天气预报》 20260917") shouldBe "20260917"
        service.extractDateCode("《晚间天气预报》 2026-09-17") shouldBe "20260917"
        service.extractDateCode("https://tv.cctv.com/2026/09/17/VIDENQPx6WifSbBI1gsBvHzc260917.shtml") shouldBe "20260917"
        service.extractDateCode("not-a-date") shouldBe null
        service.extractDateCode("") shouldBe null
        service.extractDateCode(null) shouldBe null
    }

    @Test
    fun `test exact date found resolves successfully and asserts identity matched`() {
        val result = service.resolveEpisodeFromJsonp(sampleJsonp, EpisodeQuery.Exact("20260917"))
        result shouldNotBe null
        result?.requestedEpisodeDate shouldBe "20260917"
        result?.resolvedEpisodeDate shouldBe "20260917"
        result?.episodeIdentityMatched shouldBe true
        result?.mp4Url shouldBe "https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4"
        // 20260917 is audited and bitstream probed
        result?.qualityEvidence shouldBe VideoQualityEvidenceStatus.PROBED_VERIFIED_1080P
    }

    @Test
    fun `test exact date missing returns null fail-closed and strictly forbids cross-date fallback`() {
        // Querying a missing date (20260918) MUST return null and NEVER fall back to 20260917 or latest
        val result = service.resolveEpisodeFromJsonp(sampleJsonp, EpisodeQuery.Exact("20260918"))
        result shouldBe null

        // Convenience method also returns null
        service.parseMp4FromJsonp(sampleJsonp, targetDateCode = "20260918") shouldBe null
    }

    @Test
    fun `test exact date missing falls back to same-date CCTV candidate`() {
        // China Weather does not have 20260918, so primary MP4 is unavailable
        val chinaWeatherResult = service.resolveEpisodeFromJsonp(sampleJsonp, EpisodeQuery.Exact("20260918"))
        chinaWeatherResult shouldBe null

        // CCTV has the 20260918 episode
        val cctvCandidate20260918 = PlaybackCandidate(
            candidateId = "cctv_20260918",
            provider = VideoProvider.CCTV_OFFICIAL,
            program = VideoProgram.EVENING_WEATHER,
            playbackUrl = "https://tv.cctv.com/2026/09/18/VIDEexample20260918.shtml",
            streamFormat = StreamFormat.WEBVIEW_FALLBACK,
            resolution = VideoResolution.RES_720P,
            resolutionVerification = ResolutionVerification(ResolutionVerificationStatus.UNVERIFIED_DECLARED),
            bitrateKbps = null,
            codec = null,
            subtitleType = SubtitleType.NONE,
            subtitleUrl = null,
            sourceRole = SourceRole.SEARCH_AGGREGATOR,
            healthProfile = StreamHealthProfile(isVerifiedPlayable = true),
            provenance = "CCTV Episode Page"
        )

        val episode = WeatherVideoEpisode(
            episodeId = "cctv_20260918",
            program = VideoProgram.EVENING_WEATHER,
            episodeDate = "2026-09-18",
            publishTime = "2026-09-18 19:45:00",
            durationSeconds = 240,
            guidOrPid = "pid20260918",
            sourcePageUrl = "https://tv.cctv.com/2026/09/18/VIDEexample20260918.shtml",
            coverImageUrl = null,
            summaryMetadata = null,
            candidates = listOf(cctvCandidate20260918)
        )

        val selected = PlaybackCandidateSelector.selectCandidate(episode)
        selected shouldNotBe null
        selected?.candidateId shouldBe "cctv_20260918"
        selected?.playbackUrl shouldBe "https://tv.cctv.com/2026/09/18/VIDEexample20260918.shtml"
    }

    @Test
    fun `test LATEST may resolve newest available episode`() {
        val result = service.resolveEpisodeFromJsonp(sampleJsonp, EpisodeQuery.Latest)
        result shouldNotBe null
        result?.requestedEpisodeDate shouldBe null
        result?.resolvedEpisodeDate shouldBe "20260917"
        result?.episodeIdentityMatched shouldBe true
        result?.mp4Url shouldBe "https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4"
    }

    @Test
    fun `test future or unprobed episode quality evidence not overclaimed`() {
        // 20260916 has not had prior atom bitstream verification in audit
        val result = service.resolveEpisodeFromJsonp(sampleJsonp, EpisodeQuery.Exact("20260916"))
        result shouldNotBe null
        result?.requestedEpisodeDate shouldBe "20260916"
        result?.resolvedEpisodeDate shouldBe "20260916"
        result?.qualityEvidence shouldBe VideoQualityEvidenceStatus.CHINA_WEATHER_DIRECT_MP4
    }

    @Test
    fun `test parseMp4FromJsonp handles empty or malformed inputs`() {
        service.parseMp4FromJsonp("") shouldBe null
        service.parseMp4FromJsonp("invalid jsonp string") shouldBe null
        service.parseMp4FromJsonp("""getLbDatas({"data":[]})""") shouldBe null
    }

    @Test
    fun `test fallback runtime selection gate when primary fails`() {
        val primaryCandidate = PlaybackCandidate(
            candidateId = "china_weather_1080p",
            provider = VideoProvider.CHINA_WEATHER_OFFICIAL,
            program = VideoProgram.EVENING_WEATHER,
            playbackUrl = "https://vod.weathertv.cn/video/2026/9/17/invalid_or_broken.mp4",
            streamFormat = StreamFormat.DIRECT_MP4,
            resolution = VideoResolution.RES_1080P,
            resolutionVerification = ResolutionVerification(ResolutionVerificationStatus.PROBED_VERIFIED, "ISO BMFF probe"),
            bitrateKbps = 1715,
            codec = "H.264 / AAC",
            subtitleType = SubtitleType.NONE,
            subtitleUrl = null,
            sourceRole = SourceRole.PRIMARY_ORIGIN_PRODUCER,
            healthProfile = StreamHealthProfile(isVerifiedPlayable = false),
            provenance = "China Weather Video API"
        )

        val fallbackCandidate = PlaybackCandidate(
            candidateId = "cctv_hls_fallback",
            provider = VideoProvider.CCTV_OFFICIAL,
            program = VideoProgram.EVENING_WEATHER,
            playbackUrl = "https://newcntv.qcloudcdn.com/asp/hls/main/0303000a/3/default/a4f45ccdd0494e8d8b7d190b67a034d4/main.m3u8",
            streamFormat = StreamFormat.HLS_M3U8,
            resolution = VideoResolution.RES_270P,
            resolutionVerification = ResolutionVerification(ResolutionVerificationStatus.PROBED_VERIFIED, "HLS probe"),
            bitrateKbps = 460,
            codec = "H.264 / AAC",
            subtitleType = SubtitleType.NONE,
            subtitleUrl = null,
            sourceRole = SourceRole.SEARCH_AGGREGATOR,
            healthProfile = StreamHealthProfile(isVerifiedPlayable = true),
            provenance = "CCTV VDN"
        )

        val candidates = listOf(primaryCandidate, fallbackCandidate)

        val initialSelected = PlaybackCandidateSelector.selectCandidate(candidates)
        initialSelected shouldNotBe null
        initialSelected?.candidateId shouldBe "china_weather_1080p"

        val failedIds = setOf("china_weather_1080p")
        val fallbackSelected = PlaybackCandidateSelector.selectCandidate(candidates, failedCandidateIds = failedIds)
        fallbackSelected shouldNotBe null
        fallbackSelected?.candidateId shouldBe "cctv_hls_fallback"

        val allFailedIds = setOf("china_weather_1080p", "cctv_hls_fallback")
        val noneSelected = PlaybackCandidateSelector.selectCandidate(candidates, failedCandidateIds = allFailedIds)
        noneSelected shouldBe null
    }

    @Test
    fun `test official CCTV https URL accepted by validator`() {
        CctvUrlValidator.isValidOfficialCctvUrl("https://tv.cctv.com/2026/09/17/VIDENQPx6WifSbBI1gsBvHzc260917.shtml") shouldBe true
        CctvUrlValidator.isValidOfficialCctvUrl("https://news.cctv.com/weather/index.shtml") shouldBe true
        CctvUrlValidator.isValidOfficialCctvUrl("https://api.cntv.cn/video/videoinfoByGuid") shouldBe true
        CctvUrlValidator.isValidOfficialCctvUrl("https://vdn.apps.cntv.cn/api/getHttpVideoInfo.do") shouldBe true
        CctvUrlValidator.isValidOfficialCctvUrl("https://m.cctv.com/v/v1.shtml") shouldBe true
    }

    @Test
    fun `test http URLs rejected by validator`() {
        CctvUrlValidator.isValidOfficialCctvUrl("http://tv.cctv.com/2026/09/17/VIDE123.shtml") shouldBe false
        CctvUrlValidator.isValidOfficialCctvUrl("http://api.cntv.cn/video") shouldBe false
    }

    @Test
    fun `test non-CCTV and malicious URLs rejected fail-closed`() {
        CctvUrlValidator.isValidOfficialCctvUrl("https://evil.com/phishing.shtml") shouldBe false
        CctvUrlValidator.isValidOfficialCctvUrl("https://tv.cctv.com.attacker.com/fake") shouldBe false
        CctvUrlValidator.isValidOfficialCctvUrl("https://tv.cctv.com@attacker.com/fake") shouldBe false
        CctvUrlValidator.isValidOfficialCctvUrl("javascript:alert(1)") shouldBe false
        CctvUrlValidator.isValidOfficialCctvUrl("file:///android_asset/exploit.html") shouldBe false
        CctvUrlValidator.isValidOfficialCctvUrl("") shouldBe false
        CctvUrlValidator.isValidOfficialCctvUrl(null) shouldBe false
    }

    @Test
    fun `lianbo official page and media validators admit the named sources and reject spoofed hosts`() {
        CctvUrlValidator.isValidOfficialPageUrl("https://www.weather.com.cn/video/yubao.shtml?globalid=24222") shouldBe true
        CctvUrlValidator.isValidOfficialPageUrl("https://tv.cctv.com") shouldBe true
        CctvUrlValidator.isValidOfficialPageUrl("https://www.weather.com.cn.attacker.com/video/yubao.shtml") shouldBe false
        CctvUrlValidator.isValidOfficialPageUrl("https://www.weather.com.cn@attacker.com/video/yubao.shtml") shouldBe false
        CctvUrlValidator.isValidChinaWeatherMediaUrl("https://vod.weathertv.cn/video/2026/10/2/202610021790943989883.mp4") shouldBe true
        CctvUrlValidator.isValidChinaWeatherMediaUrl("http://vod.weathertv.cn/video/test.mp4") shouldBe false
        CctvUrlValidator.isValidChinaWeatherMediaUrl("https://vod.weathertv.cn.attacker.com/video/test.mp4") shouldBe false
        CctvUrlValidator.isValidChinaWeatherMediaUrl("https://vod.weathertv.cn@attacker.com/video/test.mp4") shouldBe false
        CctvUrlValidator.isValidChinaWeatherMediaUrl("https://vod.weathertv.cn/video/test.shtml") shouldBe false
    }
}
