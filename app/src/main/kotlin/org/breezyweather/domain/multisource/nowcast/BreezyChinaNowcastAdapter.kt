/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource.nowcast

import org.breezyweather.domain.multisource.location.WeatherCoordinateSerializer
import org.breezyweather.domain.multisource.model.ForecastHorizonType
import org.breezyweather.domain.multisource.model.WeatherProvider

class BreezyChinaNowcastAdapter {

    /**
     * Adapts nowcast entries from Breezy China / Caiyun 2h radar extrapolation.
     */
    fun adaptNowcast(
        latitude: Double,
        longitude: Double,
        summaryText: String,
        minuteSteps: List<Pair<Long, Double>> = emptyList(),
        rawSourceUrl: String = "weatherbj.caiyunapp.com/v2.6/.../minutely.json"
    ): NowcastEvidence {
        val now = System.currentTimeMillis()
        val canonicalLocationId = WeatherCoordinateSerializer.toCanonicalLocationId(latitude, longitude)

        val intervals = minuteSteps.map { (epochMs, mm) ->
            NowcastInterval(
                validTimeEpochMs = epochMs,
                precipitationMm = mm,
                precipitationType = if (mm > 0.0) "rain" else null
            )
        }

        val validFrom = intervals.firstOrNull()?.validTimeEpochMs ?: now
        val validTo = intervals.lastOrNull()?.validTimeEpochMs ?: (now + 7200_000L)
        val maxPrecip = intervals.maxOfOrNull { it.precipitationMm }

        return NowcastEvidence(
            canonicalLocationId = canonicalLocationId,
            provider = WeatherProvider.BREEZY_CHINA,
            issuedAtEpochMs = now,
            validFromEpochMs = validFrom,
            validToEpochMs = validTo,
            horizonType = ForecastHorizonType.NOWCAST,
            summaryText = summaryText,
            intervals = intervals,
            maxPrecipitationMm = maxPrecip,
            rawSourceUrl = rawSourceUrl,
            provenanceDetail = "北京气象服务 / 彩云天气 2小时短临降水"
        )
    }
}
