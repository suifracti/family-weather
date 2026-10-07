/*
 * This file is part of Breezy Weather.
 */
package org.breezyweather.ui.main

import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.View
import android.widget.TextView
import org.breezyweather.domain.multisource.hourly.presentation.TomorrowRainSnapshot
import org.breezyweather.domain.multisource.model.WeatherProvider

/** Visible attribution and scope for the actual source data on the page. */
internal fun TextView.showForecastSourceAttribution(snapshot: TomorrowRainSnapshot, prefix: String = "") {
    visibility = View.VISIBLE
    val hasGlobalModels = snapshot.evidences.any { it.provider == WeatherProvider.OPEN_METEO }
    val note = if (hasGlobalModels) {
        "中国气象局及海外全球模型覆盖本地，经 Open-Meteo 提供（CC BY 4.0）。" +
            "同一模型只计一次；综合来源上游未披露。\n" +
            "Open-Meteo 天气描述为时段结束时刻，降水量和概率对应标注时段。"
    } else {
        "综合来源上游未披露；未知降水量或概率保留为未提供。"
    }
    val value = listOf(prefix, note).filter(String::isNotBlank).joinToString("\n")
    text = SpannableString(value).apply {
        if (hasGlobalModels) {
            val providerStart = value.indexOf("Open-Meteo")
            setSpan(URLSpan("https://open-meteo.com/"), providerStart, providerStart + "Open-Meteo".length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val licenseStart = value.indexOf("CC BY 4.0")
            setSpan(URLSpan("https://creativecommons.org/licenses/by/4.0/"), licenseStart, licenseStart + "CC BY 4.0".length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    movementMethod = LinkMovementMethod.getInstance()
}
