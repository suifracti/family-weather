/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.ui.main.adapters.main

import breezyweather.domain.weather.model.Daily
import org.breezyweather.unit.temperature.Temperature
import org.breezyweather.unit.temperature.TemperatureUnit
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A display-only projection. Original forecast rows remain available for comparisons and details. */
class OrchardTemperaturePresentation(
    forecast: List<Daily>,
    private val timeZone: TimeZone,
    now: Date = Date(),
) {
    private val today = now.toInstant().atZone(timeZone.toZoneId()).toLocalDate()
    private val sourceIndices = forecast.indices.filter { index ->
        !forecast[index].date.toInstant().atZone(timeZone.toZoneId()).toLocalDate().isBefore(today)
    }
    val dailyForecast = sourceIndices.map(forecast::get)
    val hasTemperatureData = dailyForecast.any {
        it.day?.temperature?.temperature != null || it.night?.temperature?.temperature != null
    }

    fun sourceIndexOrNull(displayIndex: Int): Int? = sourceIndices.getOrNull(displayIndex)

    fun dateLabels(date: Date): Pair<String, String> {
        val localDate = date.toInstant().atZone(timeZone.toZoneId()).toLocalDate()
        val day = when (localDate) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            else -> dateText(date, "E")
        }
        return day to dateText(date, "M月d日")
    }

    fun chartDateLabels(date: Date): Pair<String, String> = dateLabels(date).first to dateText(date, "MM/dd")

    fun temperatureText(value: Temperature?, compact: Boolean = false): String = value?.let {
        number(it.toDouble(temperatureUnit), compact) + if (compact) "°" else "℃"
    } ?: "资料不足"

    fun changeText(value: Temperature?): String = value?.let {
        val delta = it.toDoubleDeviation(temperatureUnit)
        "较今日 ${if (delta > 0) "+" else ""}${number(delta)}℃"
    } ?: "较今日变化资料不足"

    fun dailyDescription(daily: Daily): String {
        val (day, date) = dateLabels(daily.date)
        return "$day，$date，最高温${temperatureText(daily.day?.temperature?.temperature)}，" +
            "最低温${temperatureText(daily.night?.temperature?.temperature)}"
    }

    private fun dateText(date: Date, pattern: String) = SimpleDateFormat(pattern, locale).apply {
        timeZone = this@OrchardTemperaturePresentation.timeZone
    }.format(date)

    private fun number(value: Double, compact: Boolean = false) =
        DecimalFormat(if (compact) "0" else "0.#", DecimalFormatSymbols(locale)).format(value)

    companion object {
        val locale: Locale = Locale.SIMPLIFIED_CHINESE
        val temperatureUnit = TemperatureUnit.CELSIUS

        fun detailTemperatureUnit(isOrchardPresentation: Boolean, preferred: TemperatureUnit) =
            if (isOrchardPresentation) temperatureUnit else preferred
    }
}
