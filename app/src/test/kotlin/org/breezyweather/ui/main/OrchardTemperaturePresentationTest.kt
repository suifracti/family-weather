package org.breezyweather.ui.main

import breezyweather.domain.weather.model.Daily
import breezyweather.domain.weather.model.HalfDay
import breezyweather.domain.weather.model.Temperature as ForecastTemperature
import io.kotest.matchers.shouldBe
import org.breezyweather.ui.main.adapters.main.OrchardTemperaturePresentation
import org.breezyweather.unit.temperature.Temperature.Companion.celsius
import org.breezyweather.unit.temperature.TemperatureUnit
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class OrchardTemperaturePresentationTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private fun at(value: String) = Date.from(Instant.parse(value))
    private fun daily(day: String, high: Double? = null, low: Double? = null, timeZone: TimeZone = zone) = Daily(
        date = Date.from(LocalDate.parse(day).atStartOfDay(timeZone.toZoneId()).toInstant()),
        day = HalfDay(temperature = high?.let { ForecastTemperature(temperature = it.celsius) }),
        night = HalfDay(temperature = low?.let { ForecastTemperature(temperature = it.celsius) })
    )

    @Test fun `product display is Chinese Celsius even when host defaults are US`() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val view = OrchardTemperaturePresentation(emptyList(), zone, at("2026-10-07T04:00:00Z"))
            view.temperatureText(29.2.celsius) shouldBe "29.2℃"
            view.changeText(0.8.celsius) shouldBe "较今日 +0.8℃"
            OrchardTemperaturePresentation.locale shouldBe Locale.SIMPLIFIED_CHINESE
            OrchardTemperaturePresentation.temperatureUnit shouldBe TemperatureUnit.CELSIUS
            Locale.getDefault() shouldBe Locale.US
        } finally { Locale.setDefault(old) }
    }

    @Test fun `today is evaluated in location timezone and does not borrow device date`() {
        // UTC still October 7, orchard already October 8.
        val rows = listOf(daily("2026-10-07"), daily("2026-10-08"), daily("2026-10-09"))
        val view = OrchardTemperaturePresentation(rows, zone, at("2026-10-07T16:05:00Z"))
        view.dailyForecast shouldBe rows.drop(1)
        view.sourceIndexOrNull(0) shouldBe 1
        view.dateLabels(rows[1].date) shouldBe ("今天" to "10月8日")
        view.dateLabels(rows[2].date) shouldBe ("明天" to "10月9日")
    }

    @Test fun `ordinary details preserve preferred units and orchard entry does not inherit Fahrenheit`() {
        OrchardTemperaturePresentation.detailTemperatureUnit(false, TemperatureUnit.FAHRENHEIT) shouldBe TemperatureUnit.FAHRENHEIT
        OrchardTemperaturePresentation.detailTemperatureUnit(true, TemperatureUnit.FAHRENHEIT) shouldBe TemperatureUnit.CELSIUS
    }

    @Test fun `west timezone retains its own today while UTC is already tomorrow`() {
        val west = TimeZone.getTimeZone("America/Los_Angeles")
        val rows = listOf(daily("2026-10-06", timeZone = west), daily("2026-10-07", timeZone = west))
        OrchardTemperaturePresentation(rows, west, at("2026-10-08T01:00:00Z")).dailyForecast shouldBe rows.drop(1)
    }

    @Test fun `filtered rows keep exact source indices even if past rows are interleaved`() {
        val rows = listOf(daily("2026-10-06"), daily("2026-10-08"), daily("2026-10-05"), daily("2026-10-09"))
        val view = OrchardTemperaturePresentation(rows, zone, at("2026-10-07T04:00:00Z"))
        view.dailyForecast shouldBe listOf(rows[1], rows[3])
        view.sourceIndexOrNull(0) shouldBe 1
        view.sourceIndexOrNull(1) shouldBe 3
        view.sourceIndexOrNull(-1) shouldBe null
        view.sourceIndexOrNull(2) shouldBe null
        rows.size shouldBe 4 // original data remains available for comparisons
    }

    @Test fun `no future data produces an empty list and does not synthesize today`() {
        val view = OrchardTemperaturePresentation(listOf(daily("2026-10-06", high = 28.0)), zone, at("2026-10-07T04:00:00Z"))
        view.dailyForecast shouldBe emptyList()
        view.hasTemperatureData shouldBe false
    }

    @Test fun `missing future temperatures do not reuse past temperatures`() {
        val view = OrchardTemperaturePresentation(listOf(daily("2026-10-06", high = 28.0), daily("2026-10-07")), zone, at("2026-10-07T04:00:00Z"))
        view.hasTemperatureData shouldBe false
        view.temperatureText(null) shouldBe "资料不足"
        view.changeText(null) shouldBe "较今日变化资料不足"
        view.temperatureText(0.0.celsius) shouldBe "0℃"
        view.changeText((-0.9).celsius) shouldBe "较今日 -0.9℃"
    }

    @Test fun `month and year rollover use real local dates`() {
        val view = OrchardTemperaturePresentation(emptyList(), zone, at("2026-12-31T04:00:00Z"))
        view.dateLabels(daily("2027-01-01").date) shouldBe ("明天" to "1月1日")
        view.dateLabels(daily("2027-01-02").date) shouldBe ("周六" to "1月2日")
    }

    @Test fun `chart dates stay compact while accessible dates remain complete`() {
        val view = OrchardTemperaturePresentation(emptyList(), zone, at("2026-10-07T04:00:00Z"))
        val row = daily("2026-10-10", high = 31.2, low = 17.2)
        view.chartDateLabels(row.date) shouldBe ("周六" to "10/10")
        view.dailyDescription(row) shouldBe "周六，10月10日，最高温31.2℃，最低温17.2℃"
    }

    @Test fun `accessible description binds its own date and independent available values`() {
        val view = OrchardTemperaturePresentation(emptyList(), zone, at("2026-10-07T04:00:00Z"))
        view.dailyDescription(daily("2026-10-08", high = 29.2, low = 15.7)) shouldBe "明天，10月8日，最高温29.2℃，最低温15.7℃"
        view.dailyDescription(daily("2026-10-09", low = 17.2)) shouldBe "周五，10月9日，最高温资料不足，最低温17.2℃"
    }
}
