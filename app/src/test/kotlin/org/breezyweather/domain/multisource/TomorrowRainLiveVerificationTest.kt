/*
 * This file is part of Breezy Weather.
 */
package org.breezyweather.domain.multisource

import okhttp3.OkHttpClient
import org.breezyweather.domain.multisource.hourly.adapter.QWeatherHourlyAdapter
import org.breezyweather.domain.multisource.hourly.presentation.TomorrowRainPresentation
import org.breezyweather.domain.multisource.location.LocationAuthority
import org.junit.jupiter.api.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** One opt-in, credential-free QWeather diagnostic artifact for the orchard. */
class TomorrowRainLiveVerificationTest {

    @Test
    fun `records one bounded qweather request for today and tomorrow`() {
        val target = LocationAuthority.getAgriculturalPrimary()
        val now = System.currentTimeMillis()
        val targetDates = setOf(
            TomorrowRainPresentation.todayDateLocal(now, target.timeZoneId),
            TomorrowRainPresentation.tomorrowDateLocal(now, target.timeZoneId),
        )
        val properties = loadRepositoryProperties()
        val apiKey = System.getenv("QWEATHER_API_KEY")?.takeUnless(String::isBlank)
            ?: properties.getProperty("qweather.api_key")?.takeUnless(String::isBlank)
        val apiHost = System.getenv("QWEATHER_API_HOST")?.takeUnless(String::isBlank)
            ?: properties.getProperty("qweather.api_host")?.takeUnless(String::isBlank)
        val reportFile = File("build/reports/qweather-current-diagnostic.txt")
        reportFile.parentFile?.mkdirs()
        if (apiKey == null) {
            reportFile.writeText("CONFIGURATION=MISSING\n")
            println("QWEATHER_DIAGNOSTIC=CONFIGURATION_MISSING")
            return
        }

        val client = OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
        val result = QWeatherHourlyAdapter(client).fetchHourlyEvidencesForDatesDetailed(
            apiKey = apiKey,
            latitude = target.latitude,
            longitude = target.longitude,
            timeZoneId = target.timeZoneId,
            targetDatesLocal = targetDates,
            customApiHost = apiHost,
        )
        val hourFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone(target.timeZoneId)
        }
        val lines = buildList {
            add("FETCHED_AT=${hourFormat.format(Date(now))}")
            add("HOST_MODE=${if (result.diagnostics.usesDedicatedHost) "DEDICATED" else "SHARED_DEFAULT"}")
            add("TARGET_DATES=${targetDates.sorted().joinToString(",")}")
            result.diagnostics.attempts.forEach { attempt ->
                add("ATTEMPT=${attempt.apiVersion} HTTP=${attempt.httpStatus ?: "FAILED"}")
                add("RESPONSE_HOURS=${attempt.responseHours.firstOrNull() ?: "NONE"}..${attempt.responseHours.lastOrNull() ?: "NONE"} COUNT=${attempt.responseHours.size}")
                add("PROBABILITY_FIELDS=${attempt.hoursWithProbability.size} AMOUNT_FIELDS=${attempt.hoursWithAmount.size}")
            }
            add("RETAINED_HOURS=${result.diagnostics.retainedHours.joinToString(",")}")
            result.evidences.sortedBy { it.validFromEpochMs }.forEach { evidence ->
                add(
                    "HOUR=${hourFormat.format(Date(evidence.validFromEpochMs))} " +
                        "PROBABILITY=${evidence.precipitationProbability.percentage ?: "MISSING"} " +
                        "AMOUNT_MM=${evidence.precipitationAmount.valueMm ?: "MISSING"}"
                )
            }
        }
        reportFile.writeText(lines.joinToString("\n", postfix = "\n"))
        println("QWEATHER_DIAGNOSTIC_SAVED=${reportFile.path}")
        println("QWEATHER_HOST_MODE=${if (result.diagnostics.usesDedicatedHost) "DEDICATED" else "SHARED_DEFAULT"}")
        result.diagnostics.attempts.forEach { attempt ->
            println("QWEATHER_${attempt.apiVersion.uppercase()}_HTTP=${attempt.httpStatus ?: "FAILED"} hours=${attempt.responseHours.size} probabilityFields=${attempt.hoursWithProbability.size} amountFields=${attempt.hoursWithAmount.size}")
        }
        println("QWEATHER_RETAINED_HOURS=${result.diagnostics.retainedHours.size}")
    }

    private fun loadRepositoryProperties(): Properties {
        val properties = Properties()
        listOf(
            File(System.getProperty("user.dir"), "local.properties"),
            File(System.getProperty("user.dir"), "..\\local.properties"),
        ).firstOrNull { it.isFile }?.inputStream()?.use(properties::load)
        return properties
    }
}
