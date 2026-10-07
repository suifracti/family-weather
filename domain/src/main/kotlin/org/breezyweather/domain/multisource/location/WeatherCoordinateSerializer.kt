/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource.location

import java.util.Locale

/**
 * Authoritative coordinate serializer for multi-source weather and nowcast providers.
 *
 * Invariants:
 * 1. Internal canonical representation is always (latitude: Double, longitude: Double).
 * 2. QWeather Hourly v1 path contract is strictly: /weather/v1/hourly/{latitude}/{longitude}.
 * 3. QWeather Minutely v7 and general query location param is strictly: location={longitude},{latitude}.
 * 4. Open-Meteo query params are strictly: latitude={latitude}&longitude={longitude}.
 * 5. Breezy China / Caiyun parameter is strictly: {longitude},{latitude}.
 * 6. Canonical location identifier format: coord:%.4f,%.4f (latitude,longitude).
 */
object WeatherCoordinateSerializer {

    private fun formatCoord(value: Double): String {
        return String.format(Locale.US, "%.5f", value).trimEnd('0').trimEnd('.')
    }

    /**
     * Canonical ID for deduplication, evidence binding, and repository caching.
     * Guaranteed to produce distinct IDs for different configured worksites and county-reference locations.
     */
    fun toCanonicalLocationId(latitude: Double, longitude: Double): String {
        return "coord:${String.format(Locale.US, "%.4f", latitude)},${String.format(Locale.US, "%.4f", longitude)}"
    }

    /**
     * QWeather New /weather/v1/hourly/{latitude}/{longitude} endpoint path.
     * Example: 12.34/56.78
     */
    fun toQWeatherHourlyPath(latitude: Double, longitude: Double): String {
        return "${formatCoord(latitude)}/${formatCoord(longitude)}"
    }

    /**
     * QWeather Minutely (/v7/minutely/5m) and legacy v7 location parameter:
     * Format: longitude,latitude
     * Example: 56.78,12.34
     */
    fun toQWeatherLocationParam(latitude: Double, longitude: Double): String {
        return "${formatCoord(longitude)},${formatCoord(latitude)}"
    }

    /**
     * Open-Meteo query string fragment:
     * Format: latitude={latitude}&longitude={longitude}
     */
    fun toOpenMeteoQueryParams(latitude: Double, longitude: Double): String {
        return "latitude=${formatCoord(latitude)}&longitude=${formatCoord(longitude)}"
    }

    /**
     * Breezy China / Caiyun coordinate parameter:
     * Format: longitude,latitude
     * Example: 56.78,12.34
     */
    fun toBreezyChinaLocationParam(latitude: Double, longitude: Double): String {
        return "${formatCoord(longitude)},${formatCoord(latitude)}"
    }
}
