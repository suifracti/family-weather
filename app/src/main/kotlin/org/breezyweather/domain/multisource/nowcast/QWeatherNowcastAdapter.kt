/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource.nowcast

import okhttp3.OkHttpClient
import okhttp3.Request
import org.breezyweather.domain.multisource.location.WeatherCoordinateSerializer
import org.breezyweather.domain.multisource.model.ForecastHorizonType
import org.breezyweather.domain.multisource.model.WeatherProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Locale

class QWeatherNowcastAdapter(
    private val okHttpClient: OkHttpClient = OkHttpClient()
) {

    /**
     * Fetches minutely 5-minute nowcast from QWeather API (/v7/minutely/5m).
     *
     * Invariant:
     * Parameter format is strictly location={longitude},{latitude} (lon,lat).
     * Serialized via WeatherCoordinateSerializer.toQWeatherLocationParam.
     */
    fun fetchNowcast(
        apiKey: String?,
        latitude: Double,
        longitude: Double,
        customApiHost: String? = null
    ): NowcastEvidence? {
        if (apiKey.isNullOrBlank()) return null

        val baseHost = customApiHost?.trimEnd('/') ?: "https://devapi.qweather.com"
        val locParam = WeatherCoordinateSerializer.toQWeatherLocationParam(latitude, longitude)
        val canonicalLocationId = WeatherCoordinateSerializer.toCanonicalLocationId(latitude, longitude)

        val url = "$baseHost/v7/minutely/5m?location=$locParam&key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .header("X-QW-Api-Key", apiKey)
            .header("User-Agent", "BreezyWeather-Family/6.2.2")
            .build()

        return try {
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body.string()
            parseMinutelyResponse(body, canonicalLocationId, url)
        } catch (e: Exception) {
            null
        }
    }

    fun parseMinutelyResponse(
        bodyString: String,
        canonicalLocationId: String,
        rawSourceUrl: String = "qweather/v7/minutely/5m"
    ): NowcastEvidence? {
        val json = try {
            Json.parseToJsonElement(bodyString).jsonObject
        } catch (e: Exception) {
            return null
        }

        val code = json["code"]?.jsonPrimitive?.content
        if (code != "200") return null

        val summary = json["summary"]?.jsonPrimitive?.content ?: "未来两小时降水预报"
        val minutelyArray = json["minutely"]?.jsonArray ?: return null

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mmXXX", Locale.CHINA)
        val nowEpochMs = System.currentTimeMillis()

        val intervals = mutableListOf<NowcastInterval>()
        var maxPrecip: Double? = null

        for (item in minutelyArray) {
            val obj = item.jsonObject
            val fxTime = obj["fxTime"]?.jsonPrimitive?.content ?: continue
            val date = try {
                isoFormat.parse(fxTime) ?: continue
            } catch (e: Exception) {
                continue
            }
            val precip = obj["precip"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            val type = obj["type"]?.jsonPrimitive?.content

            intervals.add(
                NowcastInterval(
                    validTimeEpochMs = date.time,
                    precipitationMm = precip,
                    precipitationType = type
                )
            )
            if (maxPrecip == null || precip > maxPrecip) {
                maxPrecip = precip
            }
        }

        if (intervals.isEmpty()) return null

        val validFrom = intervals.first().validTimeEpochMs
        val validTo = intervals.last().validTimeEpochMs

        return NowcastEvidence(
            canonicalLocationId = canonicalLocationId,
            provider = WeatherProvider.QWEATHER,
            issuedAtEpochMs = nowEpochMs,
            validFromEpochMs = validFrom,
            validToEpochMs = validTo,
            horizonType = ForecastHorizonType.NOWCAST,
            summaryText = summary,
            intervals = intervals,
            maxPrecipitationMm = maxPrecip,
            rawSourceUrl = rawSourceUrl,
            provenanceDetail = "和风天气 2小时分钟级降水 (5m)"
        )
    }
}
