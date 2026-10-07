/*
 * This file is part of Breezy Weather.
 */

package breezyweather.domain.location.model

import org.breezyweather.domain.multisource.location.LocationAuthority
import org.breezyweather.domain.multisource.location.WeatherCoordinateSerializer
import java.util.TimeZone

/** Shared worksite identity and location-list rules used by the app and persistence layer. */
object OrchardLocationPolicy {
    private val worksite get() = LocationAuthority.WORKSITE

    fun isWorksite(latitude: Double, longitude: Double): Boolean =
        WeatherCoordinateSerializer.toCanonicalLocationId(latitude, longitude) == worksite.canonicalLocationId

    fun isWorksite(location: Location): Boolean = isWorksite(location.latitude, location.longitude)

    fun createWorksiteLocation(forecastSource: String = "openmeteo"): Location = Location(
        latitude = worksite.latitude,
        longitude = worksite.longitude,
        timeZone = TimeZone.getTimeZone(worksite.timeZoneId),
        customName = worksite.displayName,
        needsGeocodeRefresh = true,
        forecastSource = forecastSource
    )

    /** Adds the worksite at the front exactly once; an existing coordinate match is retained. */
    fun ensureWorksiteFirst(locations: List<Location>, worksite: Location): List<Location> =
        if (locations.any(::isWorksite)) locations else listOf(worksite) + locations

    /** Keeps any persisted worksite when a full-list rewrite omits it. */
    fun retainWorksite(existing: List<Location>, requested: List<Location>): List<Location> {
        if (requested.any(::isWorksite)) return requested
        val worksite = existing.filter(::isWorksite)
        return worksite + requested
    }

    /** Explicit navigation wins, then the user's saved selection, then the orchard migration default. */
    fun initialLocationId(
        explicitId: String?,
        savedId: String?,
        locations: List<Location>,
    ): String? {
        val availableIds = locations.mapTo(hashSetOf()) { it.formattedId }
        return explicitId?.takeIf { it in availableIds }
            ?: savedId?.takeIf { it in availableIds }
            ?: locations.firstOrNull(::isWorksite)?.formattedId
            ?: locations.firstOrNull()?.formattedId
    }

    fun canDelete(location: Location): Boolean = !isWorksite(location)

    fun shouldShowOrchardCards(location: Location?): Boolean = location != null && isWorksite(location)

    /**
     * Resolves the location to use for a weather request. If reverse geocoding is still pending,
     * coordinates are sufficient only for the fixed orchard and when its forecast source
     * explicitly supports coordinate-based forecast requests. Other selected sources stay intact
     * so their own location-parameter refresh and validation can run normally.
     */
    fun resolveWeatherRequestLocation(
        location: Location,
        coordinateBasedForecastSourceIds: Set<String>,
    ): Location? {
        if (location.isUsable && !location.needsGeocodeRefresh) return location
        if (!location.isUsable || !location.needsGeocodeRefresh || !isWorksite(location)) return null
        if (location.customName != worksite.displayName || location.timeZone.id != worksite.timeZoneId) return null
        if (location.forecastSource.isBlank()) return null

        if (location.forecastSource !in coordinateBasedForecastSourceIds) return null

        // Clear only on the request copy. The persisted orchard keeps geocoding pending for later
        // use with a source that needs a provider-specific location ID.
        return location.copy(needsGeocodeRefresh = false)
    }
}
