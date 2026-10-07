/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource.hourly.adapter

import okhttp3.OkHttpClient
import okhttp3.Request
import org.breezyweather.domain.multisource.model.EvidenceAvailability
import org.breezyweather.domain.multisource.model.ForecastEvidence
import org.breezyweather.domain.multisource.model.PrecipitationProbabilityValue
import org.breezyweather.domain.multisource.model.PrecipitationValue
import org.breezyweather.domain.multisource.model.SourceIdentity
import org.breezyweather.domain.multisource.model.UnderlyingModel
import org.breezyweather.domain.multisource.model.WeatherCondition
import org.breezyweather.domain.multisource.model.WeatherProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class QWeatherHourlyAdapter(
    private val okHttpClient: OkHttpClient = OkHttpClient()
) {

    data class FetchResult(
        val evidences: List<ForecastEvidence>,
        val succeeded: Boolean,
        val hadSuccessfulResponse: Boolean,
        val diagnostics: FetchDiagnostics = FetchDiagnostics(false, emptyList(), emptyList()),
    )

    data class FetchDiagnostics(
        val usesDedicatedHost: Boolean,
        val attempts: List<AttemptDiagnostics>,
        val retainedHours: List<String>,
    )

    data class AttemptDiagnostics(
        val apiVersion: String,
        val httpStatus: Int?,
        val responseHours: List<String>,
        val hoursWithProbability: List<String>,
        val hoursWithAmount: List<String>,
    )

    /**
     * Fetches hourly forecast from QWeather API when a valid API key is present.
     * Uses new /weather/v1/hourly live endpoint when customApiHost or modern schema is targeted,
     * with graceful fallback to legacy /v7/weather/24h if v1 is unavailable.
     * Retains native API fields strictly without synthesizing missing values.
     */
    fun fetchHourlyEvidences(
        apiKey: String?,
        latitude: Double,
        longitude: Double,
        timeZoneId: String = "Asia/Shanghai",
        targetDateLocal: String,
        customApiHost: String? = null
    ): List<ForecastEvidence> {
        return fetchHourlyEvidencesDetailed(
            apiKey = apiKey,
            latitude = latitude,
            longitude = longitude,
            timeZoneId = timeZoneId,
            targetDateLocal = targetDateLocal,
            customApiHost = customApiHost
        ).evidences
    }

    /**
     * Same request path as [fetchHourlyEvidences], but keeps enough outcome
     * information for the UI to distinguish an empty response from a request
     * failure. Raw SDK/HTTP errors are intentionally not returned to callers.
     */
    fun fetchHourlyEvidencesDetailed(
        apiKey: String?,
        latitude: Double,
        longitude: Double,
        timeZoneId: String = "Asia/Shanghai",
        targetDateLocal: String,
        customApiHost: String? = null
    ): FetchResult {
        return fetchHourlyEvidencesForDatesDetailed(
            apiKey, latitude, longitude, timeZoneId, setOf(targetDateLocal), customApiHost
        )
    }

    fun fetchHourlyEvidencesForDatesDetailed(
        apiKey: String?,
        latitude: Double,
        longitude: Double,
        timeZoneId: String = "Asia/Shanghai",
        targetDatesLocal: Set<String>,
        customApiHost: String? = null,
    ): FetchResult {
        if (apiKey.isNullOrBlank() || customApiHost.isNullOrBlank()) {
            return FetchResult(emptyList(), succeeded = false, hadSuccessfulResponse = false)
        }

        val baseHost = customApiHost?.trimEnd('/') ?: "https://devapi.qweather.com"
        val dedicatedHost = customApiHost?.isNotBlank() == true &&
            !baseHost.contains("devapi.qweather.com", ignoreCase = true)
        val canonicalLocationId = org.breezyweather.domain.multisource.location.WeatherCoordinateSerializer.toCanonicalLocationId(latitude, longitude)

        var hadSuccessfulResponse = false
        val attempts = mutableListOf<AttemptDiagnostics>()

        // 1. Try New /weather/v1/hourly endpoint first (path: /{latitude}/{longitude}, header: X-QW-Api-Key)
        val hourlyPath = org.breezyweather.domain.multisource.location.WeatherCoordinateSerializer.toQWeatherHourlyPath(latitude, longitude)
        val v1Url = "$baseHost/weather/v1/hourly/$hourlyPath?hours=72&localTime=true&lang=zh"
        val v1Request = Request.Builder()
            .url(v1Url)
            .header("X-QW-Api-Key", apiKey)
            .header("User-Agent", "BreezyWeather-Family/6.2.2")
            .build()

        try {
            val v1Response = okHttpClient.newCall(v1Request).execute()
            v1Response.use { response ->
                val bodyString = response.body?.string().orEmpty()
                attempts += inspectResponse("v1", response.code, bodyString, timeZoneId, true)
                if (response.isSuccessful) {
                    hadSuccessfulResponse = true
                    val parsed = targetDatesLocal.flatMap { targetDateLocal -> parseV1HourlyResponse(
                        bodyString = bodyString,
                        canonicalLocationId = canonicalLocationId,
                        targetDateLocal = targetDateLocal,
                        timeZoneId = timeZoneId,
                        rawSourceUrl = v1Url
                    ) }
                    if (parsed.isNotEmpty()) {
                        return FetchResult(
                            parsed,
                            succeeded = true,
                            hadSuccessfulResponse = true,
                            diagnostics = FetchDiagnostics(dedicatedHost, attempts, hourKeys(parsed, timeZoneId)),
                        )
                    }
                }
            }
        } catch (_: Exception) {
            attempts += AttemptDiagnostics("v1", null, emptyList(), emptyList(), emptyList())
        }

        // Do not substitute a city v7 product for the requested point forecast.
        return FetchResult(
            emptyList(),
            succeeded = false,
            hadSuccessfulResponse = hadSuccessfulResponse,
            diagnostics = FetchDiagnostics(dedicatedHost, attempts, emptyList()),
        )
    }

    private fun inspectResponse(
        apiVersion: String,
        httpStatus: Int,
        bodyString: String,
        timeZoneId: String,
        isV1: Boolean,
    ): AttemptDiagnostics {
        val rows = runCatching {
            val root = Json.parseToJsonElement(bodyString).jsonObject
            (if (isV1) root["hours"] ?: root["hourly"] else root["hourly"])?.jsonArray.orEmpty()
        }.getOrDefault(emptyList())
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone(timeZoneId)
        }
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mmXXX", Locale.CHINA)
        data class Inspected(val hour: String, val probability: Boolean, val amount: Boolean)
        val inspected = rows.mapNotNull { element ->
            val item = element.jsonObject
            val rawTime = (item["forecastTime"] ?: item["fxTime"])?.jsonPrimitive?.content ?: return@mapNotNull null
            val date = runCatching { isoFormat.parse(rawTime) }.getOrNull() ?: return@mapNotNull null
            val precipitation = item["precipitation"]?.jsonObject
            Inspected(
                hour = formatter.format(date),
                probability = if (isV1) precipitation?.get("probability") != null || item["probability"] != null else item["pop"] != null,
                amount = if (isV1) precipitation?.get("amount") != null || item["precip"] != null else item["precip"] != null,
            )
        }
        return AttemptDiagnostics(
            apiVersion = apiVersion,
            httpStatus = httpStatus,
            responseHours = inspected.map { it.hour },
            hoursWithProbability = inspected.filter { it.probability }.map { it.hour },
            hoursWithAmount = inspected.filter { it.amount }.map { it.hour },
        )
    }

    private fun hourKeys(evidences: List<ForecastEvidence>, timeZoneId: String): List<String> {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone(timeZoneId)
        }
        return evidences.map { formatter.format(Date(it.validFromEpochMs)) }.distinct().sorted()
    }

    /**
     * Parses the new QWeather /weather/v1/hourly response.
     * In this schema, precipitation.probability is a normalized ratio in [0.0, 1.0].
     * (e.g. 0.31 means 31%).
     */
    fun parseV1HourlyResponse(
        bodyString: String,
        canonicalLocationId: String,
        targetDateLocal: String,
        timeZoneId: String = "Asia/Shanghai",
        rawSourceUrl: String = "qweather/v1/hourly"
    ): List<ForecastEvidence> {
        val json = try {
            Json.parseToJsonElement(bodyString).jsonObject
        } catch (e: Exception) {
            return emptyList()
        }

        // Support both "hours" and "hourly" root arrays
        val hoursArray = (json["hours"] ?: json["hourly"])?.jsonArray ?: return emptyList()
        val nowEpochMs = System.currentTimeMillis()

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone(timeZoneId)
        }
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mmXXX", Locale.CHINA)

        val results = mutableListOf<ForecastEvidence>()
        val sourceIdentity = SourceIdentity.resolve(
            provider = WeatherProvider.QWEATHER,
            model = UnderlyingModel.QWEATHER_AGGREGATED,
            verifiedPhysicalModel = null // Composite opaque provider
        )

        for (element in hoursArray) {
            val item = element.jsonObject
            val fxTime = (item["forecastTime"] ?: item["fxTime"])?.jsonPrimitive?.content ?: continue
            val date: Date = try {
                isoFormat.parse(fxTime) ?: continue
            } catch (e: Exception) {
                continue
            }

            val localDateStr = sdf.format(date)
            if (localDateStr != targetDateLocal) {
                continue
            }

            val validFromEpochMs = date.time
            val validToEpochMs = validFromEpochMs + 3600_000L

            // 1. Condition
            val condObj = item["condition"]?.jsonObject
            val text = condObj?.get("text")?.jsonPrimitive?.content
                ?: item["text"]?.jsonPrimitive?.content
                ?: "未知"
            val code = condObj?.get("code")?.jsonPrimitive?.content
                ?: item["icon"]?.jsonPrimitive?.content
                ?: ""

            // 2. Precipitation
            val precipObj = item["precipitation"]?.jsonObject
            val precipitationType = precipObj?.get("type")?.jsonPrimitive?.content
                ?: item["precipitationType"]?.jsonPrimitive?.content
            val condition = WeatherCondition.fromProvider(
                text = text,
                code = code,
                explicitType = precipitationType
            )
            val amountVal: Double? = precipObj?.get("amount")?.jsonObject?.get("value")?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: precipObj?.get("amount")?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: item["precip"]?.jsonPrimitive?.content?.toDoubleOrNull()

            val precipValue = amountVal?.let {
                PrecipitationValue.available(it.coerceAtLeast(0.0))
            } ?: PrecipitationValue.unavailable()

            val intensityVal: Double? = precipObj?.get("intensity")?.jsonObject?.get("value")?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: precipObj?.get("intensity")?.jsonPrimitive?.content?.toDoubleOrNull()
            val intensityValue = intensityVal?.let {
                PrecipitationValue.available(it.coerceAtLeast(0.0), "mm/h")
            } ?: PrecipitationValue.unavailable()

            // 3. Probability: in v1 schema, probability is a ratio [0.0, 1.0]
            val probVal: Double? = precipObj?.get("probability")?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: item["probability"]?.jsonPrimitive?.content?.toDoubleOrNull()

            val popValue = if (probVal != null) {
                // Canonical ratio 0.0~1.0 -> 0.31 becomes 31%
                PrecipitationProbabilityValue.fromRatio(probVal, "QWeather /weather/v1/hourly raw:$probVal")
            } else {
                PrecipitationProbabilityValue.unavailable()
            }

            results.add(
                ForecastEvidence(
                    canonicalLocationId = canonicalLocationId,
                    sourceIdentity = sourceIdentity,
                    issuedAtEpochMs = 0L,
                    fetchedAtEpochMs = nowEpochMs,
                    temperatureAtEpochMs = date.time,
                    temperatureCelsius = (item["temperature"] as? JsonObject)?.get("value")?.jsonPrimitive?.content?.toDoubleOrNull()
                        ?.takeIf { it.isFinite() && it in -100.0..70.0 &&
                            ((item["temperature"] as? JsonObject)?.get("unit")?.jsonPrimitive?.content ?: "°C") in listOf("°C", "℃") },
                    spatialResolutionDetail = "和风约1公里网格预报，非果园实测",
                    temporalResolutionDetail = "逐小时预报；降水累计边界未提供，不参与严格时段比较",
                    precipitationPeriodKnown = false,
                    validFromEpochMs = validFromEpochMs,
                    validToEpochMs = validToEpochMs,
                    modelRunInitializationEpochMs = null,
                    modelRunId = null,
                    weatherCondition = condition,
                    precipitationProbability = popValue,
                    precipitationAmount = precipValue,
                    precipitationIntensity = intensityValue,
                    probabilityEventDefinition = if (popValue.availability == EvidenceAvailability.AVAILABLE) "PROVIDER_DEFINED_OR_UNKNOWN" else null,
                    freshnessSeconds = 0L,
                    rawSource = rawSourceUrl,
                    provenanceDetail = "和风天气 QWeather v1 hourly"
                )
            )
        }

        return results
    }

    /**
     * Parses legacy QWeather /v7/weather/24h response.
     * In this schema, pop is an integer 0..100 (e.g. 31 means 31%).
     */
    fun parseV7HourlyResponse(
        bodyString: String,
        canonicalLocationId: String,
        targetDateLocal: String,
        timeZoneId: String = "Asia/Shanghai",
        rawSourceUrl: String = "qweather/v7/weather/24h"
    ): List<ForecastEvidence> {
        val json = try {
            Json.parseToJsonElement(bodyString).jsonObject
        } catch (e: Exception) {
            return emptyList()
        }

        val code = json["code"]?.jsonPrimitive?.content
        if (code != "200") {
            return emptyList()
        }

        val hourlyArray = json["hourly"]?.jsonArray ?: return emptyList()
        val nowEpochMs = System.currentTimeMillis()

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone(timeZoneId)
        }
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mmXXX", Locale.CHINA)

        val results = mutableListOf<ForecastEvidence>()
        val sourceIdentity = SourceIdentity.resolve(
            provider = WeatherProvider.QWEATHER,
            model = UnderlyingModel.QWEATHER_AGGREGATED,
            verifiedPhysicalModel = null // Composite opaque provider
        )

        for (element in hourlyArray) {
            val item = element.jsonObject
            val fxTime = item["fxTime"]?.jsonPrimitive?.content ?: continue
            val date: Date = try {
                isoFormat.parse(fxTime) ?: continue
            } catch (e: Exception) {
                continue
            }

            val localDateStr = sdf.format(date)
            if (localDateStr != targetDateLocal) {
                continue
            }

            val validFromEpochMs = date.time
            val validToEpochMs = validFromEpochMs + 3600_000L

            val precipStr = item["precip"]?.jsonPrimitive?.content
            val precipVal = precipStr?.toDoubleOrNull()?.let {
                PrecipitationValue.available(it.coerceAtLeast(0.0))
            } ?: PrecipitationValue.unavailable()

            // Legacy v7 pop: integer 0..100 (e.g. 31 means 31%)
            val legacyPopStr = item["pop"]?.jsonPrimitive?.content
            val popVal = legacyPopStr?.toDoubleOrNull()?.let { rawDouble ->
                if (rawDouble <= 1.0 && rawDouble > 0.0 && legacyPopStr.contains(".")) {
                    // Fractional float representation in v7
                    PrecipitationProbabilityValue.fromRatio(rawDouble, "QWeather v7 raw:$legacyPopStr")
                } else {
                    PrecipitationProbabilityValue.available(rawDouble.toInt())
                }
            } ?: PrecipitationProbabilityValue.unavailable()

            val text = item["text"]?.jsonPrimitive?.content ?: "未知"
            val icon = item["icon"]?.jsonPrimitive?.content ?: ""
            val condition = WeatherCondition.fromProvider(
                text = text,
                code = icon
            )

            results.add(
                ForecastEvidence(
                    canonicalLocationId = canonicalLocationId,
                    sourceIdentity = sourceIdentity,
                    issuedAtEpochMs = 0L,
                    fetchedAtEpochMs = nowEpochMs,
                    temperatureAtEpochMs = date.time,
                    temperatureCelsius = item["temp"]?.jsonPrimitive?.content?.toDoubleOrNull()
                        ?.takeIf { it.isFinite() && it in -100.0..70.0 },
                    spatialResolutionDetail = "和风 v7 城市预报，非果园点预报",
                    temporalResolutionDetail = "逐小时预报；降水累计边界未提供，不参与严格时段比较",
                    precipitationPeriodKnown = false,
                    validFromEpochMs = validFromEpochMs,
                    validToEpochMs = validToEpochMs,
                    modelRunInitializationEpochMs = null,
                    modelRunId = null,
                    weatherCondition = condition,
                    precipitationProbability = popVal,
                    precipitationAmount = precipVal,
                    precipitationIntensity = PrecipitationValue.unavailable(),
                    probabilityEventDefinition = if (popVal.availability == EvidenceAvailability.AVAILABLE) "PROVIDER_DEFINED_OR_UNKNOWN" else null,
                    freshnessSeconds = 0L,
                    rawSource = rawSourceUrl,
                    provenanceDetail = "和风天气 QWeather v7 24h"
                )
            )
        }

        return results
    }
}
