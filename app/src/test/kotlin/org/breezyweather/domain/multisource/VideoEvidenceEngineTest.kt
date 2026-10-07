/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.breezyweather.domain.multisource.engine.PlaybackCandidateSelector
import org.breezyweather.domain.multisource.engine.VideoEvidenceEngine
import org.breezyweather.domain.multisource.model.ExtractionMethod
import org.breezyweather.domain.multisource.model.Media3Playability
import org.breezyweather.domain.multisource.model.OriginAuthority
import org.breezyweather.domain.multisource.model.PlaybackCandidate
import org.breezyweather.domain.multisource.model.ResolutionVerification
import org.breezyweather.domain.multisource.model.ResolutionVerificationStatus
import org.breezyweather.domain.multisource.model.SourceRole
import org.breezyweather.domain.multisource.model.StreamFormat
import org.breezyweather.domain.multisource.model.StreamHealthProfile
import org.breezyweather.domain.multisource.model.SubtitleType
import org.breezyweather.domain.multisource.model.SummaryMetadata
import org.breezyweather.domain.multisource.model.VideoProgram
import org.breezyweather.domain.multisource.model.VideoProvider
import org.breezyweather.domain.multisource.model.VideoResolution
import org.breezyweather.domain.multisource.model.WeatherVideoEpisode
import org.junit.jupiter.api.Test

class VideoEvidenceEngineTest {

    @Test
    fun `test daily single-episode program merges CCTV and China Weather streams into single canonical episode`() {
        val cctvCandidate = PlaybackCandidate(
            candidateId = "cctv_hls_20260917",
            provider = VideoProvider.CCTV_OFFICIAL,
            program = VideoProgram.EVENING_WEATHER,
            playbackUrl = "https://newcntv.qcloudcdn.com/asp/hls/main/0303000a/3/default/a4f45ccdd0494e8d8b7d190b67a034d4/main.m3u8",
            streamFormat = StreamFormat.HLS_M3U8,
            resolution = VideoResolution.RES_270P,
            resolutionVerification = ResolutionVerification(ResolutionVerificationStatus.PROBED_VERIFIED, "HLS master m3u8 parse"),
            bitrateKbps = 460,
            codec = "H.264 / AAC",
            subtitleType = SubtitleType.NONE,
            subtitleUrl = null,
            sourceRole = SourceRole.SEARCH_AGGREGATOR,
            healthProfile = StreamHealthProfile(isVerifiedPlayable = true),
            provenance = "CCTV VDN API"
        )

        val cctvEpisode = WeatherVideoEpisode(
            episodeId = "cctv_31814870",
            program = VideoProgram.EVENING_WEATHER,
            episodeDate = "2026-09-17",
            publishTime = "2026-09-17 19:50:48",
            durationSeconds = 248,
            guidOrPid = "a4f45ccdd0494e8d8b7d190b67a034d4",
            sourcePageUrl = "https://tv.cctv.com/2026/09/17/VIDENQPx6WifSbBI1gsBvHzc260917.shtml",
            coverImageUrl = "https://p1.img.cctvpic.com/fmspic/2026/09/17/a4f45ccdd0494e8d8b7d190b67a034d4-1.jpg",
            summaryMetadata = SummaryMetadata(
                text = "9月17日晚间天气预报：全国主要降水分布",
                originAuthority = OriginAuthority.OFFICIAL_PORTAL_EDITORIAL,
                extractionMethod = ExtractionMethod.HUMAN_EDITORIAL_SUMMARY,
                confidence = 0.9
            ),
            candidates = listOf(cctvCandidate)
        )

        val weatherComCandidate = PlaybackCandidate(
            candidateId = "tq_mp4_20260917",
            provider = VideoProvider.CHINA_WEATHER_OFFICIAL,
            program = VideoProgram.EVENING_WEATHER,
            playbackUrl = "https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4",
            streamFormat = StreamFormat.DIRECT_MP4,
            resolution = VideoResolution.RES_1080P,
            resolutionVerification = ResolutionVerification(ResolutionVerificationStatus.PROBED_VERIFIED, "ISO BMFF tkhd track probe"),
            bitrateKbps = 1715,
            codec = "H.264 / AAC",
            subtitleType = SubtitleType.NONE,
            subtitleUrl = null,
            sourceRole = SourceRole.PRIMARY_ORIGIN_PRODUCER,
            healthProfile = StreamHealthProfile(isVerifiedPlayable = true),
            provenance = "中国天气网 lianbo_3m"
        )

        val weatherComEpisode = WeatherVideoEpisode(
            episodeId = "weathercom_24029",
            program = VideoProgram.EVENING_WEATHER,
            episodeDate = "2026-09-17",
            publishTime = "2026-09-17 20:26:04",
            durationSeconds = 248,
            guidOrPid = null,
            sourcePageUrl = "http://www.weather.com.cn/video/yubao.shtml?globalid=24029",
            coverImageUrl = "https://i.i8tq.com/video/index_summer.png",
            summaryMetadata = SummaryMetadata(
                text = "新闻联播天气预报 20260917",
                originAuthority = OriginAuthority.OFFICIAL_PORTAL_EDITORIAL,
                extractionMethod = ExtractionMethod.HUMAN_EDITORIAL_SUMMARY,
                confidence = 0.8
            ),
            candidates = listOf(weatherComCandidate)
        )

        val mergedList = VideoEvidenceEngine.deduplicateAndMergeEpisodes(listOf(cctvEpisode, weatherComEpisode))

        mergedList.size shouldBe 1
        val canonical = mergedList.first()
        canonical.episodeDate shouldBe "2026-09-17"
        canonical.candidates.size shouldBe 2

        canonical.bestPlaybackCandidate shouldNotBe null
        canonical.bestPlaybackCandidate?.candidateId shouldBe "tq_mp4_20260917"
        canonical.bestPlaybackCandidate?.resolution shouldBe VideoResolution.RES_1080P
        canonical.bestPlaybackCandidate?.sourceRole shouldBe SourceRole.PRIMARY_ORIGIN_PRODUCER
    }

    @Test
    fun `test multi-release programs do not falsely deduplicate multiple daily broadcasts`() {
        val alertMorning = WeatherVideoEpisode(
            episodeId = "alert_001",
            program = VideoProgram.WEATHER_EXPRESS,
            episodeDate = "2026-09-17",
            publishTime = "2026-09-17 08:30:00",
            durationSeconds = 60,
            guidOrPid = "pid_morning_typhoon",
            titleFingerprint = "台风预警早间速递",
            sourcePageUrl = "https://tv.cctv.com/alert1.shtml",
            coverImageUrl = null,
            summaryMetadata = null,
            candidates = emptyList()
        )

        val alertEvening = WeatherVideoEpisode(
            episodeId = "alert_002",
            program = VideoProgram.WEATHER_EXPRESS,
            episodeDate = "2026-09-17",
            publishTime = "2026-09-17 17:00:00",
            durationSeconds = 90,
            guidOrPid = "pid_evening_heavyrain",
            titleFingerprint = "暴雨预警晚间速递",
            sourcePageUrl = "https://tv.cctv.com/alert2.shtml",
            coverImageUrl = null,
            summaryMetadata = null,
            candidates = emptyList()
        )

        val merged = VideoEvidenceEngine.deduplicateAndMergeEpisodes(listOf(alertMorning, alertEvening))

        // Multi-release programs MUST retain both episodes
        merged.size shouldBe 2
        merged.map { it.guidOrPid } shouldBe listOf("pid_evening_heavyrain", "pid_morning_typhoon")
    }

    @Test
    fun `test candidate ranking prioritizes origin producer over aggregator`() {
        val aggregatorStream = PlaybackCandidate(
            candidateId = "aggregator_stream",
            provider = VideoProvider.CCTV_OFFICIAL,
            program = VideoProgram.EVENING_WEATHER,
            playbackUrl = "https://cctv.com/480p.m3u8",
            streamFormat = StreamFormat.HLS_M3U8,
            resolution = VideoResolution.RES_480P,
            resolutionVerification = ResolutionVerification(ResolutionVerificationStatus.UNVERIFIED_DECLARED),
            bitrateKbps = 800,
            codec = "H.264",
            subtitleType = SubtitleType.NONE,
            subtitleUrl = null,
            sourceRole = SourceRole.SEARCH_AGGREGATOR,
            healthProfile = StreamHealthProfile(isVerifiedPlayable = true),
            provenance = "CCTV Search"
        )

        val originProducerStream = PlaybackCandidate(
            candidateId = "producer_stream",
            provider = VideoProvider.CHINA_WEATHER_OFFICIAL,
            program = VideoProgram.EVENING_WEATHER,
            playbackUrl = "https://vod.weathertv.cn/1080p.mp4",
            streamFormat = StreamFormat.DIRECT_MP4,
            resolution = VideoResolution.RES_1080P,
            resolutionVerification = ResolutionVerification(ResolutionVerificationStatus.PROBED_VERIFIED, "ISO BMFF tkhd track probe"),
            bitrateKbps = 1715,
            codec = "H.264",
            subtitleType = SubtitleType.NONE,
            subtitleUrl = null,
            sourceRole = SourceRole.PRIMARY_ORIGIN_PRODUCER,
            healthProfile = StreamHealthProfile(isVerifiedPlayable = true),
            provenance = "中国天气网 lianbo_3m"
        )

        val ranked = VideoEvidenceEngine.rankPlaybackCandidates(listOf(aggregatorStream, originProducerStream))
        ranked.first().candidateId shouldBe "producer_stream"
    }

    @Test
    fun `test summary authority orthogonal separation`() {
        val webBrief = SummaryMetadata(
            text = "官方网编简介",
            originAuthority = OriginAuthority.OFFICIAL_PORTAL_EDITORIAL,
            extractionMethod = ExtractionMethod.HUMAN_EDITORIAL_SUMMARY,
            confidence = 0.95
        )
        val asrTranscript = SummaryMetadata(
            text = "算法语音识别摘要",
            originAuthority = OriginAuthority.BROADCAST_AUDIO_PRIMARY,
            extractionMethod = ExtractionMethod.AUTOMATED_ASR_EXTRACTED,
            confidence = 0.75
        )

        webBrief.originAuthority shouldBe OriginAuthority.OFFICIAL_PORTAL_EDITORIAL
        webBrief.extractionMethod shouldBe ExtractionMethod.HUMAN_EDITORIAL_SUMMARY

        asrTranscript.originAuthority shouldBe OriginAuthority.BROADCAST_AUDIO_PRIMARY
        asrTranscript.extractionMethod shouldBe ExtractionMethod.AUTOMATED_ASR_EXTRACTED
    }

    @Test
    fun `test Task D2_1 verified episode candidates ranking and subtitle audit assertions`() {
        val chinaWeather1080p = PlaybackCandidate(
            candidateId = "cw_mp4_1080p_20260917",
            provider = VideoProvider.CHINA_WEATHER_OFFICIAL,
            program = VideoProgram.EVENING_WEATHER,
            playbackUrl = "https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4",
            streamFormat = StreamFormat.DIRECT_MP4,
            resolution = VideoResolution.RES_1080P,
            resolutionVerification = ResolutionVerification(ResolutionVerificationStatus.PROBED_VERIFIED, "ffprobe"),
            bitrateKbps = 1636,
            codec = "H.264 High / AAC-LC",
            subtitleType = SubtitleType.NONE,
            subtitleUrl = null,
            sourceRole = SourceRole.PRIMARY_ORIGIN_PRODUCER,
            healthProfile = StreamHealthProfile(isVerifiedPlayable = true),
            provenance = "China Weather lianbo_3m",
            audioCodec = "aac",
            audioSampleRateHz = 48000,
            audioChannels = 2,
            media3Playability = org.breezyweather.domain.multisource.model.Media3Playability.YES,
            burnedInVisualText = false,
            probeResultStatus = org.breezyweather.domain.multisource.model.ProbeResultStatus.PROBED_VERIFIED_1080P
        )

        val cctvHls720p = PlaybackCandidate(
            candidateId = "cctv_hls_720p_20260917",
            provider = VideoProvider.CCTV_OFFICIAL,
            program = VideoProgram.EVENING_WEATHER,
            playbackUrl = "https://dh5wswx02.v.cntv.cn/asp/h5e/hls/2000/0303000a/3/default/a4f45ccdd0494e8d8b7d190b67a034d4/2000.m3u8",
            streamFormat = StreamFormat.HLS_M3U8,
            resolution = VideoResolution.RES_720P,
            resolutionVerification = ResolutionVerification(ResolutionVerificationStatus.PROBED_VERIFIED, "master_playlist_parse"),
            bitrateKbps = 2048,
            codec = "H.264 High / AAC-LC",
            subtitleType = SubtitleType.NONE,
            subtitleUrl = null,
            sourceRole = SourceRole.SEARCH_AGGREGATOR,
            healthProfile = StreamHealthProfile(isVerifiedPlayable = true),
            provenance = "CCTV VDN manifest.hls_h5e_url",
            audioCodec = "aac",
            audioSampleRateHz = 48000,
            audioChannels = 2,
            media3Playability = org.breezyweather.domain.multisource.model.Media3Playability.CONDITIONAL,
            burnedInVisualText = false,
            probeResultStatus = org.breezyweather.domain.multisource.model.ProbeResultStatus.PROBED_VERIFIED_720P
        )

        val cctvHls270p = PlaybackCandidate(
            candidateId = "cctv_hls_270p_20260917",
            provider = VideoProvider.CCTV_OFFICIAL,
            program = VideoProgram.EVENING_WEATHER,
            playbackUrl = "https://newcntv.qcloudcdn.com/asp/hls/main/0303000a/3/default/a4f45ccdd0494e8d8b7d190b67a034d4/main.m3u8",
            streamFormat = StreamFormat.HLS_M3U8,
            resolution = VideoResolution.RES_270P,
            resolutionVerification = ResolutionVerification(ResolutionVerificationStatus.PROBED_VERIFIED, "ffprobe"),
            bitrateKbps = 460,
            codec = "H.264 High / AAC-LC",
            subtitleType = SubtitleType.NONE,
            subtitleUrl = null,
            sourceRole = SourceRole.SEARCH_AGGREGATOR,
            healthProfile = StreamHealthProfile(isVerifiedPlayable = true),
            provenance = "CCTV VDN default hls_url",
            audioCodec = "aac",
            audioSampleRateHz = 48000,
            audioChannels = 2,
            media3Playability = org.breezyweather.domain.multisource.model.Media3Playability.CONDITIONAL,
            burnedInVisualText = false,
            probeResultStatus = org.breezyweather.domain.multisource.model.ProbeResultStatus.PROBED_VERIFIED_270P
        )

        val cctvWebViewFallback = PlaybackCandidate(
            candidateId = "cctv_web_page",
            provider = VideoProvider.CCTV_OFFICIAL,
            program = VideoProgram.EVENING_WEATHER,
            playbackUrl = "https://tv.cctv.com/2026/09/17/VIDENQPx6WifSbBI1gsBvHzc260917.shtml",
            streamFormat = StreamFormat.WEBVIEW_FALLBACK,
            resolution = VideoResolution.UNVERIFIED_PENDING_PROBE,
            resolutionVerification = ResolutionVerification(ResolutionVerificationStatus.UNVERIFIED_DECLARED),
            bitrateKbps = null,
            codec = null,
            subtitleType = SubtitleType.NONE,
            subtitleUrl = null,
            sourceRole = SourceRole.SEARCH_AGGREGATOR,
            healthProfile = StreamHealthProfile(isVerifiedPlayable = true),
            provenance = "CCTV Search urllink",
            media3Playability = org.breezyweather.domain.multisource.model.Media3Playability.NO
        )

        val ranked = VideoEvidenceEngine.rankPlaybackCandidates(
            listOf(cctvWebViewFallback, cctvHls270p, cctvHls720p, chinaWeather1080p)
        )

        // 1. Highest ranked candidate is China Weather 1080p Direct MP4
        ranked.first().candidateId shouldBe "cw_mp4_1080p_20260917"
        ranked.first().resolution shouldBe VideoResolution.RES_1080P
        ranked.first().media3Playability shouldBe org.breezyweather.domain.multisource.model.Media3Playability.YES

        // 2. Both official sources have NO separate subtitle tracks
        ranked.all { it.subtitleType == SubtitleType.NONE } shouldBe true

        // 3. The best ASR input stream matches the highest quality audio (48kHz stereo AAC from 1080p source)
        val bestAsrStream = ranked.first()
        bestAsrStream.audioSampleRateHz shouldBe 48000
        bestAsrStream.audioChannels shouldBe 2
        bestAsrStream.streamFormat shouldBe StreamFormat.DIRECT_MP4
    }

    @Test
    fun `test PlaybackCandidateSelector falls back to CCTV WebView when primary MP4 fails`() {
        val chinaWeather1080p = PlaybackCandidate(
            candidateId = "cw_mp4_1080p",
            provider = VideoProvider.CHINA_WEATHER_OFFICIAL,
            program = VideoProgram.EVENING_WEATHER,
            playbackUrl = "https://vod.weathertv.cn/broken.mp4",
            streamFormat = StreamFormat.DIRECT_MP4,
            resolution = VideoResolution.RES_1080P,
            resolutionVerification = ResolutionVerification(ResolutionVerificationStatus.PROBED_VERIFIED),
            bitrateKbps = 1636,
            codec = "H.264",
            subtitleType = SubtitleType.NONE,
            subtitleUrl = null,
            sourceRole = SourceRole.PRIMARY_ORIGIN_PRODUCER,
            healthProfile = StreamHealthProfile(isVerifiedPlayable = true),
            provenance = "China Weather",
            media3Playability = Media3Playability.YES
        )

        val cctvHls720p = PlaybackCandidate(
            candidateId = "cctv_hls_720p",
            provider = VideoProvider.CCTV_OFFICIAL,
            program = VideoProgram.EVENING_WEATHER,
            playbackUrl = "https://cctv.com/720p.m3u8",
            streamFormat = StreamFormat.HLS_M3U8,
            resolution = VideoResolution.RES_720P,
            resolutionVerification = ResolutionVerification(ResolutionVerificationStatus.PROBED_VERIFIED),
            bitrateKbps = 2048,
            codec = "H.264",
            subtitleType = SubtitleType.NONE,
            subtitleUrl = null,
            sourceRole = SourceRole.SEARCH_AGGREGATOR,
            healthProfile = StreamHealthProfile(isVerifiedPlayable = true),
            provenance = "CCTV VDN",
            media3Playability = Media3Playability.CONDITIONAL
        )

        val cctvWebViewFallback = PlaybackCandidate(
            candidateId = "cctv_web_page",
            provider = VideoProvider.CCTV_OFFICIAL,
            program = VideoProgram.EVENING_WEATHER,
            playbackUrl = "https://tv.cctv.com/page.shtml",
            streamFormat = StreamFormat.WEBVIEW_FALLBACK,
            resolution = VideoResolution.UNVERIFIED_PENDING_PROBE,
            resolutionVerification = ResolutionVerification(ResolutionVerificationStatus.UNVERIFIED_DECLARED),
            bitrateKbps = null,
            codec = null,
            subtitleType = SubtitleType.NONE,
            subtitleUrl = null,
            sourceRole = SourceRole.SEARCH_AGGREGATOR,
            healthProfile = StreamHealthProfile(isVerifiedPlayable = true),
            provenance = "CCTV Search",
            media3Playability = Media3Playability.NO
        )

        val candidates = listOf(cctvWebViewFallback, cctvHls720p, chinaWeather1080p)

        // 1. Initial selection: primary 1080p MP4
        val initialSelected = PlaybackCandidateSelector.selectCandidate(candidates, failedCandidateIds = emptySet())
        initialSelected?.candidateId shouldBe "cw_mp4_1080p"

        // 2. Primary MP4 failed: fallback to CCTV HLS 720p
        val fallback1 = PlaybackCandidateSelector.selectCandidate(candidates, failedCandidateIds = setOf("cw_mp4_1080p"))
        fallback1?.candidateId shouldBe "cctv_hls_720p"

        // 3. Both direct streams failed: fallback to CCTV WebView
        val fallback2 = PlaybackCandidateSelector.selectCandidate(candidates, failedCandidateIds = setOf("cw_mp4_1080p", "cctv_hls_720p"))
        fallback2?.candidateId shouldBe "cctv_web_page"
        fallback2?.streamFormat shouldBe StreamFormat.WEBVIEW_FALLBACK

        // 4. All failed: returns null
        val noneLeft = PlaybackCandidateSelector.selectCandidate(candidates, failedCandidateIds = setOf("cw_mp4_1080p", "cctv_hls_720p", "cctv_web_page"))
        noneLeft shouldBe null
    }
}
