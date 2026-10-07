/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.tomorrow

import java.io.Serializable

data class TomorrowHourlyPoint(
    val hourText: String,               // e.g. "14:00"
    val precipMm: Double,               // Precipitation amount for this hour (mm)
    val intensityMmH: Double,           // Precipitation intensity (mm/h)
    val pop: Int,                       // Probability of precipitation (0 ~ 100%)
    val weatherText: String,            // Weather condition description
    val iconCode: String                // Weather condition icon code
) : Serializable

enum class RainRiskLevel(val label: String, val ruleDescription: String) {
    LOW(
        label = "明日降雨风险较低",
        ruleDescription = "预计24h累计<1.0mm且最大概率<30%"
    ),
    MODERATE(
        label = "明日存在降雨风险",
        ruleDescription = "预计24h累计1.0~10.0mm或概率30%~70%"
    ),
    HIGH(
        label = "预计有明显降雨，请结合最新预报判断",
        ruleDescription = "预计24h累计≥10.0mm(冲刷风险)或概率≥70%或雨强≥2.5mm/h"
    );
}

data class TomorrowRainData(
    val date: String,                   // yyyy-MM-dd
    val dayWeatherText: String,         // 明日白天天气现象
    val dayWeatherIcon: String,         // 明日白天图标代码
    val nightWeatherText: String,       // 明日夜间天气现象
    val nightWeatherIcon: String,       // 明日夜间图标代码
    val dayPrecipAmountMm: Double,      // 明日白天预计降雨量 (mm)
    val dayMaxPop: Int,                 // 明日白天最大降水概率 (0~100)
    val nightPrecipAmountMm: Double,    // 明日夜间预计降雨量 (mm)
    val nightMaxPop: Int,               // 明日夜间最大降水概率 (0~100)
    val total24hPrecipMm: Double,       // 明日全天预计累计降雨量 (mm)
    val peakIntensityMmH: Double,       // 明日最高降水强度 (mm/h)
    val peakRainWindow: String,         // 预计降雨主要时段 (如 "14:00 ~ 18:00" 或 "全天无明显降雨")
    val hourlyBreakdown: List<TomorrowHourlyPoint>, // 明日逐小时
    val riskLevel: RainRiskLevel,       // 审计风险评级
    val updateTime: String,             // 和风 API 数据发布时间
    val attribution: String = "和风天气 QWeather",
    val isConfigured: Boolean = true,   // Key 是否已配置
    val errorMessage: String? = null
) : Serializable

object TomorrowRainRuleEngine {
    const val THRESHOLD_LOW_PRECIP_MM = 1.0
    const val THRESHOLD_LOW_POP_PERCENT = 30
    const val THRESHOLD_HIGH_PRECIP_MM = 10.0
    const val THRESHOLD_HIGH_POP_PERCENT = 70
    const val THRESHOLD_HIGH_INTENSITY_MM_H = 2.5

    fun evaluateRiskLevel(
        total24hPrecipMm: Double,
        maxPop: Int,
        peakIntensityMmH: Double,
        dayWeatherText: String = "",
        nightWeatherText: String = ""
    ): RainRiskLevel {
        val weatherHasSevereRain = dayWeatherText.contains("暴雨") || dayWeatherText.contains("大雨") ||
                nightWeatherText.contains("暴雨") || nightWeatherText.contains("大雨") ||
                dayWeatherText.contains("雷阵雨")

        // HIGH: 累计大于等于10mm (足以冲刷肥料), 或 高概率且有实际降水, 或 瞬时雨强达2.5mm/h以上, 或 明确预报大雨/暴雨/雷雨
        if (total24hPrecipMm >= THRESHOLD_HIGH_PRECIP_MM ||
            (maxPop >= THRESHOLD_HIGH_POP_PERCENT && total24hPrecipMm >= 1.0) ||
            peakIntensityMmH >= THRESHOLD_HIGH_INTENSITY_MM_H ||
            weatherHasSevereRain
        ) {
            return RainRiskLevel.HIGH
        }

        // LOW: 累计不足1.0mm 且 最大概率低于30% 且 现象无雨
        val weatherHasRain = dayWeatherText.contains("雨") || nightWeatherText.contains("雨")
        if (total24hPrecipMm < THRESHOLD_LOW_PRECIP_MM && maxPop < THRESHOLD_LOW_POP_PERCENT && !weatherHasRain) {
            return RainRiskLevel.LOW
        }

        // MODERATE
        return RainRiskLevel.MODERATE
    }

    /**
     * Finds the contiguous rain window with precip >= 0.1 mm/h
     */
    fun findPeakRainWindow(points: List<TomorrowHourlyPoint>): String {
        val rainyPoints = points.filter { it.precipMm > 0.0 || it.pop >= 40 }
        if (rainyPoints.isEmpty()) {
            return "全天无明显降雨"
        }
        val firstHour = rainyPoints.first().hourText
        val lastHour = rainyPoints.last().hourText
        return if (firstHour == lastHour) {
            "预计集中在 $firstHour 左右"
        } else {
            "预计集中在 $firstHour ~ $lastHour"
        }
    }
}
