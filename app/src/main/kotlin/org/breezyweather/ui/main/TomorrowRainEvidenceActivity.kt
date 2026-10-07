/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.ui.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.breezyweather.R
import org.breezyweather.common.activities.BreezyActivity
import org.breezyweather.common.extensions.getThemeColor
import org.breezyweather.databinding.ActivityTomorrowRainEvidenceBinding
import org.breezyweather.domain.multisource.hourly.presentation.HourlyEvidenceText
import org.breezyweather.domain.multisource.hourly.presentation.SourceFetchState
import org.breezyweather.domain.multisource.hourly.presentation.SourceFetchStatus
import org.breezyweather.domain.multisource.hourly.presentation.SourceSlotMetrics
import org.breezyweather.domain.multisource.hourly.presentation.RainTimeSlot
import org.breezyweather.domain.multisource.hourly.presentation.ForecastOutcomeCategory
import org.breezyweather.domain.multisource.hourly.presentation.TomorrowRainSnapshot
import org.breezyweather.domain.multisource.hourly.presentation.TomorrowRainPresentation
import org.breezyweather.domain.multisource.hourly.model.ForecastTimeWindow
import org.breezyweather.domain.multisource.model.PrecipitationPhase
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Full-page source comparison for the selected three-hour presentation slot.
 * It intentionally consumes the hand-off snapshot instead of fetching again,
 * so the values on this page remain identical to the home fact block.
 */
class TomorrowRainEvidenceActivity : BreezyActivity() {

    private lateinit var binding: ActivityTomorrowRainEvidenceBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTomorrowRainEvidenceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = "各份预报对比"
        binding.toolbar.setNavigationOnClickListener { finish() }

        val snapshot = TomorrowRainEvidenceSession.resolve(
            context = this,
            expectedLocationId = intent.getStringExtra(EXTRA_LOCATION_ID),
            expectedTargetDate = intent.getStringExtra(EXTRA_TARGET_DATE),
        )
        if (snapshot == null || snapshot.slots.isEmpty()) {
            Toast.makeText(this, "预报记录已失效，请返回首页重试获取预报。", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        render(snapshot, intent.getIntExtra(EXTRA_SELECTED_SLOT, TomorrowRainEvidenceSession.selectedSlotIndex))
    }

    private fun render(snapshot: TomorrowRainSnapshot, requestedIndex: Int) {
        val index = requestedIndex.coerceIn(snapshot.slots.indices)
        val slot = snapshot.slots[index]
        binding.evidenceLocationText.text = snapshot.targetLocation.displayName
        binding.evidenceDateText.text = snapshot.targetDateLocal
        binding.evidenceWindowText.text = "${slot.definition.timeRange} · ${slot.definition.displayName}"
        val scopeText = when {
            isOldForecast(snapshot) -> "旧预报 · 当前时段各来源原值"
            snapshot.recoveredFromStorage -> "已恢复预报 · 当前时段各来源原值"
            else -> "当前时段各来源原值"
        }
        binding.evidenceScopeText.showForecastSourceAttribution(snapshot, scopeText)
        binding.sourceCompareContainer.removeAllViews()

        slot.sourceMetrics.forEach { metrics ->
            addDividerIfNeeded()
            binding.sourceCompareContainer.addView(createSourceRow(metrics))
        }

        val shownProviderIds = slot.sourceMetrics
            .map { it.sourceId.substringBefore(":") }
            .toSet()
        snapshot.sourceStatuses
            .filter { it.state != SourceFetchState.SUCCESS && !slot.sourceMetrics.any { metrics -> HourlyEvidenceText.matches(it, metrics) } }
            .forEach { status ->
                addDividerIfNeeded()
                binding.sourceCompareContainer.addView(createStatusRow(status))
            }

        if (binding.sourceCompareContainer.childCount == 0) {
            binding.sourceCompareContainer.addView(TextView(this).apply {
                text = "当前时段暂无可用来源数据"
                textSize = 16f
                setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                setPadding(0, dp(18), 0, dp(18))
            })
        }

        binding.evidenceSummaryText.text = buildForecastSummary(snapshot, slot)
    }

    private fun buildForecastSummary(
        snapshot: TomorrowRainSnapshot,
        slot: RainTimeSlot,
    ): String {
        val summary = TomorrowRainPresentation.summarizeSlot(slot)
        val lines = mutableListOf<String>()
        val countText = if (summary.totalForecastCount == 0) {
            "本时段没有可纳入统计的预报。"
        } else {
            "共${summary.totalForecastCount}份预报：${summary.independentModelCount}个独立模型，${summary.compositeForecastCount}份综合来源；" +
                "${summary.rainForecastCount}份报雨，${summary.noObviousPrecipitationForecastCount}份未报明显降水，${summary.insufficientForecastCount}份资料不足。"
        }
        lines += countText
        lines += summary.countedForecasts.map { counted ->
            val result = counted.result
            val times = when (counted.category) {
                ForecastOutcomeCategory.REPORTS_RAIN -> "报雨 ${formatWindows(result.rainWindows, snapshot)}"
                ForecastOutcomeCategory.NO_OBVIOUS_PRECIPITATION ->
                    "未报明显降水 ${formatWindows(result.noObviousPrecipitationWindows, snapshot)}"
                ForecastOutcomeCategory.INSUFFICIENT_DATA -> when {
                    result.precipitationWindows.isNotEmpty() ->
                        "有降水信号但类型待确认 ${formatWindows(result.precipitationWindows, snapshot)}"
                    result.insufficientWindows.isNotEmpty() ->
                        "资料不足 ${formatWindows(result.insufficientWindows, snapshot)}"
                    else -> "资料不足（${result.observedHourCount}/${result.expectedHourCount} 小时有数据）"
                }
            }
            val amount = if (result.amountComplete && result.totalAmountMm != null) {
                "${result.expectedHourCount}小时累计 ${"%.1f".format(Locale.US, result.totalAmountMm)} mm"
            } else {
                "雨量不完整（${result.amountAvailableHourCount}/${result.expectedHourCount} 小时有值）"
            }
            val missing = if (counted.category == ForecastOutcomeCategory.REPORTS_RAIN &&
                (result.insufficientWindows.isNotEmpty() || result.observedHourCount < result.expectedHourCount)
            ) {
                val unknownWindows = result.insufficientWindows.takeIf { it.isNotEmpty() }
                    ?.let { "，未能判断${formatWindows(it, snapshot)}" }
                    .orEmpty()
                "；时段资料不全（${result.observedHourCount}/${result.expectedHourCount}小时有数据$unknownWindows，仍只计入报雨）"
            } else ""
            "${result.displayName}：$times；$amount$missing"
        }
        if (summary.omittedDuplicateForecasts.isNotEmpty()) {
            lines += "未计入数量（同一已知物理模式的重复预报）："
            lines += summary.omittedDuplicateForecasts.map { result ->
                val resolved = result.hourlyAssessments
                    .mapNotNull { it.evidence.resolvedPhysicalModel }
                    .distinct()
                    .singleOrNull()
                    ?.displayName
                    ?.substringBefore(" (")
                    ?: "已计入的物理模式"
                "${result.displayName}：与${resolved}重复。"
            }
        }
        if (summary.omittedOverlappingBestMatchForecasts.isNotEmpty()) {
            lines += "未计入数量（Best Match组合预报与已列物理模式时段重叠）："
            lines += summary.omittedOverlappingBestMatchForecasts.map { result ->
                val intervals = result.hourlyAssessments.map { it.validFromEpochMs to it.validToEpochMs }.toSet()
                val physicalNames = slot.forecastResults
                    .filter { it.isIndependentPhysicalModel && !it.isDuplicatePhysicalModelEvidence }
                    .filter { physical -> physical.hourlyAssessments.any {
                        (it.validFromEpochMs to it.validToEpochMs) in intervals
                    } }
                    .map { it.displayName }
                    .distinct()
                "${result.displayName}：与${physicalNames.ifEmpty { listOf("独立物理模式") }.joinToString("、")}时段重叠，未另加模式票。"
            }
        }
        val shownProviderIds = slot.sourceMetrics.map { it.sourceId.substringBefore(":") }.toSet()
        val notCountedStatuses = snapshot.sourceStatuses.filter {
            it.state != SourceFetchState.SUCCESS && !slot.sourceMetrics.any { metrics -> HourlyEvidenceText.matches(it, metrics) }
        }
        if (notCountedStatuses.isNotEmpty()) {
            lines += "未取得预报、不计入份数：" + notCountedStatuses.joinToString("、") {
                "${it.displayName}（${notCountedStatusText(it)}）"
            }
        }
        val dryCaveat = if (slot.reliableNoObviousPrecipitationWindows.isNotEmpty()) {
            "共同未报明显降水：${formatWindows(slot.reliableNoObviousPrecipitationWindows, snapshot)}；这不保证无雨。"
        } else if (summary.noObviousPrecipitationForecastCount > 0) {
            buildString {
                append("完整小时未报明显降水不保证无雨。")
                if (snapshot.reliableNoObviousPrecipitationWindows.isEmpty()) {
                    append("\n独立物理模式不足两份，或完整覆盖不足，不能据此判断可靠的共同无雨时段。")
                }
            }
        } else if (summary.insufficientForecastCount > 0) {
            "资料不足不计作未报明显降水。"
        } else {
            ""
        }
        val body = if (lines.isEmpty()) "本时段暂无可用来源或模式数据。" else lines.joinToString("\n")
        val currentHourCaveat = if (snapshot.isToday) {
            val now = snapshot.analysisStartEpochMs ?: System.currentTimeMillis()
            val calendar = Calendar.getInstance(TimeZone.getTimeZone(snapshot.timeZoneId), Locale.CHINA).apply {
                timeInMillis = now
            }
            if (calendar.get(Calendar.MINUTE) > 0 &&
                calendar.get(Calendar.HOUR_OF_DAY) == slot.definition.startHour
            ) "当前小时按整点预报展示，包含已过部分；剩余雨量无法单独拆分。" else ""
        } else {
            ""
        }
        return buildString {
            if (currentHourCaveat.isNotBlank()) append(currentHourCaveat).append("\n")
            append("统计对象为本时段去重后的来源／物理模式预报。\n")
            append(body)
            if (dryCaveat.isNotBlank()) append("\n").append(dryCaveat)
            append("\n数量不是概率；各来源概率原值保留在上方，不计算平均概率。")
        }
    }

    private fun formatWindows(windows: List<ForecastTimeWindow>, snapshot: TomorrowRainSnapshot): String =
        windows.joinToString("、") { window ->
            "${formatTime(window.startEpochMs, snapshot.timeZoneId)}–${formatEndTime(window.endEpochMs, snapshot)}"
        }

    private fun formatEndTime(epochMs: Long, snapshot: TomorrowRainSnapshot): String {
        val time = formatTime(epochMs, snapshot.timeZoneId)
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone(snapshot.timeZoneId)
        }.format(Date(epochMs))
        return if (date != snapshot.targetDateLocal && time == "00:00") "24:00" else time
    }

    private fun addDividerIfNeeded() {
        if (binding.sourceCompareContainer.childCount == 0) return
        binding.sourceCompareContainer.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            )
            setBackgroundColor(getThemeColor(com.google.android.material.R.attr.colorOutline))
        })
    }

    private fun createSourceRow(metrics: SourceSlotMetrics): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }

        container.addView(TextView(this).apply {
            text = metrics.displayName
            textSize = 18f
            setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            contentDescription = "来源：${metrics.displayName}"
        })

        if (metrics.sourceId == "openmeteo:cma_grapes") container.addView(TextView(this).apply {
            text = "${metrics.spatialResolutionDetail}\n${metrics.temporalResolutionDetail}\n来源：CMA / Open-Meteo（CC BY 4.0）"
            textSize = 15f
            setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        val metricRow = LinearLayout(this).apply {
            orientation = if (isNarrowOrLargeText()) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val probability = metrics.maxProbabilityPercent?.let { "$it%" } ?: "未提供"
        val amount = when {
            !metrics.precipitationPeriodKnown -> "累计时段未核"
            metrics.totalAmountMm != null -> RainAmountText.describeForPhase(metrics.totalAmountMm, metrics.amountPhase)
            metrics.hasEvidence -> "降水量暂时不完整"
            else -> "未提供"
        }
        metricRow.addView(createMetricColumn("最高小时降水概率", probability, addStartMargin = false))
        metricRow.addView(createMetricColumn("时段累计降水量", amount, addStartMargin = true))
        container.addView(metricRow)
        container.addView(TextView(this).apply {
            text = "逐小时温度（时刻值）\n" + if (metrics.temperatureReadings.isEmpty()) "未提供" else
                metrics.temperatureReadings.joinToString("\n") { reading ->
                    "${HourlyEvidenceText.time(reading.atEpochMs, "Asia/Shanghai")}  ${HourlyEvidenceText.temperature(reading.celsius)}"
                }
            textSize = 17f
            setPadding(0, dp(8), 0, dp(4))
            setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurface))
        })

        metrics.maxProbabilityHour?.let { hour ->
            container.addView(TextView(this).apply {
                text = "最高值对应 %02d:00".format(Locale.US, hour)
                textSize = 14f
                setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(6), 0, 0)
            })
        }

        val metadata = TextView(this).apply {
            text = buildMetadata(metrics)
            textSize = 14f
            setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            visibility = View.GONE
            setPadding(0, dp(7), 0, 0)
        }
        container.addView(TextView(this).apply {
            text = "来源与更新时间 〉"
            textSize = 14f
            setTextColor(getThemeColor(androidx.appcompat.R.attr.colorPrimary))
            isClickable = true
            isFocusable = true
            minHeight = dp(44)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                metadata.visibility = if (metadata.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                text = if (metadata.visibility == View.VISIBLE) "来源与更新时间 〈" else "来源与更新时间 〉"
            }
        })
        container.addView(metadata)
        return container
    }

    private fun createMetricColumn(title: String, value: String, addStartMargin: Boolean): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(2))
        }
        column.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        column.addView(TextView(this).apply {
            text = value
            textSize = 20f
            setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(2), 0, 0)
        })
        val params = LinearLayout.LayoutParams(
            if (isNarrowOrLargeText()) ViewGroup.LayoutParams.MATCH_PARENT else 0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            if (isNarrowOrLargeText()) 0f else 1f
        )
        if (addStartMargin && !isNarrowOrLargeText()) params.marginStart = dp(14)
        column.layoutParams = params
        return column
    }

    private fun createStatusRow(status: SourceFetchStatus): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(16))
        }
        container.addView(TextView(this).apply {
            text = status.displayName
            textSize = 18f
            setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        container.addView(TextView(this).apply {
            text = sourceStatusText(status)
            textSize = 16f
            setTextColor(if (status.state == SourceFetchState.FAILED) {
                getColor(R.color.md_theme_error)
            } else {
                getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
            })
            setPadding(0, dp(7), 0, 0)
        })
        return container
    }

    private fun buildMetadata(metrics: SourceSlotMetrics): String {
        val probabilityScope = buildString {
            if (metrics.maxProbabilityPercent == null) {
                append("概率：未提供")
            } else {
                append("概率展示：该来源时段内最高小时值")
                metrics.maxProbabilityHour?.let { append("（最高值对应 %02d:00）".format(Locale.US, it)) }
            }
            if (metrics.probabilityEventDefinitions.isNotEmpty()) {
                val definitions = metrics.probabilityEventDefinitions.map {
                    if (it == "PROVIDER_DEFINED_OR_UNKNOWN") "上游未说明概率口径" else it
                }.distinct()
                append("\n概率口径：").append(definitions.joinToString("；"))
            }
        }
        val amountScope = when {
            metrics.totalAmountMm != null -> "降水量口径：${metrics.definitionLabel()}"
            metrics.hasEvidence -> "降水量：数据不完整（${metrics.availableAmountHourCount}/${metrics.expectedHourCount} 小时有值，重复小时 ${metrics.duplicateHourCount}）"
            else -> "降水量：未提供"
        }
        return listOf(
            "技术名称：${metrics.modelName}",
            "数据提供方：${metrics.providerName}",
            metrics.provenanceDetail,
            "来源：${sanitizeSource(metrics.rawSource)}",
            HourlyEvidenceText.metadata(metrics, "Asia/Shanghai"),
            probabilityScope,
            amountScope
        ).joinToString("\n")
    }

    private fun SourceSlotMetrics.definitionLabel(): String = "当前时段小时降水量合计（相态 ${amountPhase.displayName()}）"

    private fun PrecipitationPhase.displayName(): String = when (this) {
        PrecipitationPhase.RAIN -> "雨"
        PrecipitationPhase.SNOW -> "雪"
        PrecipitationPhase.MIXED -> "雨雪混合"
        PrecipitationPhase.OTHER -> "其他"
        PrecipitationPhase.UNKNOWN -> "待确认"
    }

    private fun sourceStatusText(status: SourceFetchStatus): String {
        return when (status.state) {
            SourceFetchState.SUCCESS -> "已取得 ${status.evidenceCount} 个小时数据"
            SourceFetchState.NO_DATA -> "${status.displayName}本期暂无目标时段数据，其他来源可用。"
            SourceFetchState.MISSING_CONFIGURATION -> "${status.displayName}未配置，其他来源可用。"
            SourceFetchState.FAILED -> "${status.displayName}请求失败；其他来源仍可查看。"
            SourceFetchState.NOT_RUN -> if (status.sourceId == "china") {
                "小米天气缓存本次暂无果园数据，其他来源可用。"
            } else {
                "${status.displayName}本次未运行，其他来源可用。"
            }
        }
    }

    private fun notCountedStatusText(status: SourceFetchStatus): String = when (status.state) {
        SourceFetchState.SUCCESS -> "已取得预报数据"
        SourceFetchState.NO_DATA -> "无本时段数据"
        SourceFetchState.MISSING_CONFIGURATION -> "未配置"
        SourceFetchState.FAILED -> "请求失败"
        SourceFetchState.NOT_RUN -> "本次未运行"
    }

    private fun sanitizeSource(rawSource: String): String {
        return rawSource.substringBefore("?").ifBlank { "来源信息未提供" }
    }

    private fun formatMillimeters(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun formatTime(epochMs: Long, timeZoneId: String): String {
        if (epochMs <= 0L) return "未知"
        return SimpleDateFormat("HH:mm", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone(timeZoneId)
        }.format(Date(epochMs))
    }

    private fun isNarrowOrLargeText(): Boolean {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return widthDp < 350f || resources.configuration.fontScale >= 1.15f
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun isOldForecast(snapshot: TomorrowRainSnapshot): Boolean =
        System.currentTimeMillis() - snapshot.fetchedAtEpochMs > OLD_FORECAST_AFTER_MS

    companion object {
        private const val EXTRA_LOCATION_ID = "location_id"
        private const val EXTRA_TARGET_DATE = "target_date"
        private const val EXTRA_SELECTED_SLOT = "selected_slot"
        private const val OLD_FORECAST_AFTER_MS = 6L * 60L * 60L * 1000L

        fun start(context: Context, snapshot: TomorrowRainSnapshot, selectedSlotIndex: Int) {
            context.startActivity(
                Intent(context, TomorrowRainEvidenceActivity::class.java)
                    .putExtra(EXTRA_LOCATION_ID, snapshot.targetLocation.canonicalLocationId)
                    .putExtra(EXTRA_TARGET_DATE, snapshot.targetDateLocal)
                    .putExtra(EXTRA_SELECTED_SLOT, selectedSlotIndex)
            )
        }
    }
}
