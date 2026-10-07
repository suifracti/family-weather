package org.breezyweather.sources.openmeteo

import android.content.Context
import breezyweather.domain.location.model.Location
import breezyweather.domain.location.model.OrchardLocationPolicy
import breezyweather.domain.weather.model.DailyTemperatureChange
import io.mockk.mockk
import org.breezyweather.sources.openmeteo.json.OpenMeteoWeatherDaily
import org.breezyweather.unit.temperature.TemperatureUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import retrofit2.Retrofit
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone

class OpenMeteoDailyTemperatureTest {
    @Test
    fun `Open-Meteo can serve the authoritative orchard coordinates without a geocoded place id`() {
        val source = OpenMeteoService(mockk<Context>(relaxed = true), Retrofit.Builder())
        val orchard = OrchardLocationPolicy.createWorksiteLocation().copy(weather = null)

        val requestLocation = OrchardLocationPolicy.resolveWeatherRequestLocation(
            orchard,
            coordinateBasedForecastSourceIds = if (source.supportsCoordinateBasedForecastRequests) {
                setOf(source.id)
            } else {
                emptySet()
            }
        )

        assertEquals(true, source.supportsCoordinateBasedForecastRequests)
        assertEquals(orchard.latitude, requestLocation?.latitude)
        assertEquals(orchard.longitude, requestLocation?.longitude)
        assertEquals(orchard.cityId, requestLocation?.cityId)
        assertEquals(false, requestLocation?.needsGeocodeRefresh)
    }

    @Test
    fun `maps Open-Meteo daily maximum and minimum into day and night temperatures`() {
        val daily = OpenMeteoWeatherDaily(
            time = listOf(6, 7, 8).map { day ->
                LocalDate.of(2026, 10, day).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            }.toLongArray(),
            temperatureMax = arrayOf<Double?>(26.8, 28.5, 29.0),
            temperatureMin = arrayOf<Double?>(16.1, 14.8, 15.0),
            apparentTemperatureMax = null,
            apparentTemperatureMin = null,
            sunshineDuration = null,
            uvIndexMax = null,
            relativeHumidityMean = null,
            relativeHumidityMax = null,
            relativeHumidityMin = null,
            dewPointMean = null,
            dewPointMax = null,
            dewPointMin = null,
            pressureMslMean = null,
            pressureMslMax = null,
            pressureMslMin = null,
            cloudCoverMean = null,
            cloudCoverMax = null,
            cloudCoverMin = null,
            visibilityMean = null,
            visibilityMax = null,
            visibilityMin = null
        )
        val location = Location(timeZone = TimeZone.getTimeZone("Asia/Shanghai"))
        val service = OpenMeteoService(mockk<Context>(relaxed = true), Retrofit.Builder())
        val method = OpenMeteoService::class.java
            .getDeclaredMethod("getDailyList", OpenMeteoWeatherDaily::class.java, Location::class.java)
            .apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val forecasts = method.invoke(service, daily, location) as List<breezyweather.domain.weather.wrappers.DailyWrapper>
        val today = forecasts[0].toDaily()
        val tomorrow = forecasts[1].toDaily()
        val changes = DailyTemperatureChange.between(
            today,
            tomorrow,
            location.timeZone,
            todaySourceId = location.forecastSource,
            tomorrowSourceId = location.forecastSource
        )

        assertEquals(
            28.5,
            tomorrow.day?.temperature?.temperature?.toDouble(TemperatureUnit.CELSIUS) ?: Double.NaN,
            0.01
        )
        assertEquals(
            14.8,
            tomorrow.night?.temperature?.temperature?.toDouble(TemperatureUnit.CELSIUS) ?: Double.NaN,
            0.01
        )
        assertEquals(1.7, changes.high?.toDouble(TemperatureUnit.CELSIUS) ?: Double.NaN, 0.01)
        assertEquals(-1.3, changes.low?.toDouble(TemperatureUnit.CELSIUS) ?: Double.NaN, 0.01)
    }
}
