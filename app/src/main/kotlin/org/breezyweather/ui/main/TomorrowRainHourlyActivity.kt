/*
 * This file is part of Breezy Weather.
 */
package org.breezyweather.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.breezyweather.common.activities.BreezyActivity
import org.breezyweather.common.extensions.getThemeColor
import org.breezyweather.databinding.ActivityTomorrowRainHourlyBinding
import org.breezyweather.domain.multisource.hourly.presentation.HourlyEvidenceText
import org.breezyweather.domain.multisource.hourly.presentation.HourlyForecastDisplayRow
import org.breezyweather.domain.multisource.hourly.presentation.TomorrowRainPresentation
import org.breezyweather.domain.multisource.hourly.presentation.TomorrowRainSnapshot
import org.breezyweather.domain.multisource.hourly.service.TomorrowRainSnapshotStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

class TomorrowRainHourlyActivity : BreezyActivity() {
    private lateinit var binding: ActivityTomorrowRainHourlyBinding
    private lateinit var snapshotStore: TomorrowRainSnapshotStore
    private var currentSnapshot: TomorrowRainSnapshot? = null
    private var expectedLocationId: String? = null
    private var selectedDate: String? = null
    private var initialHourTarget: View? = null
    private var firstRender = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTomorrowRainHourlyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        snapshotStore = TomorrowRainSnapshotStore.from(this)
        expectedLocationId = intent.getStringExtra(EXTRA_LOCATION_ID)
        selectedDate = intent.getStringExtra(EXTRA_TARGET_DATE)
        binding.toolbar.title = "每小时降水预报"
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.hourlyTodayButton.setOnClickListener { selectRelativeDay(false) }
        binding.hourlyTomorrowButton.setOnClickListener { selectRelativeDay(true) }
    }

    override fun onResume() {
        super.onResume()
        reloadForCurrentTime()
    }

    private fun selectRelativeDay(tomorrow: Boolean) {
        val base = currentSnapshot ?: return
        val now = System.currentTimeMillis()
        selectedDate = if (tomorrow) TomorrowRainPresentation.tomorrowDateLocal(now, base.timeZoneId)
        else TomorrowRainPresentation.todayDateLocal(now, base.timeZoneId)
        reloadForCurrentTime()
    }

    private fun reloadForCurrentTime() {
        val now = System.currentTimeMillis()
        val resolved = currentSnapshot ?: TomorrowRainEvidenceSession.resolve(
            context = this,
            expectedLocationId = expectedLocationId,
            expectedTargetDate = selectedDate,
        )
        if (resolved == null) {
            Toast.makeText(this, "预报记录已失效，请返回首页重试获取预报。", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val today = TomorrowRainPresentation.todayDateLocal(now, resolved.timeZoneId)
        val tomorrow = TomorrowRainPresentation.tomorrowDateLocal(now, resolved.timeZoneId)
        if (selectedDate !in setOf(today, tomorrow)) selectedDate = today
        val stored = snapshotStore.load(resolved.targetLocation, selectedDate!!, now)
        val snapshot = stored ?: resolved.takeIf {
            it.targetDateLocal == selectedDate && it.targetLocation.canonicalLocationId == expectedLocationId
        }
        if (snapshot == null) {
            Toast.makeText(this, "这一天还没有预报，请返回首页重试。", Toast.LENGTH_LONG).show()
            binding.hourlyLocationText.text = resolved.targetLocation.displayName
            binding.hourlyDateText.text = formatDate(selectedDate!!)
            binding.hourlySourceText.text = "暂无这一天的预报"
            binding.hourlyCompareSources.setOnClickListener(null)
            binding.hourlyCompareSources.isEnabled = false
            initialHourTarget = null
            binding.hourlyDayFlipper.removeAllViews()
            binding.hourlyDayFlipper.addView(detailText("这一天还没有预报，请返回首页重试获取。"))
            binding.hourlyDayFlipper.displayedChild = 0
            binding.hourlyScroll.scrollTo(0, 0)
            updateDayButtons(today, tomorrow)
            return
        }
        currentSnapshot = snapshot
        render(snapshot, now)
    }

    private fun render(snapshot: TomorrowRainSnapshot, now: Long) {
        binding.hourlyLocationText.text = snapshot.targetLocation.displayName
        binding.hourlyDateText.text = formatDate(snapshot.targetDateLocal)
        val readTimeText = when {
            now - snapshot.fetchedAtEpochMs > OLD_FORECAST_AFTER_MS -> "旧预报 · 读取于${formatReadTime(snapshot.fetchedAtEpochMs, snapshot.timeZoneId)}"
            snapshot.recoveredFromStorage -> "已恢复 · 读取于${formatReadTime(snapshot.fetchedAtEpochMs, snapshot.timeZoneId)}"
            else -> "读取于${formatReadTime(snapshot.fetchedAtEpochMs, snapshot.timeZoneId)}"
        }
        binding.hourlySourceText.showForecastSourceAttribution(snapshot, readTimeText)
        binding.hourlyCompareSources.isEnabled = true
        binding.hourlyCompareSources.setOnClickListener {
            TomorrowRainEvidenceActivity.start(this, snapshot, TomorrowRainEvidenceSession.selectedSlotIndex)
        }
        val today = TomorrowRainPresentation.todayDateLocal(now, snapshot.timeZoneId)
        val tomorrow = TomorrowRainPresentation.tomorrowDateLocal(now, snapshot.timeZoneId)
        updateDayButtons(today, tomorrow)
        val initialHour = if (firstRender) intent.getIntExtra(EXTRA_INITIAL_HOUR, -1) else -1
        firstRender = false
        initialHourTarget = null
        binding.hourlyDayFlipper.removeAllViews()
        binding.hourlyDayFlipper.addView(createForecastDayPage(snapshot, TomorrowRainPresentation.hourlyDisplayRows(snapshot, now), initialHour))
        binding.hourlyDayFlipper.displayedChild = 0
        binding.hourlyScroll.post { initialHourTarget?.let(::scrollToInitialHour) }
    }

    private fun updateDayButtons(today: String, tomorrow: String) {
        binding.hourlyTodayButton.text = "今天"
        binding.hourlyTomorrowButton.text = "明天"
        binding.hourlyTodayButton.isChecked = selectedDate == today
        binding.hourlyTomorrowButton.isChecked = selectedDate == tomorrow
    }

    private fun createForecastDayPage(
        snapshot: TomorrowRainSnapshot,
        rows: List<HourlyForecastDisplayRow>,
        initialHour: Int,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            rows.forEachIndexed { index, row ->
                val rowView = createHourRow(snapshot, row)
                if (row.hour == initialHour) initialHourTarget = rowView
                addView(rowView)
                if (index < rows.lastIndex) addView(View(this@TomorrowRainHourlyActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                    setBackgroundColor(getThemeColor(com.google.android.material.R.attr.colorOutline))
                })
            }
        }

    private fun createHourRow(snapshot: TomorrowRainSnapshot, row: HourlyForecastDisplayRow): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        minimumHeight = dp(68)
        setPadding(0, dp(12), 0, dp(12))
        val hourLabel = TomorrowRainPresentation.hourIntervalLabel(row.hour)
        contentDescription = "$hourLabel，综合${row.summary.totalForecastCount}份预报"
        addView(TextView(this@TomorrowRainHourlyActivity).apply {
            text = hourLabel
            textSize = 17f
            setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            setTypeface(typeface, Typeface.BOLD)
        })
        if (row.summary.totalForecastCount > 0) addView(
            detailText(buildString {
                append("综合${row.summary.totalForecastCount}份预报")
                append(" · 独立模型${row.summary.independentModelCount}个")
                if (row.summary.compositeForecastCount > 0) append(" · 综合来源${row.summary.compositeForecastCount}份")
                if (row.summary.rainForecastCount > 0) append(" · ${row.summary.rainForecastCount}份预报说有雨")
                if (row.summary.noObviousPrecipitationForecastCount > 0) append(" · ${row.summary.noObviousPrecipitationForecastCount}份没报雨")
                if (row.summary.insufficientForecastCount > 0) append(" · ${row.summary.insufficientForecastCount}份资料不足")
            })
        )
        if (row.temperatureReadings.isNotEmpty()) {
            addView(detailText("%02d:00 温度（时刻值）".format(Locale.CHINA, row.hour)))
            row.temperatureReadings.forEach { addView(detailText("${it.sourceName}：${HourlyEvidenceText.temperature(it.celsius)}")) }
        }
        if (row.forecasts.isEmpty()) addView(detailText("这个小时暂时查不到有效预报"))
        else row.forecasts.forEach { forecast ->
            val probability = forecast.probabilityPercent?.let { "$it%" } ?: "未给概率"
            val amount = forecast.amountMm?.let { " · 降水量${formatAmount(it)}毫米" } ?: " · 未给降水量"
            val condition = forecast.weatherDescription?.let { " · 天气：$it" }.orEmpty()
            addView(detailText("${forecast.displayName}：$probability$amount$condition"))
        }
        missingHourRecordNames(snapshot, row.hour).takeIf { it.isNotEmpty() }?.let { names ->
            addView(detailText("本日期其他时段有数据、本小时没有记录：${names.joinToString("、")}"))
        }
    }

    private fun missingHourRecordNames(snapshot: TomorrowRainSnapshot, hour: Int): List<String> {
        val assessments = snapshot.summary.hourlyForecastAssessments
        val recordsThisHour = assessments.filter { localHour(it.validFromEpochMs, snapshot.timeZoneId) == hour }
            .map { it.forecastKey }
            .toSet()
        return assessments
            .groupBy { it.forecastKey }
            .filterKeys { it !in recordsThisHour }
            .values
            .mapNotNull { it.firstOrNull()?.displayName }
            .distinct()
    }

    private fun localHour(epochMs: Long, timeZoneId: String): Int =
        java.util.Calendar.getInstance(TimeZone.getTimeZone(timeZoneId), Locale.CHINA)
            .apply { timeInMillis = epochMs }
            .get(java.util.Calendar.HOUR_OF_DAY)

    private fun detailText(value: String) = TextView(this).apply {
        text = value
        textSize = 15f
        setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        setPadding(0, dp(4), 0, 0)
    }

    private fun scrollToInitialHour(target: View) {
        val targetLocation = IntArray(2)
        val scrollLocation = IntArray(2)
        target.getLocationOnScreen(targetLocation)
        binding.hourlyScroll.getLocationOnScreen(scrollLocation)
        binding.hourlyScroll.smoothScrollTo(0, binding.hourlyScroll.scrollY + targetLocation[1] - scrollLocation[1])
    }

    private fun formatDate(value: String): String = runCatching {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(value)!!
        SimpleDateFormat("M月d日", Locale.CHINA).format(date)
    }.getOrDefault(value)

    private fun formatReadTime(epochMs: Long, timeZoneId: String): String =
        SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).apply { timeZone = TimeZone.getTimeZone(timeZoneId) }.format(Date(epochMs))

    private fun formatAmount(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val EXTRA_INITIAL_HOUR = "extra_initial_hour"
        private const val EXTRA_LOCATION_ID = "location_id"
        private const val EXTRA_TARGET_DATE = "target_date"
        private const val OLD_FORECAST_AFTER_MS = 6L * 60L * 60L * 1000L

        fun start(context: Context, snapshot: TomorrowRainSnapshot, initialHour: Int? = null) {
            val selectedSlot = initialHour?.let { hour ->
                TomorrowRainPresentation.slotDefinitions.indexOfFirst { hour in it.startHour until it.endHour }
                    .coerceAtLeast(0)
            } ?: TomorrowRainEvidenceSession.selectedSlotIndex
            TomorrowRainEvidenceSession.open(snapshot, selectedSlot)
            context.startActivity(Intent(context, TomorrowRainHourlyActivity::class.java).apply {
                putExtra(EXTRA_LOCATION_ID, snapshot.targetLocation.canonicalLocationId)
                putExtra(EXTRA_TARGET_DATE, snapshot.targetDateLocal)
                initialHour?.let { putExtra(EXTRA_INITIAL_HOUR, it) }
            })
        }
    }
}
