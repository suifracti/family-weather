/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.cctv

import android.content.Context
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.breezyweather.domain.multisource.model.EpisodeQuery
import org.breezyweather.domain.multisource.model.EpisodeResolutionResult
import org.breezyweather.domain.multisource.model.VideoQualityEvidenceStatus
import java.io.IOException
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChinaWeatherVideoService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        const val LIANBO_JSONP_URL = "https://www.weather.com.cn/pubm/lianbo_3m.htm"
        const val OFFICIAL_HOME_URL = "https://www.weather.com.cn/video/ylist.shtml"
        const val SOURCE_ID = "weather_com_cn"
        const val PROGRAM_LIANBO = "CHINA_WEATHER_LIANBO"
        const val LIANBO_TYPE = "3M"
        private val JSONP_PATTERN = Pattern.compile("getLbDatas\\((.*)\\)", Pattern.DOTALL)
        private val DATE_8_DIGITS = Pattern.compile("\\b(\\d{4})(\\d{2})(\\d{2})\\b")
        private val DATE_HYPHEN = Pattern.compile("\\b(\\d{4})[-/](\\d{2})[-/](\\d{2})\\b")

        @Volatile
        private var INSTANCE: ChinaWeatherVideoService? = null

        fun getInstance(context: Context): ChinaWeatherVideoService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChinaWeatherVideoService(
                    OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .build()
                ).also { INSTANCE = it }
            }
        }
    }

    fun fetchLatestLianboEpisode(nowEpochMs: Long = System.currentTimeMillis()): CctvEpisode {
        val request = Request.Builder()
            .url(LIANBO_JSONP_URL)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android; Mobile)")
            .header("Referer", OFFICIAL_HOME_URL)
            .build()
        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("China Weather list: HTTP ${response.code}")
            parseLatestLianboEpisode(response.body.string(), nowEpochMs)
        }
    }

    /**
     * Identity belongs to this catalogue item and column, never to another
     * broadcaster's same-date programme. pubDate/updateTime are publication
     * metadata and cannot establish the programme date.
     */
    fun parseLatestLianboEpisode(raw: String, nowEpochMs: Long = System.currentTimeMillis()): CctvEpisode {
        val wrapper = Regex("\\s*getLbDatas\\((.*)\\)\\s*;?\\s*", RegexOption.DOT_MATCHES_ALL)
            .matchEntire(raw) ?: throw IOException("Invalid China Weather list wrapper")
        val json = try {
            Json.parseToJsonElement(wrapper.groupValues[1]) as? JsonObject
                ?: throw IOException("Invalid China Weather list")
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid China Weather list", error)
        }
        val status = json.string("status")
        if (status.isNotEmpty() && status != "200") throw IOException("China Weather list status: $status")
        val items = json["data"] as? JsonArray ?: throw IOException("Missing China Weather list data")
        val todayDateCode = SimpleDateFormat("yyyyMMdd", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(java.util.Date(nowEpochMs))
        val candidates = items.mapNotNull { value ->
            val item = value as? JsonObject ?: return@mapNotNull null
            val title = item.string("title").trim()
            val normalizedTitle = title.replace(Regex("[《》\\s]"), "")
            if (!normalizedTitle.startsWith("新闻联播天气预报")) return@mapNotNull null
            val type = item.string("type").trim()
            if (type.isNotEmpty() && type != LIANBO_TYPE) return@mapNotNull null
            val id = item.string("id").trim()
            val hasItemId = id.toLongOrNull()?.let { it > 0 && id.all(Char::isDigit) } == true
            val mediaUrl = item.string("url").trim()
                .takeIf(CctvUrlValidator::isValidChinaWeatherMediaUrl)
            val titleDates = Regex("(20\\d{2})[-/]?(\\d{2})[-/]?(\\d{2})(?!\\d)")
                .findAll(title)
                .map { it.groupValues.drop(1).joinToString("") }
                .filter(::isValidDateCode)
                .distinct().toList()
            val titleDate = titleDates.singleOrNull()
            val pathDate = mediaUrl?.let {
                Regex("/video/(20\\d{2})/(\\d{1,2})/(\\d{1,2})/").find(it)
            }?.let {
                it.groupValues[1] + it.groupValues[2].padStart(2, '0') + it.groupValues[3].padStart(2, '0')
            }?.takeIf(::isValidDateCode)
            val identityStatus = when {
                titleDates.size > 1 || (titleDate != null && titleDate > todayDateCode) ||
                    (titleDate != null && pathDate != null && titleDate != pathDate) ->
                    CctvIdentityStatus.CONFLICT
                titleDate != null && hasItemId && type == LIANBO_TYPE && mediaUrl != null ->
                    CctvIdentityStatus.CONFIRMED_DATE
                else -> CctvIdentityStatus.UNKNOWN
            }
            val episodeDate = titleDate?.takeIf { identityStatus == CctvIdentityStatus.CONFIRMED_DATE }
                ?.let { "${it.substring(0, 4)}-${it.substring(4, 6)}-${it.substring(6, 8)}" }
            val officialPage = if (hasItemId) {
                "https://www.weather.com.cn/video/yubao.shtml?globalid=$id"
            } else OFFICIAL_HOME_URL
            val episode = CctvEpisode(
                id = id,
                title = title,
                url = officialPage,
                uploadTime = item.string("pubDate"),
                coverUrl = item.string("pic"),
                isToday = false,
                // The list summary repeats its title/date, rather than a spoken-content brief.
                editorialBrief = null,
                episodeDate = episodeDate,
                identityStatus = identityStatus,
                officialEpisodeUrl = officialPage,
                checkedAtEpochMs = nowEpochMs,
                sourceId = SOURCE_ID,
                program = PROGRAM_LIANBO,
                sourceType = type,
                mediaUrl = mediaUrl,
            ).refreshedFor(nowEpochMs)
            titleDates.maxOrNull().orEmpty() to episode
        }
        // Order by programme date; a conflicting latest item stays visibly
        // conflicting instead of silently becoming an older successful item.
        return candidates.maxByOrNull { it.first }?.second ?: CctvEpisode(
            id = "",
            title = "新闻联播天气预报",
            url = OFFICIAL_HOME_URL,
            uploadTime = "",
            coverUrl = "",
            isToday = false,
            identityStatus = CctvIdentityStatus.UNKNOWN,
            officialEpisodeUrl = OFFICIAL_HOME_URL,
            checkedAtEpochMs = nowEpochMs,
            sourceId = SOURCE_ID,
            program = PROGRAM_LIANBO,
            sourceType = LIANBO_TYPE,
        )
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun isValidDateCode(value: String): Boolean {
        val position = ParsePosition(0)
        val parsed = SimpleDateFormat("yyyyMMdd", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            isLenient = false
        }.parse(value, position)
        return parsed != null && position.index == value.length
    }

    /**
     * Discovers China Weather episode with strict fail-closed identity resolution.
     */
    fun discoverEpisode(query: EpisodeQuery): EpisodeResolutionResult? {
        val request = Request.Builder()
            .url(LIANBO_JSONP_URL)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "http://www.weather.com.cn/video/ylist.shtml")
            .build()

        return try {
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val raw = response.body.string()
            resolveEpisodeFromJsonp(raw, query)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses the JSONP response from China Weather with strict fail-closed episode identity.
     *
     * Rules:
     * - EXACT_EPISODE: requestedEpisodeDate == resolvedEpisodeDate MUST hold.
     *   If target date is missing, returns null (or episodeIdentityMatched = false).
     *   STRICTLY FORBIDDEN to fall back to latest or any other date!
     * - LATEST_EPISODE: returns the newest available episode from China Weather.
     * - 20260917 retains PROBED_VERIFIED_1080P.
     *   Dynamic/future unprobed episodes start as CHINA_WEATHER_DIRECT_MP4 until runtime verification.
     */
    fun resolveEpisodeFromJsonp(
        raw: String,
        query: EpisodeQuery
    ): EpisodeResolutionResult? {
        val matcher = JSONP_PATTERN.matcher(raw)
        if (!matcher.find()) return null
        val jsonStr = matcher.group(1)?.trim() ?: return null
        val json = try {
            Json.parseToJsonElement(jsonStr).jsonObject
        } catch (e: Exception) {
            return null
        }
        val dataArray = json["data"]?.jsonArray ?: return null
        if (dataArray.isEmpty()) return null

        when (query) {
            is EpisodeQuery.Exact -> {
                val targetDate = query.dateCode
                for (itemElement in dataArray) {
                    val item = itemElement.jsonObject
                    val title = item["title"]?.jsonPrimitive?.content ?: ""
                    val pubDate = item["pubDate"]?.jsonPrimitive?.content ?: ""
                    val itemDateCode = extractDateCode(title) ?: extractDateCode(pubDate)
                    if (itemDateCode == targetDate) {
                        val url = item["url"]?.jsonPrimitive?.content ?: ""
                        if (url.startsWith("http")) {
                            val quality = if (targetDate == "20260917") {
                                VideoQualityEvidenceStatus.PROBED_VERIFIED_1080P
                            } else {
                                VideoQualityEvidenceStatus.CHINA_WEATHER_DIRECT_MP4
                            }
                            return EpisodeResolutionResult(
                                requestedEpisodeDate = targetDate,
                                resolvedEpisodeDate = itemDateCode,
                                episodeIdentityMatched = true,
                                mp4Url = url,
                                qualityEvidence = quality
                            )
                        }
                    }
                }
                // Strict fail-closed: target date was NOT found in China Weather
                // Must return null, DO NOT fallback to latest!
                return null
            }

            is EpisodeQuery.Latest -> {
                val latest = dataArray[0].jsonObject
                val title = latest["title"]?.jsonPrimitive?.content ?: ""
                val pubDate = latest["pubDate"]?.jsonPrimitive?.content ?: ""
                val resolvedDate = extractDateCode(title) ?: extractDateCode(pubDate)
                val url = latest["url"]?.jsonPrimitive?.content ?: ""
                if (url.startsWith("http")) {
                    val quality = if (resolvedDate == "20260917") {
                        VideoQualityEvidenceStatus.PROBED_VERIFIED_1080P
                    } else {
                        VideoQualityEvidenceStatus.CHINA_WEATHER_DIRECT_MP4
                    }
                    return EpisodeResolutionResult(
                        requestedEpisodeDate = null,
                        resolvedEpisodeDate = resolvedDate,
                        episodeIdentityMatched = true,
                        mp4Url = url,
                        qualityEvidence = quality
                    )
                }
                return null
            }
        }
    }

    /**
     * Discovers China Weather direct MP4 URL.
     * Uses EXACT_EPISODE if a date is present in dateOrHint; otherwise uses LATEST_EPISODE.
     */
    fun discoverMp4Url(dateOrHint: String? = null): String? {
        val targetDateCode = extractDateCode(dateOrHint)
        val query = if (targetDateCode != null) {
            EpisodeQuery.Exact(targetDateCode)
        } else {
            EpisodeQuery.Latest
        }
        return discoverEpisode(query)?.mp4Url
    }

    /**
     * Parses the JSONP response from China Weather to find the MP4 direct link.
     */
    fun parseMp4FromJsonp(
        raw: String,
        targetDateCode: String? = null,
        matchLatestIfDateNotFound: Boolean = false
    ): String? {
        val query = if (targetDateCode != null) {
            EpisodeQuery.Exact(targetDateCode)
        } else {
            EpisodeQuery.Latest
        }
        val result = resolveEpisodeFromJsonp(raw, query)
        if (result != null) return result.mp4Url

        if (matchLatestIfDateNotFound) {
            return resolveEpisodeFromJsonp(raw, EpisodeQuery.Latest)?.mp4Url
        }
        return null
    }

    fun discoverEpisodeObservable(query: EpisodeQuery): Observable<EpisodeResolutionResult> {
        return Observable.fromCallable {
            discoverEpisode(query) ?: throw NoSuchElementException("Episode not found for query: $query")
        }.subscribeOn(Schedulers.io())
    }

    fun discoverMp4UrlObservable(dateOrHint: String? = null): Observable<String> {
        return Observable.fromCallable {
            discoverMp4Url(dateOrHint) ?: throw NoSuchElementException("Failed to discover China Weather MP4 URL")
        }.subscribeOn(Schedulers.io())
    }

    /**
     * Extracts an 8-digit date code ("YYYYMMDD") from a string.
     */
    fun extractDateCode(input: String?): String? {
        if (input.isNullOrBlank()) return null
        val m8 = DATE_8_DIGITS.matcher(input)
        if (m8.find()) {
            return "${m8.group(1)}${m8.group(2)}${m8.group(3)}"
        }
        val mh = DATE_HYPHEN.matcher(input)
        if (mh.find()) {
            return "${mh.group(1)}${mh.group(2)}${mh.group(3)}"
        }
        return null
    }
}
