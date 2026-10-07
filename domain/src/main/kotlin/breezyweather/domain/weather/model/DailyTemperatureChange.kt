/*
 * This file is part of Breezy Weather.
 */

package breezyweather.domain.weather.model

import org.breezyweather.unit.temperature.Temperature.Companion.deciCelsius
import org.breezyweather.unit.temperature.Temperature as TemperatureValue
import org.breezyweather.unit.temperature.TemperatureUnit
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/** High and low changes are independent so a missing value never gets inferred from the other. */
data class DailyTemperatureChange(
    val high: TemperatureValue?,
    val low: TemperatureValue?,
) {
    companion object {
        fun between(
            today: Daily?,
            tomorrow: Daily?,
            timeZone: TimeZone,
            todaySourceId: String?,
            tomorrowSourceId: String?,
        ): DailyTemperatureChange {
            if (today == null || tomorrow == null ||
                todaySourceId.isNullOrBlank() || todaySourceId != tomorrowSourceId ||
                !isNextLocalDay(today.date, tomorrow.date, timeZone)
            ) {
                return DailyTemperatureChange(high = null, low = null)
            }

            return DailyTemperatureChange(
                high = deviation(
                    today.day?.temperature?.temperature,
                    tomorrow.day?.temperature?.temperature
                ),
                low = deviation(
                    today.night?.temperature?.temperature,
                    tomorrow.night?.temperature?.temperature
                )
            )
        }

        private fun isNextLocalDay(today: Date, tomorrow: Date, timeZone: TimeZone): Boolean {
            val expected = Calendar.getInstance(timeZone).apply {
                time = today
                add(Calendar.DAY_OF_YEAR, 1)
            }
            val actual = Calendar.getInstance(timeZone).apply { time = tomorrow }
            return expected.get(Calendar.YEAR) == actual.get(Calendar.YEAR) &&
                expected.get(Calendar.DAY_OF_YEAR) == actual.get(Calendar.DAY_OF_YEAR)
        }

        private fun deviation(today: TemperatureValue?, tomorrow: TemperatureValue?): TemperatureValue? {
            if (today == null || tomorrow == null) return null
            val changeInDeciCelsius =
                tomorrow.toDouble(TemperatureUnit.DECI_CELSIUS) - today.toDouble(TemperatureUnit.DECI_CELSIUS)
            return changeInDeciCelsius.deciCelsius
        }
    }
}
