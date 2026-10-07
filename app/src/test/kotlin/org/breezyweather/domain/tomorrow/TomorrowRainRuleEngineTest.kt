/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.tomorrow

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TomorrowRainRuleEngineTest {

    @Test
    fun `test dry day evaluates to LOW risk`() {
        val risk = TomorrowRainRuleEngine.evaluateRiskLevel(
            total24hPrecipMm = 0.0,
            maxPop = 0,
            peakIntensityMmH = 0.0,
            dayWeatherText = "晴",
            nightWeatherText = "晴"
        )
        risk shouldBe RainRiskLevel.LOW
    }

    @Test
    fun `test marginal precip below threshold evaluates to LOW risk`() {
        val risk = TomorrowRainRuleEngine.evaluateRiskLevel(
            total24hPrecipMm = 0.5,
            maxPop = 20,
            peakIntensityMmH = 0.2,
            dayWeatherText = "多云",
            nightWeatherText = "阴"
        )
        risk shouldBe RainRiskLevel.LOW
    }

    @Test
    fun `test moderate precip evaluates to MODERATE risk`() {
        val risk = TomorrowRainRuleEngine.evaluateRiskLevel(
            total24hPrecipMm = 3.5,
            maxPop = 50,
            peakIntensityMmH = 1.0,
            dayWeatherText = "小雨",
            nightWeatherText = "阴"
        )
        risk shouldBe RainRiskLevel.MODERATE
    }

    @Test
    fun `test heavy rain accumulation 10mm or more evaluates to HIGH risk`() {
        val risk = TomorrowRainRuleEngine.evaluateRiskLevel(
            total24hPrecipMm = 12.0,
            maxPop = 60,
            peakIntensityMmH = 1.5,
            dayWeatherText = "中雨",
            nightWeatherText = "小雨"
        )
        risk shouldBe RainRiskLevel.HIGH
    }

    @Test
    fun `test high rain intensity rate 2_5mm per hour or more evaluates to HIGH risk`() {
        val risk = TomorrowRainRuleEngine.evaluateRiskLevel(
            total24hPrecipMm = 5.0,
            maxPop = 60,
            peakIntensityMmH = 3.0,
            dayWeatherText = "雷阵雨",
            nightWeatherText = "多云"
        )
        risk shouldBe RainRiskLevel.HIGH
    }

    @Test
    fun `test high probability with rain evaluates to HIGH risk`() {
        val risk = TomorrowRainRuleEngine.evaluateRiskLevel(
            total24hPrecipMm = 2.0,
            maxPop = 85,
            peakIntensityMmH = 1.0,
            dayWeatherText = "小雨",
            nightWeatherText = "阴"
        )
        risk shouldBe RainRiskLevel.HIGH
    }

    @Test
    fun `test peak rain window detection with contiguous rain`() {
        val points = listOf(
            TomorrowHourlyPoint("10:00", 0.0, 0.0, 10, "多云", "101"),
            TomorrowHourlyPoint("11:00", 0.0, 0.0, 10, "多云", "101"),
            TomorrowHourlyPoint("12:00", 0.5, 0.5, 50, "小雨", "305"),
            TomorrowHourlyPoint("13:00", 1.8, 1.8, 70, "中雨", "306"),
            TomorrowHourlyPoint("14:00", 0.6, 0.6, 60, "小雨", "305"),
            TomorrowHourlyPoint("15:00", 0.0, 0.0, 20, "阴", "104")
        )
        val window = TomorrowRainRuleEngine.findPeakRainWindow(points)
        window shouldBe "预计集中在 12:00 ~ 14:00"
    }

    @Test
    fun `test peak rain window detection with no rain`() {
        val points = listOf(
            TomorrowHourlyPoint("10:00", 0.0, 0.0, 5, "晴", "100"),
            TomorrowHourlyPoint("11:00", 0.0, 0.0, 5, "晴", "100")
        )
        val window = TomorrowRainRuleEngine.findPeakRainWindow(points)
        window shouldBe "全天无明显降雨"
    }
}
