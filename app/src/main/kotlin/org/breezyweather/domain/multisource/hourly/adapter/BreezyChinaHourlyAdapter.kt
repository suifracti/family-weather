/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource.hourly.adapter

import org.breezyweather.domain.multisource.model.ForecastEvidence
import org.breezyweather.domain.multisource.model.PrecipitationProbabilityValue
import org.breezyweather.domain.multisource.model.PrecipitationValue
import org.breezyweather.domain.multisource.model.SourceIdentity
import org.breezyweather.domain.multisource.model.UnderlyingModel
import org.breezyweather.domain.multisource.model.WeatherCondition
import org.breezyweather.domain.multisource.model.WeatherProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class BreezyChinaHourlyAdapter {

    /**
     * Converts Breezy China hourly forecast entries for [targetDateLocal] into [ForecastEvidence].
     *
     * Invariants strictly enforced:
     * - weatherCondition is preserved from upstream weather text/code.
     * - precipitationAmount is marked UNAVAILABLE (regular hourly does not provide mm).
     * - precipitationProbability is marked UNAVAILABLE (regular hourly does not provide PoP).
     * - precipitationIntensity is marked UNAVAILABLE (2h nowcast does not extend to tomorrow).
     */
    fun adaptHourlyForecast(
        canonicalLocationId: String,
        hourlyWeatherEntries: List<HourlyWeatherEntry>,
        timeZoneId: String = "Asia/Shanghai",
        targetDateLocal: String,
        fetchedAtEpochMs: Long? = null,
        sourceUpdatedAtEpochMs: Long? = null,
    ): List<ForecastEvidence> {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone(timeZoneId)
        }

        val nowEpochMs = System.currentTimeMillis()
        val sourceIdentity = SourceIdentity.resolve(
            provider = WeatherProvider.BREEZY_CHINA,
            model = UnderlyingModel.UNKNOWN,
            verifiedPhysicalModel = null // Xiaomi's cache does not disclose per-field NWP lineage.
        )

        val results = mutableListOf<ForecastEvidence>()

        for (entry in hourlyWeatherEntries) {
            val dateStr = sdf.format(Date(entry.validFromEpochMs))
            if (dateStr != targetDateLocal) {
                continue
            }

            val condition = WeatherCondition.fromProvider(
                text = entry.weatherText,
                code = entry.weatherCode,
                explicitType = entry.weatherCode
            )

            results.add(
                ForecastEvidence(
                    canonicalLocationId = canonicalLocationId,
                    sourceIdentity = sourceIdentity,
                    issuedAtEpochMs = 0L,
                    fetchedAtEpochMs = fetchedAtEpochMs,
                    sourceUpdatedAtEpochMs = sourceUpdatedAtEpochMs,
                    temperatureCelsius = entry.temperatureCelsius?.takeIf { it.isFinite() && it in -100.0..70.0 },
                    temperatureAtEpochMs = entry.validFromEpochMs,
                    spatialResolutionDetail = "小米缓存参考；城市关联，果园网格范围未披露",
                    temporalResolutionDetail = "缓存小时标注；上游时区归属未核，不参与严格时段比较",
                    precipitationPeriodKnown = false,
                    validFromEpochMs = entry.validFromEpochMs,
                    validToEpochMs = entry.validFromEpochMs + 3600_000L,
                    modelRunInitializationEpochMs = null,
                    modelRunId = null,
                    weatherCondition = condition,
                    precipitationProbability = PrecipitationProbabilityValue.unavailable(),
                    precipitationAmount = PrecipitationValue.unavailable(),
                    precipitationIntensity = PrecipitationValue.unavailable(),
                    probabilityEventDefinition = null,
                    freshnessSeconds = 0L,
                    rawSource = "https://weatherapi.market.xiaomi.com/wtr-v3/weather/all (or intl gateway)",
                    provenanceDetail = "现有小米天气通道缓存；上游署名包含北京天气/彩云，但逐小时字段上游未披露"
                )
            )
        }

        return results
    }

    data class HourlyWeatherEntry(
        val validFromEpochMs: Long,
        val weatherText: String,
        val weatherCode: String?,
        val temperatureCelsius: Double? = null,
    )
}
