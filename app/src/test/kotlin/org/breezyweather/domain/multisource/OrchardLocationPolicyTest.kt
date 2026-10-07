/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource

import breezyweather.domain.location.model.Location
import breezyweather.domain.location.model.OrchardLocationPolicy
import breezyweather.domain.weather.model.Daily
import breezyweather.domain.weather.model.DailyTemperatureChange
import breezyweather.domain.weather.model.HalfDay
import breezyweather.domain.weather.model.Temperature as ForecastTemperature
import org.breezyweather.domain.multisource.location.LocationAuthority
import org.breezyweather.domain.multisource.location.WeatherCoordinateSerializer
import io.kotest.matchers.shouldBe
import org.breezyweather.unit.temperature.Temperature.Companion.celsius
import org.breezyweather.unit.temperature.TemperatureUnit
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Date
import java.util.TimeZone

class OrchardLocationPolicyTest {
    private val forecastTimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private val orchard = Location(
        latitude = LocationAuthority.WORKSITE.latitude,
        longitude = LocationAuthority.WORKSITE.longitude,
        customName = LocationAuthority.WORKSITE.displayName,
        forecastSource = "openmeteo"
    )
    private val guilin = Location(
        latitude = 25.2736,
        longitude = 110.2900,
        city = "桂林",
        forecastSource = "openmeteo"
    )

    @Test
    fun `formal orchard location uses the existing worksite coordinates`() {
        val location = OrchardLocationPolicy.createWorksiteLocation()

        location.latitude shouldBe LocationAuthority.WORKSITE.latitude
        location.longitude shouldBe LocationAuthority.WORKSITE.longitude
        location.customName shouldBe LocationAuthority.WORKSITE.displayName
        location.timeZone.id shouldBe LocationAuthority.WORKSITE.timeZoneId
        WeatherCoordinateSerializer.toCanonicalLocationId(location.latitude, location.longitude) shouldBe
            LocationAuthority.WORKSITE.canonicalLocationId
    }

    @Test
    fun `first migration adds the authoritative orchard once and selects it by default`() {
        val migrated = OrchardLocationPolicy.ensureWorksiteFirst(listOf(guilin), orchard)

        migrated.first().formattedId shouldBe orchard.formattedId
        migrated.count(OrchardLocationPolicy::isWorksite) shouldBe 1
        OrchardLocationPolicy.initialLocationId(null, null, migrated) shouldBe orchard.formattedId

        val existingWorksite = orchard.copy(forecastSource = "china")
        val alreadyMigrated = listOf(guilin, existingWorksite)
        OrchardLocationPolicy.ensureWorksiteFirst(alreadyMigrated, orchard) shouldBe alreadyMigrated
    }

    @Test
    fun `explicit and saved city selection take precedence after migration`() {
        val locations = listOf(orchard, guilin)

        OrchardLocationPolicy.initialLocationId(guilin.formattedId, null, locations) shouldBe guilin.formattedId
        OrchardLocationPolicy.initialLocationId(null, guilin.formattedId, locations) shouldBe guilin.formattedId
        OrchardLocationPolicy.initialLocationId(null, "removed-location", locations) shouldBe orchard.formattedId
    }

    @Test
    fun `failed optional geocoding still permits coordinate forecast for the uncached authoritative orchard`() {
        val geocodingFailure = OrchardLocationPolicy.createWorksiteLocation().copy(
            weather = null,
            needsGeocodeRefresh = true
        )

        val requestLocation = OrchardLocationPolicy.resolveWeatherRequestLocation(
            geocodingFailure,
            coordinateBasedForecastSourceIds = setOf("openmeteo")
        )

        requestLocation?.latitude shouldBe LocationAuthority.WORKSITE.latitude
        requestLocation?.longitude shouldBe LocationAuthority.WORKSITE.longitude
        requestLocation?.timeZone?.id shouldBe LocationAuthority.WORKSITE.timeZoneId
        requestLocation?.customName shouldBe LocationAuthority.WORKSITE.displayName
        requestLocation?.weather shouldBe null
        requestLocation?.needsGeocodeRefresh shouldBe false
        requestLocation?.city shouldBe ""
        requestLocation?.cityId shouldBe null
        requestLocation?.admin1 shouldBe null
        requestLocation?.admin2 shouldBe null
        geocodingFailure.needsGeocodeRefresh shouldBe true
    }

    @Test
    fun `failed geocoding does not bypass an id based forecast source`() {
        val locationWithoutProviderId = OrchardLocationPolicy.createWorksiteLocation("gmet").copy(
            weather = null,
            needsGeocodeRefresh = true,
            parameters = emptyMap()
        )
        locationWithoutProviderId.parameters["gmet"]?.get("cityId") shouldBe null

        OrchardLocationPolicy.resolveWeatherRequestLocation(
            locationWithoutProviderId,
            coordinateBasedForecastSourceIds = setOf("openmeteo")
        ) shouldBe null
    }

    @Test
    fun `a location id based secondary source stays configured for its own validation`() {
        val locationWithIdBasedNormals = OrchardLocationPolicy.createWorksiteLocation().copy(
            weather = null,
            needsGeocodeRefresh = true,
            normalsSource = "smasc",
            parameters = emptyMap()
        )

        val requestLocation = OrchardLocationPolicy.resolveWeatherRequestLocation(
            locationWithIdBasedNormals,
            coordinateBasedForecastSourceIds = setOf("openmeteo")
        )

        requestLocation?.forecastSource shouldBe "openmeteo"
        requestLocation?.normalsSource shouldBe "smasc"
        requestLocation?.parameters?.get("smasc")?.get("cityId") shouldBe null
        requestLocation?.needsGeocodeRefresh shouldBe false
    }

    @Test
    fun `orchard is not deletable and bulk replacement retains it`() {
        OrchardLocationPolicy.canDelete(orchard) shouldBe false
        OrchardLocationPolicy.canDelete(guilin) shouldBe true
        OrchardLocationPolicy.retainWorksite(listOf(orchard, guilin), listOf(guilin)) shouldBe listOf(orchard, guilin)
        OrchardLocationPolicy.retainWorksite(listOf(orchard, guilin), emptyList()) shouldBe listOf(orchard)
    }

    @Test
    fun `orchard card scope follows the selected location coordinates`() {
        OrchardLocationPolicy.shouldShowOrchardCards(orchard) shouldBe true
        OrchardLocationPolicy.shouldShowOrchardCards(guilin) shouldBe false
    }

    @Test
    fun `a same-name location at another coordinate is deletable`() {
        val sameNameCity = guilin.copy(customName = LocationAuthority.WORKSITE.displayName)

        OrchardLocationPolicy.canDelete(sameNameCity) shouldBe true
        OrchardLocationPolicy.isWorksite(sameNameCity) shouldBe false
        OrchardLocationPolicy.retainWorksite(listOf(orchard), listOf(sameNameCity)) shouldBe
            listOf(orchard, sameNameCity)
    }

    @Test
    fun `high and low temperature changes are computed independently and keep their sign`() {
        val today = daily(high = 28.0.celsius, low = 18.0.celsius, date = localDate("2026-10-06"))
        val tomorrow = daily(high = 30.0.celsius, low = 17.0.celsius, date = localDate("2026-10-07"))

        val changes = between(today, tomorrow)

        changes.high?.toDoubleDeviation(TemperatureUnit.CELSIUS) shouldBe 2.0
        changes.low?.toDoubleDeviation(TemperatureUnit.CELSIUS) shouldBe -1.0
        changes.high?.toDoubleDeviation(TemperatureUnit.FAHRENHEIT) shouldBe 3.6
    }

    @Test
    fun `missing high temperature does not hide an available low temperature change`() {
        val today = daily(high = null, low = 18.0.celsius, date = localDate("2026-10-06"))
        val tomorrow = daily(high = 30.0.celsius, low = 17.0.celsius, date = localDate("2026-10-07"))

        val changes = between(today, tomorrow)

        changes.high shouldBe null
        changes.low?.toDoubleDeviation(TemperatureUnit.CELSIUS) shouldBe -1.0
    }

    @Test
    fun `change is omitted when the previous daily forecast is missing`() {
        val tomorrow = daily(
            high = 30.0.celsius,
            low = 17.0.celsius,
            date = localDate("2026-10-07")
        )

        val changes = between(null, tomorrow)

        changes.high shouldBe null
        changes.low shouldBe null
    }

    @Test
    fun `change is omitted when daily forecast dates are not consecutive`() {
        val today = daily(high = 28.0.celsius, low = 18.0.celsius, date = localDate("2026-10-06"))
        val laterDay = daily(high = 30.0.celsius, low = 17.0.celsius, date = localDate("2026-10-08"))

        val changes = between(today, laterDay)

        changes.high shouldBe null
        changes.low shouldBe null
    }

    @Test
    fun `change is omitted when daily forecasts come from different sources`() {
        val today = daily(high = 28.0.celsius, low = 18.0.celsius, date = localDate("2026-10-06"))
        val tomorrow = daily(high = 30.0.celsius, low = 17.0.celsius, date = localDate("2026-10-07"))

        val changes = DailyTemperatureChange.between(
            today,
            tomorrow,
            forecastTimeZone,
            todaySourceId = "openmeteo",
            tomorrowSourceId = "other-source"
        )

        changes.high shouldBe null
        changes.low shouldBe null
    }

    private fun between(today: Daily?, tomorrow: Daily?) = DailyTemperatureChange.between(
        today,
        tomorrow,
        forecastTimeZone,
        todaySourceId = "openmeteo",
        tomorrowSourceId = "openmeteo"
    )

    private fun localDate(value: String) = Date.from(
        LocalDate.parse(value).atStartOfDay(forecastTimeZone.toZoneId()).toInstant()
    )

    private fun daily(
        high: org.breezyweather.unit.temperature.Temperature?,
        low: org.breezyweather.unit.temperature.Temperature?,
        date: Date,
    ) =
        Daily(
            date = date,
            day = HalfDay(temperature = high?.let { ForecastTemperature(temperature = it) }),
            night = HalfDay(temperature = low?.let { ForecastTemperature(temperature = it) })
        )
}
