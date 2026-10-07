/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.ui.main

import org.breezyweather.domain.multisource.model.PrecipitationPhase
import java.util.Locale

/** Human-readable weather amount levels. These labels do not assess farm work. */
internal object RainAmountText {

    fun describeRainAmount(valueMm: Double): String {
        val description = when {
            valueMm <= 0.0 -> "无明显降水"
            valueMm <= 0.5 -> "雨很小"
            valueMm <= 2.5 -> "小雨"
            valueMm <= 10.0 -> "中雨"
            valueMm <= 25.0 -> "雨较明显"
            valueMm <= 50.0 -> "大雨"
            else -> "大雨"
        }
        return "$description（${String.format(Locale.US, "%.1f", valueMm)} 毫米）"
    }

    fun describeRainAmountLevel(valueMm: Double): String =
        describeRainAmount(valueMm).substringBefore("（")

    fun describeRainAmountCompact(valueMm: Double): String = when {
        valueMm <= 0.0 -> "无明显降水"
        valueMm <= 0.5 -> "雨很小"
        valueMm <= 2.5 -> "小雨"
        valueMm <= 10.0 -> "中雨"
        valueMm <= 25.0 -> "雨较明显"
        valueMm <= 50.0 -> "大雨"
        else -> "大雨"
    }

    fun describeForPhase(valueMm: Double, phase: PrecipitationPhase): String {
        val amount = String.format(Locale.US, "%.1f", valueMm)
        return when (phase) {
            PrecipitationPhase.RAIN -> describeRainAmount(valueMm)
            PrecipitationPhase.SNOW -> "降雪量 ${amount} 毫米"
            PrecipitationPhase.MIXED -> "雨雪混合，降水量 ${amount} 毫米"
            PrecipitationPhase.OTHER -> if (valueMm <= 0.0) {
                "无明显降水（${amount} 毫米）"
            } else {
                "降水量 ${amount} 毫米"
            }
            PrecipitationPhase.UNKNOWN -> "降水类型暂不确定（${amount} 毫米）"
        }
    }

    /** Qualitative home-card wording; exact millimetres stay in source detail. */
    fun describeForPhaseLevel(valueMm: Double, phase: PrecipitationPhase): String = when (phase) {
        PrecipitationPhase.RAIN -> describeRainAmountLevel(valueMm)
        PrecipitationPhase.SNOW -> "降雪"
        PrecipitationPhase.MIXED -> "雨雪混合"
        PrecipitationPhase.OTHER -> if (valueMm <= 0.0) "无明显降水" else "降水"
        PrecipitationPhase.UNKNOWN -> "降水类型暂不确定"
    }

    fun describeForPhaseCompact(valueMm: Double, phase: PrecipitationPhase): String {
        return when (phase) {
            PrecipitationPhase.RAIN -> "${describeRainAmountCompact(valueMm)} ${String.format(Locale.US, "%.1f", valueMm)}毫米"
            PrecipitationPhase.SNOW -> "降雪 ${String.format(Locale.US, "%.1f", valueMm)}毫米"
            PrecipitationPhase.MIXED -> "雨雪混合 ${String.format(Locale.US, "%.1f", valueMm)}毫米"
            PrecipitationPhase.OTHER -> if (valueMm <= 0.0) "无明显降水" else "降水 ${String.format(Locale.US, "%.1f", valueMm)}毫米"
            PrecipitationPhase.UNKNOWN -> "降水类型暂不确定"
        }
    }
}
