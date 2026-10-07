/*
 * This file is part of Breezy Weather.
 */
package org.breezyweather.domain.multisource

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.breezyweather.domain.multisource.hourly.adapter.BreezyChinaHourlyAdapter
import org.breezyweather.domain.multisource.hourly.adapter.OpenMeteoHourlyAdapter
import org.breezyweather.domain.multisource.hourly.presentation.TomorrowRainPresentation
import org.breezyweather.domain.multisource.location.LocationAuthority
import org.breezyweather.domain.multisource.model.PrecipitationProbabilityValue
import org.breezyweather.domain.multisource.model.SourceIdentity
import org.breezyweather.domain.multisource.model.UnderlyingModel
import org.breezyweather.domain.multisource.model.WeatherProvider
import org.junit.jupiter.api.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class HourlySourceMappingTest {
    private val adapter = OpenMeteoHourlyAdapter()
    private val target = LocationAuthority.WORKSITE
    private val referenceNow = epoch("2026-10-02 12:00")

    // 1790956800 is 2026-10-02 16:00 UTC / 2026-10-03 00:00 Asia/Shanghai.
    // Official Open-Meteo semantics: the value at midnight covers the preceding hour.
    private val midnightFixture = """
        {"hourly":{"time":[1790956800],
          "precipitation_cma_grapes_global":[0.6],
          "precipitation_probability_cma_grapes_global":[null],
          "weather_code_cma_grapes_global":[61],
          "precipitation_best_match":[0.6],
          "precipitation_probability_best_match":[40],
          "weather_code_best_match":[61]
        }}
    """.trimIndent()

    @Test
    fun `new Open Meteo suffixes keep physical identities and prefer current fields`() {
        val evidences = parse("""
            {"hourly":{"time":[1790956800],
              "precipitation_ncep_gfs_global":[0.7],"precipitation_gfs_global":[9.9],
              "precipitation_probability_ncep_gfs_global":[42],"weather_code_ncep_gfs_global":[61],
              "precipitation_dwd_icon_global":[0.2],"precipitation_probability_dwd_icon_global":[20],
              "weather_code_dwd_icon_global":[61]}}
        """.trimIndent())
        val gfs = evidences.single { it.underlyingModel == UnderlyingModel.NOAA_GFS }
        gfs.precipitationAmount.valueMm shouldBe 0.7
        gfs.precipitationProbability.percentage shouldBe 42
        gfs.validFromEpochMs shouldBe epoch("2026-10-02 23:00")
        gfs.rawSource shouldContain "ncep_gfs_global"
        evidences.map { it.underlyingModel } shouldBe listOf(UnderlyingModel.NOAA_GFS, UnderlyingModel.DWD_ICON)
    }

    @Test
    fun `midnight precipitation belongs to previous day 23 to 24 and CMA null probability remains absent`() {
        val evidences = parse(midnightFixture)
        val cma = evidences.single { it.underlyingModel == UnderlyingModel.CMA_GRAPES }
        cma.validFromEpochMs shouldBe epoch("2026-10-02 23:00")
        cma.validToEpochMs shouldBe epoch("2026-10-03 00:00")
        cma.precipitationAmount.valueMm shouldBe 0.6
        cma.precipitationProbability.percentage shouldBe null
        cma.probabilityEventDefinition shouldBe null
        cma.provenanceDetail shouldContain "全球"
        cma.provenanceDetail shouldContain "Open-Meteo"
        parse(midnightFixture, "2026-10-03") shouldBe emptyList()
    }

    @Test
    fun `null and invalid numbers cannot create dry evidence or empty source columns`() {
        val evidences = parse("""
            {"hourly":{"time":[1790956800],
              "precipitation_cma_grapes_global":[null],"precipitation_probability_cma_grapes_global":[null],"weather_code_cma_grapes_global":[null],
              "precipitation_gfs_global":[-0.1],"precipitation_probability_gfs_global":[-1],"weather_code_gfs_global":[null],
              "precipitation_icon_global":[0.0],"precipitation_probability_icon_global":[0],"weather_code_icon_global":[0]
            }}
        """.trimIndent())
        evidences.map { it.underlyingModel } shouldBe listOf(UnderlyingModel.DWD_ICON)
        evidences.single().precipitationAmount.valueMm shouldBe 0.0
        evidences.single().precipitationProbability.percentage shouldBe 0
    }

    @Test
    fun `CMA amount remains visible and same upstream plus best match cannot multiply independent forecasts`() {
        val evidences = parse(midnightFixture)
        val cma = evidences.single { it.underlyingModel == UnderlyingModel.CMA_GRAPES }
        val duplicateCma = cma.copy(sourceIdentity = SourceIdentity.resolve(WeatherProvider.QWEATHER, UnderlyingModel.CMA_GRAPES))
        val opaqueProvider = cma.copy(sourceIdentity = SourceIdentity.resolve(WeatherProvider.QWEATHER, UnderlyingModel.QWEATHER_AGGREGATED))
        val snapshot = snapshot(evidences + duplicateCma + opaqueProvider)
        val row = TomorrowRainPresentation.hourlyDisplayRows(snapshot, referenceNow).single { it.hour == 23 }

        snapshot.summary.independentPhysicalModelCount shouldBe 1
        row.summary.independentModelCount shouldBe 1
        row.summary.compositeForecastCount shouldBe 1
        row.summary.totalForecastCount shouldBe 2
        row.summary.omittedDuplicateForecasts.size shouldBe 1
        row.summary.omittedOverlappingBestMatchForecasts.size shouldBe 1
        row.forecasts.single { it.forecastKey == "model:cma_grapes" }.apply {
            displayName shouldBe "中国气象局 GRAPES"
            amountMm shouldBe 0.6
            probabilityPercent shouldBe null
        }
    }

    @Test
    fun `Xiaomi cached conditions do not claim a known Caiyun upstream or invent amounts`() {
        val evidence = BreezyChinaHourlyAdapter().adaptHourlyForecast(
            target.canonicalLocationId,
            listOf(BreezyChinaHourlyAdapter.HourlyWeatherEntry(epoch("2026-10-02 23:00"), "小雨", "RAIN")),
            target.timeZoneId,
            "2026-10-02",
        ).single()
        evidence.underlyingModel shouldBe UnderlyingModel.UNKNOWN
        evidence.resolvedPhysicalModel shouldBe null
        evidence.rawSource shouldContain "xiaomi.com"
        evidence.precipitationAmount.valueMm shouldBe null
        evidence.precipitationProbability.percentage shouldBe null
        val cachedSnapshot = snapshot(listOf(evidence))
        cachedSnapshot.summary.independentPhysicalModelCount shouldBe 0
        cachedSnapshot.slots.last().sourceMetrics.single().providerName shouldBe "小米天气网关（缓存）"
    }

    @Test
    fun `strictly above and at least probability thresholds remain separate event definitions`() {
        val cma = parse(midnightFixture).single { it.underlyingModel == UnderlyingModel.CMA_GRAPES }
        val strict = cma.copy(
            sourceIdentity = SourceIdentity.resolve(WeatherProvider.OPEN_METEO, UnderlyingModel.DWD_ICON),
            precipitationProbability = PrecipitationProbabilityValue.available(40),
            probabilityEventDefinition = "1小时累计降水 > 0.1mm 成员占比",
        )
        val inclusive = strict.copy(
            sourceIdentity = SourceIdentity.resolve(WeatherProvider.OPEN_METEO, UnderlyingModel.ECMWF_IFS),
            probabilityEventDefinition = "1小时累计降水 >= 0.1mm 成员占比",
        )
        val slot = snapshot(listOf(strict, inclusive)).slots.last()
        val groups = TomorrowRainPresentation.probabilityDisplay(slot, target.timeZoneId)
            .hourly.single { it.hour == 23 }.groups
        groups.map { it.eventScopeLabel }.toSet() shouldBe setOf("1小时累计降水>0.1毫米", "1小时累计降水≥0.1毫米")
        groups.size shouldBe 2
    }

    private fun parse(body: String, targetDate: String = "2026-10-02") = adapter.parseHourlyResponse(
        body, target.latitude, target.longitude, target.timeZoneId, setOf(targetDate), referenceNow,
    )

    private fun snapshot(evidences: List<org.breezyweather.domain.multisource.model.ForecastEvidence>) =
        TomorrowRainPresentation.buildSnapshot(target, "2026-10-02", evidences, emptyList(), nowEpochMs = referenceNow)

    private fun epoch(value: String): Long = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }.parse(value)!!.time
}
