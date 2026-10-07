package org.breezyweather.domain.multisource.hourly.presentation

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object HourlyEvidenceText {
    fun time(epochMs: Long?, zone: String): String = if (epochMs == null || epochMs <= 0) "未提供" else
        SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).apply { timeZone = TimeZone.getTimeZone(zone) }.format(Date(epochMs))
    fun temperature(value: Double?): String = value?.takeIf { it.isFinite() && it in -100.0..70.0 }
        ?.let { String.format(Locale.CHINA, "%.1f℃", it) } ?: "未提供"
    fun matches(status: SourceFetchStatus, metrics: SourceSlotMetrics): Boolean = when (status.sourceId) {
        "openmeteo:cma_grapes" -> metrics.sourceId == status.sourceId
        "openmeteo" -> metrics.sourceId.startsWith("openmeteo:") && metrics.sourceId != "openmeteo:cma_grapes"
        else -> metrics.sourceId.substringBefore(":") == status.sourceId
    }
    fun metadata(metrics: SourceSlotMetrics, zone: String): String = listOf(
        "发布时间：${time(metrics.latestIssuedAtEpochMs, zone)}",
        "获取时间：${time(metrics.latestFetchedAtEpochMs, zone)}",
        metrics.sourceUpdatedAtEpochMs?.let { "缓存上游更新时间：${time(it, zone)}" },
        metrics.spatialResolutionDetail,
        if (metrics.gridLatitude != null && metrics.gridLongitude != null)
            "返回网格中心：${metrics.gridLatitude}, ${metrics.gridLongitude}" else null,
        metrics.temporalResolutionDetail,
        if (metrics.precipitationPeriodKnown) "降水量：时段累计；温度：标注时刻值" else "降水累计边界未核，不纳入时段合计",
    ).filterNotNull().joinToString("\n")
}
