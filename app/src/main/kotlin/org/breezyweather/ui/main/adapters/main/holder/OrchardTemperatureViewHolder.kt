/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.ui.main.adapters.main.holder

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import breezyweather.domain.location.model.Location
import breezyweather.domain.weather.model.Daily
import breezyweather.domain.weather.model.DailyTemperatureChange
import breezyweather.domain.weather.model.Weather
import org.breezyweather.R
import org.breezyweather.common.activities.BreezyActivity
import org.breezyweather.common.extensions.getFormattedDate
import org.breezyweather.common.extensions.getThemeColor
import org.breezyweather.domain.settings.SettingsManager
import breezyweather.domain.source.SourceFeature
import org.breezyweather.ui.common.widgets.trend.TrendLayoutManager
import org.breezyweather.ui.common.widgets.trend.TrendRecyclerView
import org.breezyweather.ui.main.MainActivity
import org.breezyweather.ui.main.adapters.main.OrchardTemperaturePresentation
import org.breezyweather.ui.main.adapters.trend.daily.DailyTemperatureAdapter
import org.breezyweather.ui.main.widgets.TrendRecyclerViewScrollBar
import org.breezyweather.ui.theme.resource.providers.ResourceProvider
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class OrchardTemperatureViewHolder(parent: ViewGroup) : AbstractMainCardViewHolder(
    LayoutInflater.from(parent.context).inflate(R.layout.container_main_orchard_temperature, parent, false)
) {
    private val tomorrowDate: TextView = itemView.findViewById(R.id.orchard_temperature_tomorrow_date)
    private val highValue: TextView = itemView.findViewById(R.id.orchard_temperature_high_value)
    private val highChange: TextView = itemView.findViewById(R.id.orchard_temperature_high_change)
    private val lowValue: TextView = itemView.findViewById(R.id.orchard_temperature_low_value)
    private val lowChange: TextView = itemView.findViewById(R.id.orchard_temperature_low_change)
    private val emptyMessage: TextView = itemView.findViewById(R.id.orchard_temperature_empty)
    private val trendHint: TextView = itemView.findViewById(R.id.orchard_temperature_trend_hint)
    private val trend: TrendRecyclerView = itemView.findViewById(R.id.orchard_temperature_trend)
    private val sourceText: TextView = itemView.findViewById(R.id.orchard_temperature_source)
    private val updatedText: TextView = itemView.findViewById(R.id.orchard_temperature_updated)
    private val scrollBar = TrendRecyclerViewScrollBar()

    init {
        trend.setHasFixedSize(true)
        trend.addItemDecoration(scrollBar)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindView(
        activity: BreezyActivity,
        location: Location,
        provider: ResourceProvider,
        listAnimationEnabled: Boolean,
        itemAnimationEnabled: Boolean,
    ) {
        super.onBindView(activity, location, provider, listAnimationEnabled, itemAnimationEnabled)

        val weather = location.weather
        val now = Date()
        val tomorrow = tomorrowDate(location, now)
        val presentation = OrchardTemperaturePresentation(weather?.dailyForecast.orEmpty(), location.timeZone, now)
        tomorrowDate.text = "明日 · ${tomorrow.getFormattedDate("M月d日", location, activity)}"

        if (weather == null || weather.dailyForecast.isEmpty()) {
            highValue.text = "资料不足"
            lowValue.text = "资料不足"
            highChange.text = "变化资料不足"
            lowChange.text = "变化资料不足"
            emptyMessage.visibility = View.VISIBLE
            trendHint.visibility = View.GONE
            trend.visibility = View.GONE
        } else {
            bindTomorrowRange(location, weather, tomorrow, now, presentation)
            bindTrend(activity, location, weather, provider, presentation)
        }

        sourceText.text = "来源：${forecastSourceName(activity, location)}"
        updatedText.text = weather?.base?.forecastUpdateTime?.let {
            "预报更新时间：${it.getFormattedDate("M月d日 HH:mm", location, activity)}"
        } ?: "预报更新时间：未知"
    }

    @SuppressLint("SetTextI18n")
    private fun bindTomorrowRange(
        location: Location,
        weather: Weather,
        tomorrowDate: Date,
        now: Date,
        presentation: OrchardTemperaturePresentation,
    ) {
        val today = findDaily(weather, location, now)
        val tomorrow = findDaily(weather, location, tomorrowDate)
        val changes = DailyTemperatureChange.between(
            today,
            tomorrow,
            location.timeZone,
            todaySourceId = location.forecastSource,
            tomorrowSourceId = location.forecastSource
        )
        val high = tomorrow?.day?.temperature?.temperature
        val low = tomorrow?.night?.temperature?.temperature

        highValue.text = high?.let { presentation.temperatureText(it) } ?: "最高温资料不足"
        lowValue.text = low?.let { presentation.temperatureText(it) } ?: "最低温资料不足"
        highChange.text = presentation.changeText(changes.high)
        lowChange.text = presentation.changeText(changes.low)
    }

    private fun bindTrend(
        activity: BreezyActivity,
        location: Location,
        weather: Weather,
        provider: ResourceProvider,
        presentation: OrchardTemperaturePresentation,
    ) {
        if (!presentation.hasTemperatureData) {
            emptyMessage.visibility = View.VISIBLE
            trendHint.visibility = View.GONE
            trend.visibility = View.GONE
            return
        }
        emptyMessage.visibility = View.GONE
        trendHint.visibility = View.VISIBLE
        trend.visibility = View.VISIBLE
        trendHint.text = "未来高低温（℃） · 点按日期查看逐小时详情"
        trend.contentDescription = "未来高低温，单位摄氏度，点按日期查看逐小时详情"
        trend.layoutManager = TrendLayoutManager(activity)
        trend.setLineColor(activity.getThemeColor(com.google.android.material.R.attr.colorOutline))
        trend.setTextColor(activity.getThemeColor(R.attr.colorBodyText))
        trend.setKeyLineVisibility(SettingsManager.getInstance(activity).isTrendHorizontalLinesEnabled)

        val adapter = DailyTemperatureAdapter(
            activity,
            location.copy(weather = weather.copy(dailyForecast = presentation.dailyForecast)),
            provider,
            OrchardTemperaturePresentation.temperatureUnit,
            showPrecipitationProbability = false,
            presentation = presentation
        )
        trend.adapter = adapter
        adapter.bindBackgroundForHost(trend)
        trend.scrollToPosition(0)
        scrollBar.resetColor(activity)
    }

    private fun forecastSourceName(activity: BreezyActivity, location: Location): String {
        val sourceManager = (activity as? MainActivity)?.sourceManager
        return sourceManager
            ?.getWeatherSource(location.forecastSource)
            ?.supportedFeatures
            ?.getOrElse(SourceFeature.FORECAST) { null }
            ?.takeIf { it.isNotBlank() }
            ?: location.forecastSource
    }

    private fun findDaily(weather: Weather, location: Location, date: Date): Daily? {
        val targetDate = localDateFormatter(location).format(date)
        return weather.dailyForecast.firstOrNull {
            localDateFormatter(location).format(it.date) == targetDate
        }
    }

    private fun tomorrowDate(location: Location, now: Date): Date = Calendar.getInstance(location.timeZone).run {
        time = now
        add(Calendar.DAY_OF_YEAR, 1)
        time
    }

    private fun localDateFormatter(location: Location) =
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = location.timeZone }
}
