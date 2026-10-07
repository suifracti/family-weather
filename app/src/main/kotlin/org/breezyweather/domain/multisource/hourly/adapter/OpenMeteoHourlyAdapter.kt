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
import org.breezyweather.domain.multisource.model.PrecipitationPhase
import org.breezyweather.domain.multisource.model.WeatherProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class OpenMeteoHourlyAdapter(
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://api.open-meteo.com/v1/forecast",
) {

    /**
     * Fetches live multi-model hourly forecast from Open-Meteo API
     * and maps each hourly slot for [targetDateLocal] into [ForecastEvidence].
     */
    fun fetchHourlyEvidences(
        latitude: Double,
        longitude: Double,
        timeZoneId: String = "Asia/Shanghai",
        targetDateLocal: String
    ): List<ForecastEvidence> {
        return fetchHourlyEvidencesForDates(latitude, longitude, timeZoneId, setOf(targetDateLocal))
    }

    fun fetchHourlyEvidencesForDates(
        latitude: Double,
        longitude: Double,
        timeZoneId: String = "Asia/Shanghai",
        targetDatesLocal: Set<String>,
        cmaOnly: Boolean = false,
    ): List<ForecastEvidence> {
        val modelsParam = if (cmaOnly) "cma_grapes_global" else "ecmwf_ifs025,ncep_gfs_global,dwd_icon_global,jma_gsm,best_match"
        val coordParams = org.breezyweather.domain.multisource.location.WeatherCoordinateSerializer.toOpenMeteoQueryParams(latitude, longitude)
        val url = "$baseUrl?" +
                "$coordParams" +
                "&models=$modelsParam" +
                "&hourly=temperature_2m,precipitation,precipitation_probability,weather_code" +
                "&temperature_unit=celsius&precipitation_unit=mm&timeformat=unixtime" +
                "&timezone=${timeZoneId}" +
                "&forecast_days=3"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "BreezyWeather-Family/6.2.2")
            .build()

        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Open-Meteo API error: HTTP ${response.code}")
            }
            parseHourlyResponse(
                bodyString = response.body.string(),
                latitude = latitude,
                longitude = longitude,
                timeZoneId = timeZoneId,
                targetDatesLocal = targetDatesLocal,
                requestedSingleModel = if (cmaOnly) UnderlyingModel.CMA_GRAPES else null,
            )
        }
    }

    /** Precipitation at timestamp t describes (t-1h, t], per the official API contract. */
    fun parseHourlyResponse(
        bodyString: String,
        latitude: Double,
        longitude: Double,
        timeZoneId: String = "Asia/Shanghai",
        targetDatesLocal: Set<String>,
        nowEpochMs: Long = System.currentTimeMillis(),
        requestedSingleModel: UnderlyingModel? = null,
    ): List<ForecastEvidence> {
        val json = Json.parseToJsonElement(bodyString).jsonObject
        val hourly = json["hourly"]?.jsonObject ?: return emptyList()
        val timeArray = hourly["time"]?.jsonArray ?: return emptyList()

        val canonicalLocationId = org.breezyweather.domain.multisource.location.WeatherCoordinateSerializer.toCanonicalLocationId(latitude, longitude)

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone(timeZoneId)
        }

        val targetIndexes = mutableListOf<Int>()
        for (i in 0 until timeArray.size) {
            val epochSec = timeArray[i].jsonPrimitive.longOrNull ?: continue
            val dateStr = sdf.format(Date(epochSec * 1000L - 3600_000L))
            if (dateStr in targetDatesLocal || sdf.format(Date(epochSec * 1000L)) in targetDatesLocal) {
                targetIndexes.add(i)
            }
        }

        if (targetIndexes.isEmpty()) return emptyList()

        val results = mutableListOf<ForecastEvidence>()

        val modelConfigs = listOf(
            ModelConfig(
                model = UnderlyingModel.ECMWF_IFS,
                apiKeySuffix = "ecmwf_ifs025",
                isPhysical = true,
                hasPoP = true,
                probabilitySourceDetail = "ECMWF IFS 全球模式；概率来自 IFS 集合预报",
                probabilityEventDefinition = "ECMWF 集合预报 1小时累计降水 > 0.1mm 成员占比"
            ),
            ModelConfig(
                model = UnderlyingModel.CMA_GRAPES,
                apiKeySuffix = "cma_grapes_global",
                isPhysical = true,
                hasPoP = false,
                probabilitySourceDetail = "中国气象局 GRAPES 全球模式；原生3小时数据经 Open-Meteo 转为小时值",
                probabilityEventDefinition = null
            ),
            ModelConfig(
                model = UnderlyingModel.NOAA_GFS,
                apiKeySuffix = "ncep_gfs_global",
                isPhysical = true,
                hasPoP = true,
                probabilitySourceDetail = "NOAA GFS 全球模式；概率来自 GEFS 集合预报",
                probabilityEventDefinition = "NOAA GEFS 1小时累计降水 > 0.1mm 成员占比"
            ),
            ModelConfig(
                model = UnderlyingModel.DWD_ICON,
                apiKeySuffix = "dwd_icon_global",
                isPhysical = true,
                hasPoP = true,
                probabilitySourceDetail = "DWD ICON 全球模式；概率来自 ICON-EPS 集合预报",
                probabilityEventDefinition = "DWD ICON-EPS 1小时累计降水 > 0.1mm 成员占比"
            ),
            ModelConfig(
                model = UnderlyingModel.JMA_GSM,
                apiKeySuffix = "jma_gsm",
                isPhysical = true,
                hasPoP = false,
                probabilitySourceDetail = "日本气象厅 GSM 全球模式",
                probabilityEventDefinition = null
            ),
            ModelConfig(
                model = UnderlyingModel.OPEN_METEO_BEST_MATCH,
                apiKeySuffix = "best_match",
                isPhysical = false,
                hasPoP = true,
                probabilitySourceDetail = "Open-Meteo Best Match 组合优选；具体上游未在响应中披露",
                probabilityEventDefinition = "PROVIDER_DEFINED_OR_UNKNOWN"
            )
        )

        for (config in modelConfigs.filter { requestedSingleModel == null || it.model == requestedSingleModel }) {
            val oldSuffix = when (config.model) {
                UnderlyingModel.NOAA_GFS -> "gfs_global"
                UnderlyingModel.DWD_ICON -> "icon_global"
                else -> config.apiKeySuffix
            }
            val actualSuffix = listOf(config.apiKeySuffix, oldSuffix).firstOrNull { suffix ->
                listOf("precipitation", "precipitation_probability", "weather_code").any {
                    hourly.containsKey("${it}_$suffix")
                }
            } ?: config.apiKeySuffix
            fun field(name: String): JsonArray? = (hourly["${name}_$actualSuffix"]
                ?: if (requestedSingleModel == config.model) hourly[name] else null) as? JsonArray
            val precipArray = field("precipitation")
            val popArray = field("precipitation_probability")
            val codeArray = field("weather_code")
            val temperatureArray = field("temperature_2m")
            val units = json["hourly_units"] as? kotlinx.serialization.json.JsonObject
            val temperatureUnit = (units?.get("temperature_2m_$actualSuffix") ?: units?.get("temperature_2m"))
                ?.jsonPrimitive?.content
            val celsiusUnit = temperatureUnit == null || temperatureUnit in listOf("°C", "℃", "celsius")

            val sourceIdentity = SourceIdentity.resolve(
                provider = WeatherProvider.OPEN_METEO,
                model = config.model,
                verifiedPhysicalModel = if (config.isPhysical) config.model else null
            )

            for (index in targetIndexes) {
                val epochSec = timeArray[index].jsonPrimitive.longOrNull ?: continue
                val validToEpochMs = epochSec * 1000L
                val validFromEpochMs = validToEpochMs - 3600_000L

                // 1. Precipitation Amount (mm)
                val precipVal = if (precipArray != null && index < precipArray.size) {
                    val mm = (precipArray[index] as? JsonPrimitive)?.doubleOrNull
                    if (mm != null && mm.isFinite() && mm >= 0.0) {
                        PrecipitationValue.available(mm)
                    } else {
                        PrecipitationValue.unavailable()
                    }
                } else {
                    PrecipitationValue.unavailable()
                }

                // 2. Precipitation Probability (%)
                val popVal = if (config.hasPoP && popArray != null && index < popArray.size) {
                    val pop = (popArray[index] as? JsonPrimitive)?.intOrNull
                    if (pop != null && pop in 0..100) {
                        PrecipitationProbabilityValue.available(pop)
                    } else {
                        PrecipitationProbabilityValue.unavailable()
                    }
                } else {
                    PrecipitationProbabilityValue.unavailable()
                }

                // 3. Weather Condition
                val condition = if (codeArray != null && index < codeArray.size) {
                    val wmo = (codeArray[index] as? JsonPrimitive)?.intOrNull
                    if (wmo != null) mapWmoToCondition(wmo) else null
                } else {
                    null
                }

                val temperature = temperatureArray?.getOrNull(index)?.let { (it as? JsonPrimitive)?.doubleOrNull }
                    ?.takeIf { celsiusUnit && it.isFinite() && it in -100.0..70.0 }
                if (temperature == null && precipVal.availability != EvidenceAvailability.AVAILABLE &&
                    popVal.availability != EvidenceAvailability.AVAILABLE && condition == null
                ) continue

                val provenance = "https://api.open-meteo.com/v1/forecast?models=$actualSuffix"

                results.add(
                    ForecastEvidence(
                        canonicalLocationId = canonicalLocationId,
                        sourceIdentity = sourceIdentity,
                        issuedAtEpochMs = 0L,
                        fetchedAtEpochMs = nowEpochMs,
                        temperatureCelsius = temperature,
                        temperatureAtEpochMs = validToEpochMs,
                        resolvedLatitude = json["latitude"]?.jsonPrimitive?.doubleOrNull.takeIf { requestedSingleModel != null },
                        resolvedLongitude = json["longitude"]?.jsonPrimitive?.doubleOrNull.takeIf { requestedSingleModel != null },
                        spatialResolutionDetail = if (config.model == UnderlyingModel.CMA_GRAPES) "约15公里网格（0.125°），非果园实测" else "模式网格预报，非果园实测；各模式网格不同",
                        temporalResolutionDetail = if (config.model == UnderlyingModel.CMA_GRAPES) "原生3小时，经插值提供逐小时值；模式每6小时更新" else "提供逐小时值；模式更新周期各异",
                        validFromEpochMs = validFromEpochMs,
                        validToEpochMs = validToEpochMs,
                        modelRunInitializationEpochMs = null,
                        modelRunId = null,
                        weatherCondition = condition,
                        precipitationProbability = popVal,
                        precipitationAmount = precipVal,
                        precipitationIntensity = precipVal, // Hourly amount mm equals mm/h
                        probabilityEventDefinition = if (popVal.availability == EvidenceAvailability.AVAILABLE) config.probabilityEventDefinition else null,
                        freshnessSeconds = 0L,
                        rawSource = provenance,
                        provenanceDetail = "${config.probabilitySourceDetail}；经 Open-Meteo 提供（CC BY 4.0）。" +
                            "降水量对应此前1小时；温度、天气码对应标注时刻。"
                    )
                )
            }
        }

        return results
    }

    private data class ModelConfig(
        val model: UnderlyingModel,
        val apiKeySuffix: String,
        val isPhysical: Boolean,
        val hasPoP: Boolean,
        val probabilitySourceDetail: String,
        val probabilityEventDefinition: String?
    )

    private fun mapWmoToCondition(code: Int): WeatherCondition {
        val (text, phase) = when (code) {
            0 -> "晴" to PrecipitationPhase.OTHER
            1, 2 -> "多云" to PrecipitationPhase.OTHER
            3 -> "阴" to PrecipitationPhase.OTHER
            45, 48 -> "雾" to PrecipitationPhase.OTHER
            51, 53, 55 -> "毛毛雨" to PrecipitationPhase.RAIN
            56, 57 -> "冻毛毛雨" to PrecipitationPhase.MIXED
            61 -> "小雨" to PrecipitationPhase.RAIN
            63 -> "中雨" to PrecipitationPhase.RAIN
            65 -> "大雨" to PrecipitationPhase.RAIN
            66, 67 -> "冻雨" to PrecipitationPhase.MIXED
            71, 73, 75, 77, 85, 86 -> "降雪" to PrecipitationPhase.SNOW
            80, 81, 82 -> "阵雨" to PrecipitationPhase.RAIN
            95 -> "雷雨" to PrecipitationPhase.RAIN
            96, 99 -> "雷雨伴冰雹" to PrecipitationPhase.MIXED
            else -> "天气现象 $code" to PrecipitationPhase.UNKNOWN
        }
        return WeatherCondition(
            text = text,
            code = code.toString(),
            isRainCondition = phase == PrecipitationPhase.RAIN,
            precipitationPhase = phase
        )
    }
}
