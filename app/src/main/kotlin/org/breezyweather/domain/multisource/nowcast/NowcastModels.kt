/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource.nowcast

import org.breezyweather.domain.multisource.model.ForecastHorizonType
import org.breezyweather.domain.multisource.model.WeatherProvider

/**
 * A discrete nowcast time step (e.g. 5-minute radar extrapolation point).
 */
data class NowcastInterval(
    val validTimeEpochMs: Long,
    val precipitationMm: Double,
    val precipitationType: String? = null
)

/**
 * Standalone Nowcast evidence representing 0~2 hour short-term/minutely precipitation.
 *
 * Invariants:
 * 1. horizonType is strictly NOWCAST.
 * 2. Kept completely separate from tomorrow 00:00~24:00 hourly forecast evidences.
 * 3. Prohibits arithmetic averaging between different nowcast providers (e.g. QWeather vs Caiyun).
 */
data class NowcastEvidence(
    val canonicalLocationId: String,
    val provider: WeatherProvider,
    val issuedAtEpochMs: Long,
    val validFromEpochMs: Long,
    val validToEpochMs: Long,
    val horizonType: ForecastHorizonType = ForecastHorizonType.NOWCAST,
    val summaryText: String,
    val intervals: List<NowcastInterval>,
    val maxPrecipitationMm: Double?,
    val rawSourceUrl: String,
    val provenanceDetail: String
)
