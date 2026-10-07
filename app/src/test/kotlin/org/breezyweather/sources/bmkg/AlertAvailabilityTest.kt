package org.breezyweather.sources.bmkg

import breezyweather.domain.location.model.Location
import breezyweather.domain.weather.model.Alert
import breezyweather.domain.weather.model.Weather
import org.breezyweather.domain.weather.model.alertsFromAvailableSource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AlertAvailabilityTest {
    @Test fun `unavailable BMKG alerts are hidden without mutating stored weather`() {
        val cached = listOf(Alert("cached", color = 0))
        val weather = Weather(alertList = cached)
        val location = Location(alertSource = "bmkg", weather = weather)
        assertTrue(location.alertsFromAvailableSource().isEmpty())
        assertSame(cached, weather.alertList)
        assertSame(cached, location.copy(alertSource = "other").alertsFromAvailableSource())
    }
}
