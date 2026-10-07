package org.breezyweather.sources

import breezyweather.data.location.LocationRepository
import breezyweather.data.weather.WeatherRepository
import breezyweather.domain.location.model.OrchardLocationPolicy
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
import org.breezyweather.common.source.LocationResult
import org.breezyweather.common.source.RefreshError
import org.breezyweather.common.source.WeatherSource
import org.breezyweather.domain.settings.CurrentLocationStore
import org.breezyweather.ui.main.utils.RefreshErrorType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RefreshHelperOrchardLocationTest {
    @Test
    fun `pending reverse geocoding does not block coordinate-only orchard forecast`() {
        val source = coordinateSource("openmeteo", true)
        val helper = refreshHelperWith(source)
        val orchard = OrchardLocationPolicy.createWorksiteLocation().copy(weather = null)
        val failedGeocode = LocationResult(
            orchard,
            listOf(RefreshError(RefreshErrorType.REVERSE_GEOCODING_FAILED, "nativegeocoder"))
        )

        val requestLocation = helper.resolveWeatherRequestLocation(orchard, failedGeocode)

        assertNotNull(requestLocation)
        assertEquals(orchard.latitude, requestLocation?.latitude)
        assertEquals(orchard.longitude, requestLocation?.longitude)
        assertEquals(orchard.timeZone.id, requestLocation?.timeZone?.id)
        assertEquals(orchard.cityId, requestLocation?.cityId)
        assertEquals(false, requestLocation?.needsGeocodeRefresh)
        assertEquals(true, orchard.needsGeocodeRefresh)
        assertEquals(RefreshErrorType.REVERSE_GEOCODING_FAILED, failedGeocode.errors.single().error)
    }

    @Test
    fun `pending geocoding still blocks a source that requires a place id`() {
        val source = coordinateSource("gmet", false)
        val helper = refreshHelperWith(source)
        val orchard = OrchardLocationPolicy.createWorksiteLocation("gmet").copy(weather = null)
        val failedGeocode = LocationResult(
            orchard,
            listOf(RefreshError(RefreshErrorType.REVERSE_GEOCODING_FAILED, "nativegeocoder"))
        )

        assertNull(helper.resolveWeatherRequestLocation(orchard, failedGeocode))
        assertEquals(true, orchard.needsGeocodeRefresh)
    }

    @Test
    fun `coordinate forecast leaves an id based secondary source for its own validation`() {
        val openMeteo = coordinateSource("openmeteo", true)
        val smasc = coordinateSource("smasc", false)
        val helper = refreshHelperWith(openMeteo, smasc)
        val orchard = OrchardLocationPolicy.createWorksiteLocation().copy(
            weather = null,
            normalsSource = "smasc"
        )
        val failedGeocode = LocationResult(
            orchard,
            listOf(RefreshError(RefreshErrorType.REVERSE_GEOCODING_FAILED, "nativegeocoder"))
        )

        val requestLocation = helper.resolveWeatherRequestLocation(orchard, failedGeocode)

        assertEquals("openmeteo", requestLocation?.forecastSource)
        assertEquals("smasc", requestLocation?.normalsSource)
        assertNull(requestLocation?.parameters?.get("smasc")?.get("cityId"))
        assertEquals(true, orchard.needsGeocodeRefresh)
    }

    private fun coordinateSource(sourceId: String, supportsCoordinateForecasts: Boolean): WeatherSource = mockk {
        every { id } returns sourceId
        every { supportsCoordinateBasedForecastRequests } returns supportsCoordinateForecasts
    }

    private fun refreshHelperWith(vararg sources: WeatherSource): RefreshHelper {
        val sourceManager = mockk<SourceManager>()
        every { sourceManager.getWeatherSources() } returns persistentListOf(*sources)
        return RefreshHelper(
            sourceManager,
            mockk<LocationRepository>(relaxed = true),
            mockk<WeatherRepository>(relaxed = true),
            mockk<CurrentLocationStore>(relaxed = true)
        )
    }
}
