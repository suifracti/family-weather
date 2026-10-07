/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource.model

/**
 * Weather video content provider.
 */
enum class VideoProvider(val id: String, val displayName: String) {
    CHINA_WEATHER_OFFICIAL("weather_com_cn", "中国天气网 (中国气象局公共气象服务中心)"),
    CCTV_OFFICIAL("cctv_official", "央视网 (中央广播电视总台)"),
    UNKNOWN("unknown", "未知提供方")
}

/**
 * Institutional role of the video source in the production and broadcast pipeline.
 */
enum class SourceRole(val id: String, val displayName: String, val authorityRank: Int) {
    PRIMARY_ORIGIN_PRODUCER("producer", "原始节目制作方 (中国气象局影视中心)", 4),
    NATIONAL_NETWORK_BROADCASTER("broadcaster", "国家级首发播出平台 (CCTV-1 / CCTV-13)", 3),
    OFFICIAL_MET_PORTAL("portal", "官方气象发布门户 (中国天气网)", 2),
    SEARCH_AGGREGATOR("aggregator", "搜索引擎收录聚合 (央视网检索)", 1)
}

/**
 * Standardized weather video programs.
 */
enum class VideoProgram(
    val id: String,
    val displayName: String,
    val broadcastChannel: String,
    /** Whether this program broadcasts strictly once per calendar day */
    val isDailySingleEpisode: Boolean
) {
    EVENING_WEATHER("evening_weather", "《晚间天气预报》", "CCTV-1 / CCTV-13 19:32", true),
    MORNING_WEATHER("morning_weather", "《朝闻天下天气预报》", "CCTV-1 / CCTV-13 早间", true),
    NOON_WEATHER("noon_weather", "《午间天气预报》", "CCTV-1 / CCTV-13 12:00", true),
    FIRST_IMPRESSION("first_impression", "《第一印象》天气预报", "CCTV-2 财经", true),
    CCTV4_GLOBAL("cctv4_global", "CCTV-4 中文国际天气预报", "CCTV-4 中文国际", true),
    CCTV7_MILITARY("cctv7_military", "CCTV-7 国防军事天气预报", "CCTV-7 国防军事", true),
    CCTV17_AGRI("cctv17_agri", "CCTV-17 农业气象", "CCTV-17 农业农村", true),

    // Multi-release / sporadic programs (can have multiple releases per day)
    WEATHER_EXPRESS("weather_express", "天气速递", "全网气象融媒体", false),
    EARLY_WARNING_VIDEO("early_warning_video", "预警视频", "国家预警发布平台", false),
    EXPERT_INTERPRETATION("expert_interpretation", "专家解读", "气象专家深度分析", false)
}

/**
 * Verified subtitle type.
 * Never disguise ASR transcription as official subtitles.
 */
enum class SubtitleType(val id: String, val displayText: String, val isOfficial: Boolean) {
    OFFICIAL_EMBEDDED("official_vtt", "官方封装字幕 / WebVTT", true),
    AUTO_TRANSCRIBED("auto_asr", "自动转写 (FunASR/SenseVoice) - 非官方字幕", false),
    NONE("none", "无独立字幕", false)
}

/**
 * Origin authority of video summary text.
 */
enum class OriginAuthority(val id: String, val displayText: String) {
    /** Text originates directly from broadcast speech/subtitles */
    BROADCAST_AUDIO_PRIMARY("broadcast_primary", "电视节目正文台词 (声音/实况)"),
    /** Text originates from official web portal editorial staff */
    OFFICIAL_PORTAL_EDITORIAL("official_editorial", "官方气象 / 电视门户编辑撰写"),
    THIRD_PARTY_SYNDICATION("third_party", "第三方转载"),
    NONE("none", "无文本来源")
}

/**
 * Extraction / production method of video summary text.
 */
enum class ExtractionMethod(val id: String, val displayText: String) {
    VERBATIM_OFFICIAL_TRANSCRIPT("verbatim_transcript", "官方同期声字音台词"),
    HUMAN_EDITORIAL_SUMMARY("human_editorial", "人工编辑简介 (内容高度凝练，覆盖有限)"),
    AUTOMATED_ASR_EXTRACTED("automated_asr", "算法语音识别转写 (来自节目正文，存在转写误差)"),
    NONE("none", "无提取方法")
}

/**
 * Composite summary metadata with decoupled origin authority and extraction method.
 */
data class SummaryMetadata(
    val text: String,
    val originAuthority: OriginAuthority,
    val extractionMethod: ExtractionMethod,
    val confidence: Double = 1.0
)

/**
 * Video stream packaging format.
 */
enum class StreamFormat(val id: String) {
    DIRECT_MP4("direct_mp4"),
    HLS_M3U8("hls_m3u8"),
    WEBVIEW_FALLBACK("webview_page")
}

/**
 * Resolution verification state.
 */
enum class ResolutionVerificationStatus {
    /** Verified by probing container atom (e.g. ISO BMFF tkhd) or ffprobe */
    PROBED_VERIFIED,
    /** Declared by webpage or metadata, but not yet bitstream verified */
    UNVERIFIED_DECLARED
}

data class ResolutionVerification(
    val status: ResolutionVerificationStatus,
    val probeMethod: String? = null
)

/**
 * Standardized video resolution tiers.
 */
enum class VideoResolution(val width: Int, val height: Int, val label: String, val rank: Int) {
    RES_1080P(1920, 1080, "1080p 全高清", 5),
    RES_720P(1280, 720, "720p 高清", 4),
    RES_480P(854, 480, "480p 标清", 3),
    RES_360P(640, 360, "360p 流畅", 2),
    RES_270P(480, 270, "270p 省流", 1),
    UNVERIFIED_PENDING_PROBE(0, 0, "待实测清晰度", 0)
}

/**
 * Direct playback capability with Android Media3 / ExoPlayer.
 */
enum class Media3Playability {
    YES,
    NO,
    CONDITIONAL
}

/**
 * Technical probe verification outcome.
 */
enum class ProbeResultStatus {
    PROBED_VERIFIED_1080P,
    PROBED_VERIFIED_720P,
    PROBED_VERIFIED_480P,
    PROBED_VERIFIED_360P,
    PROBED_VERIFIED_270P,
    PROBE_FAILED
}

/**
 * Granular resolution and quality evidence status for video streams.
 * Distinguishes audited bitstream probes, unverified direct streams, and real-time renderer evidence.
 */
enum class VideoQualityEvidenceStatus(val id: String, val displayText: String) {
    /** 2026-09-17 or ISO BMFF bitstream probed & verified 1080p */
    PROBED_VERIFIED_1080P("probed_1080p", "经探针实测验证 1080p"),
    /** Direct MP4 from China Weather without prior bitstream metadata probe */
    CHINA_WEATHER_DIRECT_MP4("direct_mp4", "中国天气网直链 MP4 (待运行时验证)"),
    /** Runtime verified by Android Media3 onVideoSizeChanged (1920x1080) */
    RUNTIME_VERIFIED_1080P("runtime_1080p", "运行时实测渲染 1080p"),
    /** Other runtime resolution detected */
    RUNTIME_DETECTED_OTHER("runtime_other", "运行时实测渲染其它清晰度")
}

/**
 * Strict query semantics for episode discovery.
 */
sealed class EpisodeQuery {
    /** Strictly query an exact date code ("YYYYMMDD"). Never falls back to different dates. */
    data class Exact(val dateCode: String) : EpisodeQuery()
    /** Query the latest broadcast episode available on the official portal. */
    object Latest : EpisodeQuery()
}

/**
 * Canonical result of episode identity resolution.
 */
data class EpisodeResolutionResult(
    val requestedEpisodeDate: String?,
    val resolvedEpisodeDate: String?,
    val episodeIdentityMatched: Boolean,
    val mp4Url: String?,
    val qualityEvidence: VideoQualityEvidenceStatus
)

/**
 * Status of official separate subtitle tracks.
 */
enum class OfficialSubtitleStatus {
    VERIFIED_AVAILABLE,
    VERIFIED_UNAVAILABLE
}

/**
 * Observed stream reliability metrics (measurable, avoiding hardcoded subjective assumptions).
 */
data class StreamHealthProfile(
    val isVerifiedPlayable: Boolean = true,
    val observedSuccessRate: Double? = null,
    val startupLatencyMs: Long? = null,
    val errorCount: Int = 0
)

/**
 * Concrete playback candidate stream for a given video episode.
 */
data class PlaybackCandidate(
    val candidateId: String,
    val provider: VideoProvider,
    val program: VideoProgram,
    val playbackUrl: String,
    val streamFormat: StreamFormat,
    val resolution: VideoResolution,
    val resolutionVerification: ResolutionVerification,
    val bitrateKbps: Int?,
    val codec: String?,
    val subtitleType: SubtitleType,
    val subtitleUrl: String?,
    val sourceRole: SourceRole,
    val healthProfile: StreamHealthProfile,
    val provenance: String,
    val audioCodec: String? = null,
    val audioSampleRateHz: Int? = null,
    val audioChannels: Int? = null,
    val media3Playability: Media3Playability = Media3Playability.YES,
    val burnedInVisualText: Boolean = false,
    val probeResultStatus: ProbeResultStatus = ProbeResultStatus.PROBED_VERIFIED_1080P
)

/**
 * Canonical deduplicated weather video episode.
 */
data class WeatherVideoEpisode(
    val episodeId: String,
    val program: VideoProgram,
    val episodeDate: String, // yyyy-MM-dd
    val publishTime: String, // yyyy-MM-dd HH:mm:ss
    val durationSeconds: Int,
    val guidOrPid: String?,
    val titleFingerprint: String? = null,
    val sourcePageUrl: String,
    val coverImageUrl: String?,
    val summaryMetadata: SummaryMetadata?,
    val candidates: List<PlaybackCandidate> = emptyList(),
    val bestPlaybackCandidate: PlaybackCandidate? = null
)
