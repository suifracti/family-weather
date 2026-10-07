/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.cctv

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CctvWeatherService @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private var cachedEpisode: CctvEpisode? = null
    private var lastFetchTime: Long = 0
    private val chinaWeatherService = ChinaWeatherVideoService(okHttpClient)

    fun getLatestEpisode(forceRefresh: Boolean = false): Observable<CctvEpisode> {
        val now = System.currentTimeMillis()
        // Acceptance-only archived official catalogue. Absent by default; never changes release discovery.
        if (org.breezyweather.BuildConfig.DEBUG) {
            val fixture = java.io.File(org.breezyweather.BreezyWeather.instance.filesDir, "sensevoice-acceptance-catalogue.jsonp")
            if (fixture.isFile) return Observable.fromCallable {
                chinaWeatherService.parseLatestLianboEpisode(fixture.readText(), now)
            }.subscribeOn(Schedulers.io())
        }
        if (!forceRefresh && cachedEpisode != null && (now - lastFetchTime < 15 * 60 * 1000)) {
            return Observable.just(cachedEpisode!!.refreshedFor(now))
        }

        return Observable.fromCallable {
            try {
                val episode = chinaWeatherService.fetchLatestLianboEpisode(now)
                cachedEpisode = episode
                lastFetchTime = now
                episode
            } catch (error: Throwable) {
                val cached = cachedEpisode
                if (cached != null) {
                    cached.refreshedFor(now).copy(
                        checkedAtEpochMs = now,
                        lookupStatus = CctvLookupStatus.FAILED,
                    )
                } else {
                    throw error
                }
            }
        }.subscribeOn(Schedulers.io())
    }

    internal fun parseLatestResponse(body: String, nowEpochMs: Long): CctvEpisode =
        chinaWeatherService.parseLatestLianboEpisode(body, nowEpochMs)

    /**
     * Legacy offline parser retained for existing evidence/fixtures; the default
     * network discovery uses the official China Weather list above.
     * Upload time is retained for
     * diagnostics, but it is never used as the episode/program date.
     */
    internal fun parseSearchResponse(body: String, nowEpochMs: Long = System.currentTimeMillis()): CctvEpisode {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
            .parseToJsonElement(body)
            .jsonObject
        val list = json["list"]?.jsonArray
            ?: throw RuntimeException("No video list found in CCTV response")
        if (list.isEmpty()) return unknownEpisode(nowEpochMs)

        val candidates = (0 until list.size).mapNotNull { index ->
            val item = list[index].jsonObject
            val title = cleanTitle(item.string("all_title").ifBlank { item.string("title") })
            if (!title.contains("晚间天气预报")) return@mapNotNull null

            val titleDate = dateCodeFrom(title)
            val pageUrl = item.string("urllink").trim()
            val urlDate = dateCodeFrom(pageUrl)
            val structuredDate = listOf("episodeDate", "programDate", "videoDate", "date")
                .asSequence()
                .map { key -> item.string(key).trim() }
                .mapNotNull(::dateCodeFrom)
                .firstOrNull()
            val identityDates = listOfNotNull(titleDate, urlDate, structuredDate).distinct()
            val identityStatus = when {
                identityDates.size > 1 -> CctvIdentityStatus.CONFLICT
                identityDates.size == 1 -> CctvIdentityStatus.CONFIRMED_DATE
                else -> CctvIdentityStatus.UNKNOWN
            }
            SearchCandidate(
                item = item,
                title = title.ifBlank { "《晚间天气预报》" },
                pageUrl = pageUrl,
                dateCode = identityDates.singleOrNull(),
                identityStatus = identityStatus,
            )
        }

        val candidate = candidates
            .filter { it.identityStatus == CctvIdentityStatus.CONFIRMED_DATE }
            .maxByOrNull { it.dateCode.orEmpty() }
            ?: candidates.firstOrNull()

        if (candidate == null) return unknownEpisode(nowEpochMs)
        val officialUrl = candidate.pageUrl
            .takeIf { CctvUrlValidator.isValidOfficialCctvUrl(it) }
            ?: OFFICIAL_HOME_URL
        val episodeDate = candidate.dateCode?.let(::formatDateIso)
            .takeIf { candidate.identityStatus == CctvIdentityStatus.CONFIRMED_DATE }
        val uploadTime = candidate.item.string("uploadtime").trim()
        val rawBrief = candidate.item.string("brief")
            .ifBlank { candidate.item.string("tag") }
            .trim()
        val editorialBrief = rawBrief
            .replace("晚间天气预报", "")
            .replace("《", "")
            .replace("》", "")
            .trim()
            .ifEmpty { null }
        val isToday = episodeDate == todayIso(nowEpochMs) &&
            candidate.identityStatus == CctvIdentityStatus.CONFIRMED_DATE

        return CctvEpisode(
            id = candidate.item.string("id"),
            title = candidate.title,
            url = officialUrl,
            uploadTime = uploadTime,
            coverUrl = candidate.item.string("imglink"),
            isToday = isToday,
            editorialBrief = editorialBrief,
            episodeDate = episodeDate,
            identityStatus = candidate.identityStatus,
            officialEpisodeUrl = officialUrl,
            checkedAtEpochMs = nowEpochMs,
            lookupStatus = CctvLookupStatus.SUCCESS,
        )
    }

    private data class SearchCandidate(
        val item: JsonObject,
        val title: String,
        val pageUrl: String,
        val dateCode: String?,
        val identityStatus: CctvIdentityStatus,
    )

    private fun unknownEpisode(nowEpochMs: Long): CctvEpisode = CctvEpisode(
        id = "",
        title = "《晚间天气预报》",
        url = OFFICIAL_HOME_URL,
        uploadTime = "",
        coverUrl = "",
        isToday = false,
        editorialBrief = null,
        episodeDate = null,
        identityStatus = CctvIdentityStatus.UNKNOWN,
        officialEpisodeUrl = OFFICIAL_HOME_URL,
        checkedAtEpochMs = nowEpochMs,
        lookupStatus = CctvLookupStatus.SUCCESS,
    )

    private fun cleanTitle(value: String): String = value
        .replace(Regex("<[^>]+>"), "")
        .trim()

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun dateCodeFrom(value: String?): String? {
        if (value.isNullOrBlank()) return null
        Regex("(20\\d{2})[-/]?(\\d{2})[-/]?(\\d{2})").find(value)?.let { match ->
            return "${match.groupValues[1]}${match.groupValues[2]}${match.groupValues[3]}"
        }
        return null
    }

    private fun formatDateIso(dateCode: String): String =
        "${dateCode.substring(0, 4)}-${dateCode.substring(4, 6)}-${dateCode.substring(6, 8)}"

    private fun todayIso(nowEpochMs: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }.format(Date(nowEpochMs))

    companion object {
        const val OFFICIAL_HOME_URL = "https://tv.cctv.com"
        const val DEFAULT_OFFICIAL_HOME_URL = ChinaWeatherVideoService.OFFICIAL_HOME_URL

        @Volatile
        private var INSTANCE: CctvWeatherService? = null

        fun getInstance(context: android.content.Context): CctvWeatherService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CctvWeatherService(
                    OkHttpClient.Builder()
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                ).also { INSTANCE = it }
            }
        }
    }
}
