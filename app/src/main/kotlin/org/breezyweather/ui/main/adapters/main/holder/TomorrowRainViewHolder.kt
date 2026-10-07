/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.ui.main.adapters.main.holder

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import breezyweather.domain.location.model.Location
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import org.breezyweather.R
import org.breezyweather.common.activities.BreezyActivity
import org.breezyweather.common.extensions.getThemeColor
import org.breezyweather.domain.multisource.hourly.presentation.HourlyForecastDisplayRow
import org.breezyweather.domain.multisource.hourly.presentation.RainSlotDisplaySummary
import org.breezyweather.domain.multisource.hourly.presentation.TomorrowRainPresentation
import org.breezyweather.domain.multisource.hourly.presentation.TomorrowRainSnapshot
import org.breezyweather.domain.multisource.hourly.service.TomorrowRainEvidenceService
import org.breezyweather.ui.main.TomorrowRainEvidenceActivity
import org.breezyweather.ui.main.TomorrowRainEvidenceSession
import org.breezyweather.ui.main.TomorrowRainHourlyActivity
import org.breezyweather.ui.main.showForecastSourceAttribution
import org.breezyweather.ui.theme.resource.providers.ResourceProvider
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

class TomorrowRainViewHolder(parent: ViewGroup) : AbstractMainCardViewHolder(
    LayoutInflater.from(parent.context).inflate(R.layout.container_main_tomorrow_rain_card, parent, false)
) {
    private val locationText: TextView = itemView.findViewById(R.id.tomorrow_location_text)
    private val todayButton: MaterialButton = itemView.findViewById(R.id.tomorrow_today_button)
    private val tomorrowButton: MaterialButton = itemView.findViewById(R.id.tomorrow_tomorrow_button)
    private val dateText: TextView = itemView.findViewById(R.id.tomorrow_date_text)
    private val updateText: TextView = itemView.findViewById(R.id.tomorrow_update_text)
    private val forecastColumns: LinearLayout = itemView.findViewById(R.id.tomorrow_forecast_columns)
    private val hourlyList: LinearLayout = itemView.findViewById(R.id.tomorrow_slot_row)
    private val hourlyScroll: NestedScrollView = itemView.findViewById(R.id.tomorrow_hourly_scroll)
    private val hourLocator: HourLocatorView = itemView.findViewById(R.id.tomorrow_hour_locator)
    private val retryButton: MaterialButton = itemView.findViewById(R.id.btn_retry_forecast)

    private var mDisposable: Disposable? = null
    private var selectedTargetDate: String? = null
    private var selectedTomorrow = false
    private var boundLocationKey: String? = null
    private var bindGeneration = 0L
    private var lastActivity: BreezyActivity? = null
    private var lastLocation: Location? = null
    private var hourlyRowViews: List<View> = emptyList()

    init {
        hourLocator.onHourSelected = { index ->
            hourlyRowViews.getOrNull(index)?.let { row -> hourlyScroll.scrollTo(0, row.top) }
        }
        hourlyScroll.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ -> syncHourLocator(scrollY) }
        )
    }

    @SuppressLint("SetTextI18n")
    override fun onBindView(
        activity: BreezyActivity,
        location: Location,
        provider: ResourceProvider,
        listAnimationEnabled: Boolean,
        itemAnimationEnabled: Boolean
    ) {
        super.onBindView(activity, location, provider, listAnimationEnabled, itemAnimationEnabled)

        lastActivity = activity
        lastLocation = location
        val target = org.breezyweather.domain.multisource.location.LocationAuthority.getAgriculturalPrimary()
        boundLocationKey = target.canonicalLocationId
        selectedTargetDate = TomorrowRainPresentation.todayDateLocal(System.currentTimeMillis(), target.timeZoneId)
        selectedTomorrow = false
        locationText.text = target.displayName
        todayButton.setOnClickListener { selectForecastDate(isTomorrow = false) }
        tomorrowButton.setOnClickListener { selectForecastDate(isTomorrow = true) }
        retryButton.setOnClickListener { retryCurrentBind() }
        updateDayToggle(activity)
        loadForecastForDate(activity, location, target, selectedTargetDate!!)
    }

    private fun loadForecastForDate(
        activity: BreezyActivity,
        location: Location,
        target: org.breezyweather.domain.multisource.location.TargetLocation,
        requestedTargetDate: String,
    ) {
        val requestGeneration = ++bindGeneration
        mDisposable?.dispose()
        selectedTargetDate = requestedTargetDate
        boundLocationKey = target.canonicalLocationId
        dateText.text = "${dayLabel(requestedTargetDate, target.timeZoneId)} $requestedTargetDate"
        updateText.text = "获取中"
        setUpdateTextStyle(activity, isOld = false)
        retryButton.visibility = View.GONE
        renderMessage(activity, "正在整理逐小时预报")

        mDisposable = TomorrowRainEvidenceService.getInstance(activity)
            .getForecastForDate(location, requestedTargetDate)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ snapshot ->
                if (requestGeneration != bindGeneration ||
                    snapshot.targetLocation.canonicalLocationId != boundLocationKey ||
                    snapshot.targetDateLocal != selectedTargetDate
                ) return@subscribe
                if (snapshot.evidences.isEmpty()) {
                    bindNoSnapshot(activity, snapshot)
                } else {
                    bindSnapshot(activity, snapshot)
                }
            }, {
                // The provider service normally fail-closes individual sources.
                // This is only the outer unexpected failure path.
                if (requestGeneration == bindGeneration) bindOuterFailure(activity)
            })
    }

    private fun selectForecastDate(isTomorrow: Boolean) {
        val activity = lastActivity ?: return
        val location = lastLocation ?: return
        val target = org.breezyweather.domain.multisource.location.LocationAuthority.getAgriculturalPrimary()
        val now = System.currentTimeMillis()
        val requestedDate = if (isTomorrow) {
            TomorrowRainPresentation.tomorrowDateLocal(now, target.timeZoneId)
        } else {
            TomorrowRainPresentation.todayDateLocal(now, target.timeZoneId)
        }
        if (requestedDate == selectedTargetDate) {
            selectedTomorrow = isTomorrow
            updateDayToggle(activity)
            return
        }
        selectedTomorrow = isTomorrow
        selectedTargetDate = requestedDate
        updateDayToggle(activity)
        loadForecastForDate(activity, location, target, requestedDate)
    }

    private fun dayLabel(targetDate: String, timeZoneId: String): String =
        if (targetDate == TomorrowRainPresentation.todayDateLocal(System.currentTimeMillis(), timeZoneId)) "今天" else "明天"

    private fun updateDayToggle(activity: BreezyActivity) {
        listOf(todayButton to !selectedTomorrow, tomorrowButton to selectedTomorrow)
            .forEach { (button, selected) ->
                button.backgroundTintList = null
                button.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(activity, 12).toFloat()
                    setColor(
                        if (selected) activity.getThemeColor(com.google.android.material.R.attr.colorSecondaryContainer)
                        else activity.getThemeColor(com.google.android.material.R.attr.colorSurface)
                    )
                    setStroke(
                        dp(activity, if (selected) 2 else 1),
                        if (selected) activity.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
                        else activity.getThemeColor(com.google.android.material.R.attr.colorOutline)
                    )
                }
                button.isSelected = selected
                button.contentDescription = if (selected) "已选择${button.text}" else "查看${button.text}天气"
            }
    }

    private fun setUpdateTextStyle(activity: BreezyActivity, isOld: Boolean) {
        updateText.setTextColor(
            if (isOld) activityColorError()
            else activity.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        )
        updateText.setTypeface(updateText.typeface, if (isOld) Typeface.BOLD else Typeface.NORMAL)
    }

    @SuppressLint("SetTextI18n")
    private fun bindSnapshot(activity: BreezyActivity, snapshot: TomorrowRainSnapshot) {
        dateText.text = "${dayLabel(snapshot.targetDateLocal, snapshot.timeZoneId)} ${snapshot.targetDateLocal}"
        updateText.text = TomorrowRainReadTimeText.build(snapshot)
        setUpdateTextStyle(activity, TomorrowRainReadTimeText.isOld(snapshot))
        retryButton.visibility = View.GONE
        renderHourlyRows(activity, snapshot)
    }

    @SuppressLint("SetTextI18n")
    private fun bindNoSnapshot(activity: BreezyActivity, snapshot: TomorrowRainSnapshot) {
        dateText.text = "${dayLabel(snapshot.targetDateLocal, snapshot.timeZoneId)} ${snapshot.targetDateLocal}"
        updateText.text = "暂无有效数据"
        setUpdateTextStyle(activity, isOld = false)
        renderMessage(activity, "这一天没有有效预报。可切换日期，或重试获取。")
        showRetryButton()
    }

    @SuppressLint("SetTextI18n")
    private fun bindOuterFailure(activity: BreezyActivity) {
        updateText.text = "请求失败"
        setUpdateTextStyle(activity, isOld = false)
        val target = org.breezyweather.domain.multisource.location.LocationAuthority.getAgriculturalPrimary()
        selectedTargetDate?.let { dateText.text = "${dayLabel(it, target.timeZoneId)} $it" }
        renderMessage(activity, "本日期没有取得有效预报。可重试或切换日期。")
        showRetryButton()
    }

    private fun showRetryButton() {
        retryButton.text = "重试获取预报"
        retryButton.visibility = View.VISIBLE
    }

    private fun renderMessage(activity: BreezyActivity, message: String) {
        hourlyList.removeAllViews()
        hourlyRowViews = emptyList()
        hourLocator.setHours(emptyList())
        hourLocator.visibility = View.GONE
        hourlyScroll.scrollTo(0, 0)
        hourlyList.addView(TextView(activity).apply {
            text = message
            textSize = 15f
            setTextColor(activity.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(activity, 12), 0, dp(activity, 12))
        })
    }

    private fun renderHourlyRows(activity: BreezyActivity, snapshot: TomorrowRainSnapshot) {
        hourlyList.removeAllViews()
        hourlyRowViews = emptyList()
        val stackedForecastRows = useStackedForecastRows(activity)
        forecastColumns.visibility = if (stackedForecastRows) View.GONE else View.VISIBLE
        val now = System.currentTimeMillis()
        val rows = TomorrowRainPresentation.hourlyDisplayRows(snapshot, now)
        val isToday = snapshot.targetDateLocal == TomorrowRainPresentation.todayDateLocal(now, snapshot.timeZoneId)
        val currentHour = Calendar.getInstance(TimeZone.getTimeZone(snapshot.timeZoneId), Locale.CHINA)
            .apply { timeInMillis = now }
            .get(Calendar.HOUR_OF_DAY)

        val rowViews = mutableListOf<View>()
        rows.forEachIndexed { index, row ->
            val rowView = createHourRow(activity, snapshot, row, isToday && row.hour == currentHour, stackedForecastRows)
            rowView.tag = row.hour
            rowViews += rowView
            hourlyList.addView(rowView)
            if (index < rows.lastIndex) hourlyList.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 1))
                setBackgroundColor(activity.getThemeColor(com.google.android.material.R.attr.colorOutline))
            })
        }
        if (rows.isEmpty()) {
            renderMessage(activity, "今天已没有后续小时预报。")
        } else {
            hourlyList.addView(TextView(activity).apply {
                textSize = 12f
                setTextColor(activity.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(activity, 12), 0, dp(activity, 8))
                showForecastSourceAttribution(snapshot)
            })
            hourlyRowViews = rowViews
            hourLocator.visibility = View.VISIBLE
            hourLocator.setHours(rows.map { "%02d点".format(Locale.US, it.hour) })
            hourlyScroll.scrollTo(0, 0)
            hourlyScroll.post { syncHourLocator(hourlyScroll.scrollY) }
        }
    }

    private fun syncHourLocator(scrollY: Int) {
        if (hourlyRowViews.isEmpty()) return
        val index = hourlyRowViews.indexOfFirst { it.bottom > scrollY }
            .takeIf { it >= 0 }
            ?: hourlyRowViews.lastIndex
        hourLocator.setSelectedIndex(index)
    }

    private fun createHourRow(
        activity: BreezyActivity,
        snapshot: TomorrowRainSnapshot,
        row: HourlyForecastDisplayRow,
        isCurrentHour: Boolean,
        stackedForecastRows: Boolean,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(activity, 10), 0, dp(activity, 10))
        contentDescription = "${TomorrowRainPresentation.hourIntervalLabel(row.hour)}"

        addView(LinearLayout(activity).apply {
            orientation = if (stackedForecastRows) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = TomorrowRainPresentation.hourIntervalLabel(row.hour)
                textSize = 16f
                setTextColor(activity.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = if (stackedForecastRows) {
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                } else {
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
            })
            if (isCurrentHour) addView(TextView(activity).apply {
                text = "本小时预报"
                textSize = 12f
                setTextColor(activity.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(dp(activity, 4), 0, dp(activity, 8), 0)
            })
            addView(TextView(activity).apply {
                text = "来源详情"
                textSize = 13f
                gravity = Gravity.CENTER
                minHeight = dp(activity, 48)
                setTextColor(activity.getThemeColor(androidx.appcompat.R.attr.colorPrimary))
                isClickable = true
                isFocusable = true
                keepHomeScrollFromStealingTouch(this)
                setOnClickListener { openSourceDetails(activity, snapshot, row.hour) }
                contentDescription = "查看${TomorrowRainPresentation.hourIntervalLabel(row.hour)}来源详情"
            })
        })

        row.forecasts.forEach { forecast ->
            val probabilityText = forecast.probabilityPercent?.let { "$it%" } ?: "未提供"
            val amountText = forecast.amountMm?.let { "${String.format(Locale.US, "%.1f", it)}毫米" } ?: "未提供"
            addView(LinearLayout(activity).apply {
                orientation = if (stackedForecastRows) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(activity, 30)
                if (stackedForecastRows) setPadding(0, dp(activity, 6), 0, dp(activity, 6))
                addView(TextView(activity).apply {
                    text = forecast.displayName
                    textSize = 14f
                    setTextColor(activity.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                    if (stackedForecastRows) setTypeface(typeface, Typeface.BOLD)
                    layoutParams = if (stackedForecastRows) {
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    } else {
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                })
                addView(TextView(activity).apply {
                    text = if (stackedForecastRows) "降水概率：$probabilityText" else probabilityText
                    textSize = 14f
                    gravity = if (stackedForecastRows) Gravity.START else Gravity.END
                    setTextColor(activity.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                    layoutParams = LinearLayout.LayoutParams(
                        if (stackedForecastRows) ViewGroup.LayoutParams.MATCH_PARENT else dp(activity, 64),
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                })
                addView(TextView(activity).apply {
                    text = listOfNotNull(
                        if (stackedForecastRows) "本小时降水量：$amountText"
                        else if (forecast.amountMm == null) "降水量未提供" else amountText,
                        forecast.weatherDescription?.let { if (stackedForecastRows) "天气：$it" else it },
                    ).joinToString("\n")
                    textSize = if (stackedForecastRows) 14f else 13f
                    gravity = if (stackedForecastRows) Gravity.START else Gravity.END
                    setTextColor(activity.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    layoutParams = LinearLayout.LayoutParams(
                        if (stackedForecastRows) ViewGroup.LayoutParams.MATCH_PARENT else dp(activity, 82),
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                })
            })
        }
        if (row.forecasts.isEmpty()) addView(TextView(activity).apply {
            text = "这个小时暂时查不到有效预报"
            textSize = 14f
            setTextColor(activity.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(activity, 4), 0, 0)
        })

        hourlyOutcomeSummary(row.summary).takeIf { it.isNotEmpty() }?.let { summary ->
            addView(TextView(activity).apply {
                text = summary
                textSize = 13f
                setTextColor(activity.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(activity, 5), 0, 0)
                minHeight = dp(activity, 48)
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                contentDescription = "$summary，点按查看计入的预报"
                keepHomeScrollFromStealingTouch(this)
                setOnClickListener { TomorrowRainHourlyActivity.start(activity, snapshot, row.hour) }
            })
        }
    }

    private fun useStackedForecastRows(activity: BreezyActivity): Boolean {
        val density = activity.resources.displayMetrics.density
        val cardMargins = itemView.layoutParams as? ViewGroup.MarginLayoutParams
        val availableWidthPx = hourlyScroll.width.takeIf { it > 0 }?.toFloat()
            ?: (activity.resources.configuration.screenWidthDp * density -
                (cardMargins?.leftMargin ?: 0) - (cardMargins?.rightMargin ?: 0) -
                itemView.paddingStart - itemView.paddingEnd)
        val contentWidthDp = (availableWidthPx - hourlyScroll.paddingStart - hourlyScroll.paddingEnd) / density
        return contentWidthDp < 280f || activity.resources.configuration.fontScale >= 1.15f
    }

    private fun hourlyOutcomeSummary(summary: RainSlotDisplaySummary): String {
        if (summary.totalForecastCount == 0) return ""
        return buildList {
            add("综合${summary.totalForecastCount}份预报")
            if (summary.rainForecastCount > 0) add("${summary.rainForecastCount}份预报说有雨")
            if (summary.noObviousPrecipitationForecastCount > 0) add("${summary.noObviousPrecipitationForecastCount}份没报雨")
            if (summary.insufficientForecastCount > 0) add("${summary.insufficientForecastCount}份资料不足")
        }.joinToString(" · ")
    }

    private fun openSourceDetails(activity: BreezyActivity, snapshot: TomorrowRainSnapshot, hour: Int) {
        val slotIndex = TomorrowRainPresentation.slotDefinitions.indexOfFirst {
            hour in it.startHour until it.endHour
        }.coerceAtLeast(0)
        TomorrowRainEvidenceSession.open(snapshot, slotIndex)
        TomorrowRainEvidenceActivity.start(activity, snapshot, slotIndex)
    }

    private fun keepHomeScrollFromStealingTouch(view: View) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> itemView.parent?.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> itemView.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private fun retryCurrentBind() {
        val activity = lastActivity ?: return
        val location = lastLocation ?: return
        val target = org.breezyweather.domain.multisource.location.LocationAuthority.getAgriculturalPrimary()
        val now = System.currentTimeMillis()
        selectedTargetDate = if (selectedTomorrow) {
            TomorrowRainPresentation.tomorrowDateLocal(now, target.timeZoneId)
        } else {
            TomorrowRainPresentation.todayDateLocal(now, target.timeZoneId)
        }
        updateDayToggle(activity)
        loadForecastForDate(activity, location, target, selectedTargetDate!!)
    }

    private fun activityColorError(): Int = ContextCompat.getColor(itemView.context, R.color.md_theme_error)

    private fun dp(activity: BreezyActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).roundToInt()

    override fun onRecycleView() {
        super.onRecycleView()
        bindGeneration++
        mDisposable?.dispose()
        mDisposable = null
        lastActivity = null
        lastLocation = null
        hourlyRowViews = emptyList()
        hourLocator.onHourSelected = null
    }
}

internal object TomorrowRainReadTimeText {
    private const val OLD_FORECAST_AFTER_MS = 6L * 60L * 60L * 1000L

    fun isOld(snapshot: TomorrowRainSnapshot, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        nowEpochMs - snapshot.fetchedAtEpochMs > OLD_FORECAST_AFTER_MS

    fun build(snapshot: TomorrowRainSnapshot, nowEpochMs: Long = System.currentTimeMillis()): String {
        val readAt = snapshot.fetchedAtEpochMs
            .takeIf { it > 0L }
            ?.let { epochMs ->
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply {
                    timeZone = TimeZone.getTimeZone(snapshot.timeZoneId)
                }.format(Date(epochMs))
            }
        return when {
            isOld(snapshot, nowEpochMs) && readAt != null -> "旧预报 · 读取于 $readAt"
            isOld(snapshot, nowEpochMs) -> "旧预报 · 读取时间未知"
            snapshot.recoveredFromStorage -> if (readAt != null) "已恢复 · 读取于 $readAt" else "已恢复 · 读取时间未知"
            readAt != null -> "读取于 $readAt"
            else -> "读取时间未知"
        }
    }
}
