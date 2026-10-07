/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource.hourly.presentation

import org.breezyweather.domain.multisource.engine.WeatherEvidenceEngine
import org.breezyweather.domain.multisource.hourly.engine.TomorrowHourlyRainEngine
import org.breezyweather.domain.multisource.hourly.model.ForecastTimeWindow
import org.breezyweather.domain.multisource.hourly.model.HourlyForecastAssessment
import org.breezyweather.domain.multisource.hourly.model.HourlyForecastResult
import org.breezyweather.domain.multisource.hourly.model.ModelPeriodForecastResult
import org.breezyweather.domain.multisource.hourly.model.TomorrowHourlyRainSummary
import org.breezyweather.domain.multisource.location.TargetLocation
import org.breezyweather.domain.multisource.model.EvidenceAvailability
import org.breezyweather.domain.multisource.model.ForecastEvidence
import org.breezyweather.domain.multisource.model.UnderlyingModel
import org.breezyweather.domain.multisource.model.PrecipitationPhase
import org.breezyweather.domain.multisource.model.WeatherProvider
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A source fetch status is deliberately separate from field availability.
 * A successful source can still omit probability or precipitation amount.
 */
enum class SourceFetchState {
    SUCCESS,
    NO_DATA,
    MISSING_CONFIGURATION,
    FAILED,
    NOT_RUN
}

data class SourceFetchStatus(
    val sourceId: String,
    val displayName: String,
    val state: SourceFetchState,
    val evidenceCount: Int = 0
)

/**
 * The six UI groups are only presentation windows. They do not replace the
 * four elder periods used by TomorrowHourlyRainEngine.
 */
data class RainTimeSlotDefinition(
    val id: String,
    val startHour: Int,
    val endHour: Int,
    val displayName: String,
) {
    val timeRange: String
        get() = "%02d:00–%02d:00".format(Locale.US, startHour, endHour)

    val expectedHourCount: Int
        get() = endHour - startHour
}

data class SourceSlotMetrics(
    val sourceId: String,
    val displayName: String,
    val providerName: String,
    val modelName: String,
    val provenanceDetail: String,
    val rawSource: String,
    val evidenceCount: Int,
    val expectedHourCount: Int,
    val availableAmountHourCount: Int,
    val maxProbabilityPercent: Int?,
    val maxProbabilityHour: Int?,
    val totalAmountMm: Double?,
    val amountComplete: Boolean,
    val latestIssuedAtEpochMs: Long,
    val conditionKnownHourCount: Int = 0,
    val duplicateHourCount: Int = 0,
    val amountPhase: PrecipitationPhase = PrecipitationPhase.UNKNOWN,
    val probabilityEventDefinitions: List<String> = emptyList(),
    val latestFetchedAtEpochMs: Long? = null,
    val sourceUpdatedAtEpochMs: Long? = null,
    val spatialResolutionDetail: String = "范围未提供",
    val temporalResolutionDetail: String = "时间分辨率未提供",
    val gridLatitude: Double? = null,
    val gridLongitude: Double? = null,
    val precipitationPeriodKnown: Boolean = true,
    val temperatureReadings: List<HourlyTemperatureReading> = emptyList(),
) {
    val hasEvidence: Boolean
        get() = evidenceCount > 0

    val hasUsableMetric: Boolean
        get() = hasEvidence && precipitationPeriodKnown
}

data class RainTimeSlot(
    val definition: RainTimeSlotDefinition,
    val sourceMetrics: List<SourceSlotMetrics>,
    val forecastResults: List<ModelPeriodForecastResult> = emptyList(),
    val reliableNoObviousPrecipitationWindows: List<ForecastTimeWindow> = emptyList(),
)

enum class RainSlotDisplayState {
    ALL_REPORT_RAIN,
    MIXED_RAIN_AND_NO_OBVIOUS,
    RAIN_REPORTED,
    NO_OBVIOUS_PRECIPITATION,
    PRECIPITATION_SIGNAL,
    INSUFFICIENT_DATA,
}

enum class ForecastOutcomeCategory {
    REPORTS_RAIN,
    NO_OBVIOUS_PRECIPITATION,
    INSUFFICIENT_DATA,
}

data class CountedForecastResult(
    val result: ModelPeriodForecastResult,
    val category: ForecastOutcomeCategory,
)

data class RainWindowPatternCount(
    val windows: List<ForecastTimeWindow>,
    val forecastCount: Int,
    val incompleteCoverageForecastCount: Int = 0,
)

data class ProbabilityReading(
    val forecastKey: String,
    val displayName: String,
    val hour: Int,
    val percentage: Int,
    val comparisonKey: String,
    val eventScopeLabel: String?,
    val sourceSpecificScope: Boolean,
)

data class ProbabilityDisplayGroup(
    val eventScopeLabel: String?,
    val sourceSpecificScope: Boolean,
    val readings: List<ProbabilityReading>,
) {
    val minPercent: Int
        get() = readings.minOf { it.percentage }

    val maxPercent: Int
        get() = readings.maxOf { it.percentage }
}

data class HourlyProbabilityDisplay(
    val hour: Int,
    val groups: List<ProbabilityDisplayGroup>,
    val forecastsWithoutProbabilityCount: Int,
)

data class SlotProbabilityDisplay(
    val hourly: List<HourlyProbabilityDisplay>,
    /** Each value is one forecast's highest available hourly value in the slot. */
    val maximumByForecastGroups: List<ProbabilityDisplayGroup>,
    val incompleteForecastCount: Int,
    val forecastCount: Int,
)

data class HourlyForecastDisplayValue(
    val forecastKey: String,
    val displayName: String,
    val probabilityPercent: Int?,
    val weatherDescription: String?,
    val amountMm: Double?,
)

data class HourlyTemperatureReading(val sourceName: String, val atEpochMs: Long, val celsius: Double?)

data class HourlyForecastDisplayRow(
    val hour: Int,
    val forecasts: List<HourlyForecastDisplayValue>,
    val summary: RainSlotDisplaySummary,
    val temperatureReadings: List<HourlyTemperatureReading> = emptyList(),
) {
    /** Probability-only consumers can use this view; full comparisons retain [forecasts]. */
    val forecastsWithProbability: List<HourlyForecastDisplayValue>
        get() = forecasts.filter { it.probabilityPercent != null }
}

data class RainSlotDisplaySummary(
    val state: RainSlotDisplayState,
    val hasRain: Boolean,
    val hasCompleteNoRainForecast: Boolean,
    val hasIncompleteForecasts: Boolean,
    val hasPrecipitationSignal: Boolean,
    val rainWindowPatterns: List<List<ForecastTimeWindow>>,
    val minCompleteAmountMm: Double?,
    val maxCompleteAmountMm: Double?,
    val hasIncompleteAmounts: Boolean,
    val countedForecasts: List<CountedForecastResult>,
    val omittedDuplicateForecasts: List<ModelPeriodForecastResult>,
    val omittedOverlappingBestMatchForecasts: List<ModelPeriodForecastResult>,
    val rainWindowPatternCounts: List<RainWindowPatternCount>,
    val partialRainForecastCount: Int,
    val incompleteAmountForecastCount: Int,
) {
    val totalForecastCount: Int
        get() = countedForecasts.size

    val independentModelCount: Int
        get() = countedForecasts.count { it.result.isIndependentPhysicalModel }

    val compositeForecastCount: Int
        get() = countedForecasts.count { !it.result.isIndependentPhysicalModel }

    val rainForecastCount: Int
        get() = countedForecasts.count { it.category == ForecastOutcomeCategory.REPORTS_RAIN }

    val noObviousPrecipitationForecastCount: Int
        get() = countedForecasts.count { it.category == ForecastOutcomeCategory.NO_OBVIOUS_PRECIPITATION }

    val insufficientForecastCount: Int
        get() = countedForecasts.count { it.category == ForecastOutcomeCategory.INSUFFICIENT_DATA }
}

data class TomorrowRainSnapshot(
    val targetLocation: TargetLocation,
    val targetDateLocal: String,
    val timeZoneId: String,
    val evidences: List<ForecastEvidence>,
    val sourceStatuses: List<SourceFetchStatus>,
    val slots: List<RainTimeSlot>,
    val primarySourceId: String?,
    val primarySourceDisplayName: String?,
    val headline: String,
    val summary: TomorrowHourlyRainSummary,
    val fetchedAtEpochMs: Long,
    val temperatureEvidences: List<ForecastEvidence> = emptyList(),
    val recoveredFromStorage: Boolean = false,
    val isToday: Boolean = false,
    val analysisStartEpochMs: Long? = null,
    val allDayForecastResults: List<ModelPeriodForecastResult> = emptyList(),
    val reliableNoObviousPrecipitationWindows: List<ForecastTimeWindow> = emptyList(),
)

object TomorrowRainPresentation {

    val slotDefinitions = listOf(
        RainTimeSlotDefinition("early_night", 0, 3, "凌晨"),
        RainTimeSlotDefinition("late_night", 3, 6, "凌晨"),
        RainTimeSlotDefinition("early_morning", 6, 9, "早上"),
        RainTimeSlotDefinition("morning", 9, 12, "上午"),
        RainTimeSlotDefinition("noon", 12, 15, "中午"),
        RainTimeSlotDefinition("afternoon", 15, 18, "下午"),
        RainTimeSlotDefinition("evening", 18, 21, "傍晚"),
        RainTimeSlotDefinition("late_evening", 21, 24, "夜间")
    )

    /** Counts each non-duplicate forecast once, prioritizing a rain signal over missing fields. */
    fun summarizeSlot(slot: RainTimeSlot): RainSlotDisplaySummary = summarizeForecastResults(slot.forecastResults)

    fun summarizeForecastResults(forecastResults: List<ModelPeriodForecastResult>): RainSlotDisplaySummary {
        fun hasCompleteHourlyOutcome(result: ModelPeriodForecastResult): Boolean =
            result.expectedHourCount > 0 &&
                result.observedHourCount == result.expectedHourCount &&
                result.insufficientWindows.isEmpty() &&
                result.hourlyAssessments.size == result.expectedHourCount &&
                result.hourlyAssessments.none { it.result == HourlyForecastResult.INSUFFICIENT_DATA }

        val omittedDuplicates = forecastResults.filter { it.isDuplicatePhysicalModelEvidence }
        val independentPhysicalIntervals = forecastResults
            .filter { it.isIndependentPhysicalModel && !it.isDuplicatePhysicalModelEvidence }
            .flatMap { result -> result.hourlyAssessments.map { it.validFromEpochMs to it.validToEpochMs } }
            .toSet()
        val omittedOverlappingBestMatches = forecastResults.filter { result ->
            result.sourceIdentityId == "${WeatherProvider.OPEN_METEO.id}:${UnderlyingModel.OPEN_METEO_BEST_MATCH.id}" &&
                !result.isIndependentPhysicalModel &&
                !result.isDuplicatePhysicalModelEvidence &&
                result.hourlyAssessments.any { (it.validFromEpochMs to it.validToEpochMs) in independentPhysicalIntervals }
        }
        val omittedBestMatchKeys = omittedOverlappingBestMatches.map { it.forecastKey }.toSet()
        val counted = forecastResults
            .filterNot { it.isDuplicatePhysicalModelEvidence || it.forecastKey in omittedBestMatchKeys }
            .map { result ->
                val category = when {
                    result.rainWindows.isNotEmpty() -> ForecastOutcomeCategory.REPORTS_RAIN
                    hasCompleteHourlyOutcome(result) && result.hourlyAssessments.all {
                        it.result == HourlyForecastResult.NO_OBVIOUS_PRECIPITATION
                    } -> ForecastOutcomeCategory.NO_OBVIOUS_PRECIPITATION
                    else -> ForecastOutcomeCategory.INSUFFICIENT_DATA
                }
                CountedForecastResult(result, category)
            }
        val results = counted.map { it.result }

        val hasRain = results.any { it.rainWindows.isNotEmpty() }
        val hasCompleteNoRainForecast = counted.any {
            it.category == ForecastOutcomeCategory.NO_OBVIOUS_PRECIPITATION
        }
        val hasPrecipitationSignal = results.any { it.precipitationWindows.isNotEmpty() }
        val hasIncompleteForecasts = results.isEmpty() || results.any { !hasCompleteHourlyOutcome(it) }
        val allReportRain = results.isNotEmpty() && results.all { result ->
            hasCompleteHourlyOutcome(result) && result.rainWindows.isNotEmpty()
        }
        val completeAmounts = results.mapNotNull { result ->
            result.totalAmountMm.takeIf { result.amountComplete }
        }
        val rainPatternCounts = counted
            .filter { it.category == ForecastOutcomeCategory.REPORTS_RAIN }
            .groupBy { forecast ->
                forecast.result.rainWindows.map { it.startEpochMs to it.endEpochMs }
            }
            .map { (_, forecasts) ->
                RainWindowPatternCount(
                    windows = forecasts.first().result.rainWindows,
                    forecastCount = forecasts.size,
                    incompleteCoverageForecastCount = forecasts.count { !hasCompleteHourlyOutcome(it.result) },
                )
            }
            .sortedWith(
                compareBy<RainWindowPatternCount> { it.windows.firstOrNull()?.startEpochMs ?: Long.MAX_VALUE }
                    .thenByDescending { it.windows.lastOrNull()?.endEpochMs ?: Long.MIN_VALUE }
                    .thenByDescending { it.forecastCount }
            )

        val state = when {
            hasRain && hasCompleteNoRainForecast -> RainSlotDisplayState.MIXED_RAIN_AND_NO_OBVIOUS
            allReportRain -> RainSlotDisplayState.ALL_REPORT_RAIN
            hasRain -> RainSlotDisplayState.RAIN_REPORTED
            hasPrecipitationSignal -> RainSlotDisplayState.PRECIPITATION_SIGNAL
            hasCompleteNoRainForecast -> RainSlotDisplayState.NO_OBVIOUS_PRECIPITATION
            else -> RainSlotDisplayState.INSUFFICIENT_DATA
        }
        return RainSlotDisplaySummary(
            state = state,
            hasRain = hasRain,
            hasCompleteNoRainForecast = hasCompleteNoRainForecast,
            hasIncompleteForecasts = hasIncompleteForecasts,
            hasPrecipitationSignal = hasPrecipitationSignal,
            rainWindowPatterns = rainPatternCounts.map { it.windows },
            minCompleteAmountMm = completeAmounts.minOrNull(),
            maxCompleteAmountMm = completeAmounts.maxOrNull(),
            hasIncompleteAmounts = results.any { !it.amountComplete },
            countedForecasts = counted,
            omittedDuplicateForecasts = omittedDuplicates,
            omittedOverlappingBestMatchForecasts = omittedOverlappingBestMatches,
            rainWindowPatternCounts = rainPatternCounts,
            partialRainForecastCount = counted.count {
                it.category == ForecastOutcomeCategory.REPORTS_RAIN && !hasCompleteHourlyOutcome(it.result)
            },
            incompleteAmountForecastCount = results.count { !it.amountComplete },
        )
    }

    /**
     * Presents only upstream hourly probabilities from the already deduplicated
     * forecast results. Different or unspecified event scopes stay separate.
     */
    fun probabilityDisplay(slot: RainTimeSlot, timeZoneId: String): SlotProbabilityDisplay {
        val countedForecasts = summarizeSlot(slot).countedForecasts.map { it.result }
        val expectedHours = (slot.definition.startHour until slot.definition.endHour).toList()

        fun latestAssessmentForHour(result: ModelPeriodForecastResult, hour: Int): HourlyForecastAssessment? =
            result.hourlyAssessments
                .filter { localHour(it.validFromEpochMs, timeZoneId) == hour }
                .maxByOrNull { it.evidence.issuedAtEpochMs }

        fun readingFor(
            result: ModelPeriodForecastResult,
            assessment: HourlyForecastAssessment,
            hour: Int,
        ): ProbabilityReading? {
            val percentage = assessment.evidence.precipitationProbability.percentage ?: return null
            val scope = probabilityScope(result, assessment.evidence)
            return ProbabilityReading(
                forecastKey = result.forecastKey,
                displayName = result.displayName,
                hour = hour,
                percentage = percentage,
                comparisonKey = scope.comparisonKey,
                eventScopeLabel = scope.label,
                sourceSpecificScope = scope.sourceSpecific,
            )
        }

        val hourly = expectedHours.map { hour ->
            val readings = countedForecasts.mapNotNull { result ->
                latestAssessmentForHour(result, hour)?.let { assessment ->
                    readingFor(result, assessment, hour)
                }
            }
            val forecastsWithProbability = readings.map { it.forecastKey }.toSet().size
            HourlyProbabilityDisplay(
                hour = hour,
                groups = groupProbabilityReadings(readings),
                forecastsWithoutProbabilityCount = (countedForecasts.size - forecastsWithProbability).coerceAtLeast(0),
            )
        }

        val maxima = countedForecasts.flatMap { result ->
            val readings = result.hourlyAssessments
                .filter { localHour(it.validFromEpochMs, timeZoneId) in expectedHours }
                .mapNotNull { assessment ->
                    readingFor(result, assessment, localHour(assessment.validFromEpochMs, timeZoneId))
                }
                .groupBy { it.comparisonKey }
            readings.values.mapNotNull { forecastReadings ->
                val maximum = forecastReadings.maxOfOrNull { it.percentage } ?: return@mapNotNull null
                forecastReadings.first { it.percentage == maximum }
            }
        }
        val incompleteForecastCount = countedForecasts.count { result ->
            expectedHours.any { hour ->
                latestAssessmentForHour(result, hour)
                    ?.evidence?.precipitationProbability?.percentage == null
            }
        }
        return SlotProbabilityDisplay(
            hourly = hourly,
            maximumByForecastGroups = groupProbabilityReadings(maxima),
            incompleteForecastCount = incompleteForecastCount,
            forecastCount = countedForecasts.size,
        )
    }

    /**
     * Builds a continuous clock-hour grid while retaining only independently
     * counted forecasts. Missing hours and missing fields remain explicit.
     */
    fun hourlyDisplayRows(
        snapshot: TomorrowRainSnapshot,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): List<HourlyForecastDisplayRow> {
        val isTodayNow = snapshot.targetDateLocal == todayDateLocal(nowEpochMs, snapshot.timeZoneId)
        val firstHour = if (isTodayNow) localHour(nowEpochMs, snapshot.timeZoneId) else 0
        return (firstHour until 24).map { hour ->
            val oneHourResults = buildModelPeriodResults(
                assessments = snapshot.summary.hourlyForecastAssessments.filter {
                    !isTodayNow || it.validToEpochMs > nowEpochMs
                },
                startHour = hour,
                endHour = hour + 1,
                timeZoneId = snapshot.timeZoneId,
            )
            val summary = summarizeForecastResults(oneHourResults)
            val forecasts = summary.countedForecasts
                .sortedWith(
                    compareBy<CountedForecastResult> {
                        if (it.result.sourceIdentityId.startsWith("${WeatherProvider.QWEATHER.id}:")) 0 else 1
                    }.thenBy { it.result.displayName }
                )
                .mapNotNull { counted ->
                    val assessment = counted.result.hourlyAssessments
                        .filter { localHour(it.validFromEpochMs, snapshot.timeZoneId) == hour }
                        .maxByOrNull { it.evidence.issuedAtEpochMs }
                        ?: return@mapNotNull null
                    HourlyForecastDisplayValue(
                        forecastKey = counted.result.forecastKey,
                        displayName = friendlySourceName(counted.result.forecastKey, counted.result.displayName),
                        probabilityPercent = assessment.evidence.precipitationProbability.percentage,
                        weatherDescription = assessment.evidence.weatherCondition
                            ?.takeIf { it.isKnown }
                            ?.text
                            ?.takeIf { it.isNotBlank() && it != "未知" && it != "天气现象未知" },
                        amountMm = assessment.amountMm,
                    )
                }
            val temperatures = snapshot.temperatureEvidences.filter {
                it.temperatureAtEpochMs?.let { t -> localHour(t, snapshot.timeZoneId) == hour } == true
            }.map { HourlyTemperatureReading(sourceDisplayName(it.provider, it.underlyingModel),
                it.temperatureAtEpochMs!!, it.temperatureCelsius) }
            HourlyForecastDisplayRow(hour = hour, forecasts = forecasts, summary = summary, temperatureReadings = temperatures)
        }
    }

    fun hourIntervalLabel(hour: Int): String = when (hour.coerceIn(0, 23)) {
        0 -> "凌晨0–1点"
        in 1..5 -> "凌晨${hour}–${hour + 1}点"
        in 6..8 -> "早上${hour}–${hour + 1}点"
        in 9..10 -> "上午${hour}–${hour + 1}点"
        11 -> "上午11点–中午12点"
        12 -> "中午12点–下午1点"
        in 13..18 -> "下午${hour - 12}–${hour - 11}点"
        in 19..22 -> "晚上${hour - 12}–${hour - 11}点"
        else -> "晚上11点–午夜12点"
    }

    private fun friendlySourceName(forecastKey: String, fallback: String): String = when (forecastKey) {
        "model:${UnderlyingModel.DWD_ICON.id}" -> "德国 ICON"
        "model:${UnderlyingModel.ECMWF_IFS.id}" -> "欧洲 IFS"
        "model:${UnderlyingModel.NOAA_GFS.id}" -> "美国 GFS"
        "model:${UnderlyingModel.CMA_GRAPES.id}" -> "中国气象局 GRAPES"
        "model:${UnderlyingModel.JMA_GSM.id}" -> "日本 GSM"
        "model:${UnderlyingModel.OPEN_METEO_BEST_MATCH.id}" -> "自动预报"
        else -> when {
            forecastKey.startsWith("source:${WeatherProvider.QWEATHER.id}:") -> "和风天气"
            forecastKey.startsWith("source:${WeatherProvider.BREEZY_CHINA.id}:") -> "小米天气（缓存）"
            else -> fallback
        }
    }

    private data class ProbabilityScope(
        val comparisonKey: String,
        val label: String?,
        val sourceSpecific: Boolean,
    )

    private val comparablePrecipitationProbabilityScope =
        Regex("""(\d+)\s*小时累计降水\s*(>=|>)\s*([0-9]+(?:\.[0-9]+)?)\s*mm""", RegexOption.IGNORE_CASE)

    private fun probabilityScope(
        result: ModelPeriodForecastResult,
        evidence: ForecastEvidence,
    ): ProbabilityScope {
        val definition = evidence.probabilityEventDefinition?.trim().orEmpty()
        val knownScope = comparablePrecipitationProbabilityScope.find(definition)
        if (knownScope != null) {
            val hours = knownScope.groupValues[1].toIntOrNull()
            val threshold = runCatching {
                BigDecimal(knownScope.groupValues[3]).stripTrailingZeros().toPlainString()
            }.getOrNull()
            if (hours != null && threshold != null) {
                return ProbabilityScope(
                    comparisonKey = "precipitation:$hours:${knownScope.groupValues[2]}:$threshold",
                    label = "${hours}小时累计降水${if (knownScope.groupValues[2] == ">=") "≥" else ">"}${threshold}毫米",
                    sourceSpecific = false,
                )
            }
        }
        // Unknown/provider-defined scopes are safe to show for their own model,
        // but cannot establish that two different forecasts describe one event.
        return ProbabilityScope(
            comparisonKey = "source:${result.forecastKey}:${definition.ifBlank { "unspecified" }}",
            label = null,
            sourceSpecific = true,
        )
    }

    private fun groupProbabilityReadings(readings: List<ProbabilityReading>): List<ProbabilityDisplayGroup> =
        readings.groupBy { it.comparisonKey }
            .map { (_, groupReadings) ->
                ProbabilityDisplayGroup(
                    eventScopeLabel = groupReadings.first().eventScopeLabel,
                    sourceSpecificScope = groupReadings.first().sourceSpecificScope,
                    readings = groupReadings.sortedWith(compareBy<ProbabilityReading> { it.displayName }.thenBy { it.hour }),
                )
            }
            .sortedWith(compareBy<ProbabilityDisplayGroup> { it.eventScopeLabel.orEmpty() }
                .thenBy { it.readings.firstOrNull()?.displayName.orEmpty() })

    fun buildSnapshot(
        targetLocation: TargetLocation,
        targetDateLocal: String,
        rawEvidences: List<ForecastEvidence>,
        sourceStatuses: List<SourceFetchStatus>,
        fetchedAtEpochMs: Long = System.currentTimeMillis(),
        recoveredFromStorage: Boolean = false,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TomorrowRainSnapshot {
        val isToday = targetDateLocal == todayDateLocal(nowEpochMs, targetLocation.timeZoneId)
        val analysisStartEpochMs = nowEpochMs.takeIf { isToday }
        val allLocationEvidences = WeatherEvidenceEngine.deduplicate(rawEvidences)
            .filter { it.canonicalLocationId == targetLocation.canonicalLocationId }
        val temperatureEvidences = allLocationEvidences.filter { evidence ->
            evidence.temperatureAtEpochMs?.let {
                isInTargetDate(it, targetDateLocal, targetLocation.timeZoneId) &&
                    (analysisStartEpochMs == null || it >= analysisStartEpochMs)
            } == true
        }
        val targetDateEvidences = allLocationEvidences
            .filter { it.precipitationPeriodKnown }
            .filter { isInTargetDate(it.validFromEpochMs, targetDateLocal, targetLocation.timeZoneId) }
        val expectedIndependentForecastKeys = targetDateEvidences
            .groupBy { it.validFromEpochMs to it.validToEpochMs }
            .values
            .flatMap(WeatherEvidenceEngine::resolveIndependentPhysicalEvidences)
            .mapNotNull { evidence ->
                val model = evidence.resolvedPhysicalModel ?: evidence.underlyingModel
                "model:${model.id}".takeIf { model.isPhysicalNumericalModel }
            }
            .toSet()
        val deduped = targetDateEvidences
            .filter { analysisStartEpochMs == null || it.validToEpochMs > analysisStartEpochMs }

        val summary = TomorrowHourlyRainEngine.synthesizeTomorrowSummary(
            canonicalLocationId = targetLocation.canonicalLocationId,
            targetDateLocal = targetDateLocal,
            timeZoneId = targetLocation.timeZoneId,
            rawEvidences = deduped,
            effectiveStartEpochMs = analysisStartEpochMs,
        )

        val sourceGroups = (deduped + temperatureEvidences).distinct().groupBy { it.sourceIdentity.id }
        val firstHour = analysisStartEpochMs?.let { localHour(it, targetLocation.timeZoneId) } ?: 0
        val definitions = slotDefinitions
            .filter { it.endHour > firstHour }
            .map { definition ->
                if (definition.startHour < firstHour) {
                    definition.copy(startHour = firstHour, displayName = "当前")
                } else {
                    definition
                }
            }
        val slots = definitions.map { definition ->
            val forecastResults = buildModelPeriodResults(
                assessments = summary.hourlyForecastAssessments,
                startHour = definition.startHour,
                endHour = definition.endHour,
                timeZoneId = targetLocation.timeZoneId,
            )
            RainTimeSlot(
                definition = definition,
                sourceMetrics = sourceGroups.values
                    .map { evidenceForSource ->
                        aggregateSourceForSlot(
                            definition = definition,
                            evidenceForSource = evidenceForSource,
                            timeZoneId = targetLocation.timeZoneId,
                            targetDateLocal = targetDateLocal,
                        )
                    }
                    .sortedWith(sourceComparator),
                forecastResults = forecastResults,
                reliableNoObviousPrecipitationWindows = buildReliableNoPrecipitationWindows(
                    results = forecastResults,
                    expectedModelKeys = expectedIndependentForecastKeys,
                ),
            )
        }

        val primary = choosePrimarySource(slots)
        val label = when {
            isToday -> "今天"
            targetDateLocal == tomorrowDateLocal(nowEpochMs, targetLocation.timeZoneId) -> "明天"
            else -> targetDateLocal
        }
        val allDayForecastResults = buildModelPeriodResults(
            assessments = summary.hourlyForecastAssessments,
            startHour = firstHour,
            endHour = 24,
            timeZoneId = targetLocation.timeZoneId,
        )
        val headline = buildHeadline(
            dayLabel = label,
            targetDateLocal = targetDateLocal,
            startHour = firstHour,
            allDayForecastResults = allDayForecastResults,
            allDayEvidences = deduped,
        )
        val sourceStatusOrder = listOf("qweather", "openmeteo", "china")
        val orderedStatuses = sourceStatuses.sortedWith(
            compareBy<SourceFetchStatus> {
                sourceStatusOrder.indexOf(it.sourceId).let { index -> if (index == -1) 99 else index }
            }.thenBy { it.displayName }
        )

        // Keep the status list stable even when a provider returned an empty response.
        // The UI uses this to distinguish NO_DATA/FAILED from an omitted metric.
        val normalizedStatuses = orderedStatuses.map { status ->
            status.copy(evidenceCount = status.evidenceCount.coerceAtLeast(
                sourceGroups.values.filter { it.firstOrNull()?.provider?.id == status.sourceId }.sumOf { it.size }
            ))
        }

        return TomorrowRainSnapshot(
            targetLocation = targetLocation,
            targetDateLocal = targetDateLocal,
            timeZoneId = targetLocation.timeZoneId,
            evidences = deduped,
            temperatureEvidences = temperatureEvidences,
            sourceStatuses = normalizedStatuses,
            slots = slots,
            primarySourceId = primary?.sourceId,
            primarySourceDisplayName = primary?.displayName,
            headline = headline,
            summary = summary,
            fetchedAtEpochMs = fetchedAtEpochMs,
            recoveredFromStorage = recoveredFromStorage,
            isToday = isToday,
            analysisStartEpochMs = analysisStartEpochMs,
            allDayForecastResults = allDayForecastResults,
            reliableNoObviousPrecipitationWindows = buildReliableNoPrecipitationWindows(allDayForecastResults),
        )
    }

    fun todayDateLocal(nowEpochMs: Long, timeZoneId: String): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone(timeZoneId)
        }.format(Date(nowEpochMs))

    fun tomorrowDateLocal(nowEpochMs: Long, timeZoneId: String): String {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone(timeZoneId), Locale.CHINA).apply {
            timeInMillis = nowEpochMs
            add(Calendar.DAY_OF_YEAR, 1)
        }
        return SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone(timeZoneId)
        }.format(calendar.time)
    }

    fun sourceDisplayName(provider: WeatherProvider, model: UnderlyingModel): String {
        return when (provider) {
            WeatherProvider.QWEATHER -> "和风天气"
            WeatherProvider.BREEZY_CHINA -> "小米天气（缓存）"
            WeatherProvider.OPEN_METEO -> when (model) {
                UnderlyingModel.ECMWF_IFS -> "欧洲 IFS（全球）"
                UnderlyingModel.NOAA_GFS -> "美国 GFS（全球）"
                UnderlyingModel.DWD_ICON -> "德国 ICON（全球）"
                UnderlyingModel.CMA_GRAPES -> "CMA 国内模式，经 Open-Meteo 获取"
                UnderlyingModel.JMA_GSM -> "日本 GSM（全球）"
                UnderlyingModel.OPEN_METEO_BEST_MATCH -> "Best match（组合预报）"
                else -> model.displayName.substringBefore(" (")
            }
            WeatherProvider.UNKNOWN -> model.displayName
        }
    }

    private fun aggregateSourceForSlot(
        definition: RainTimeSlotDefinition,
        evidenceForSource: List<ForecastEvidence>,
        timeZoneId: String,
        targetDateLocal: String,
    ): SourceSlotMetrics {
        val first = evidenceForSource.first()
        val slotEvidences = evidenceForSource.filter {
            it.precipitationPeriodKnown && isInTargetDate(it.validFromEpochMs, targetDateLocal, timeZoneId) &&
                localHour(it.validFromEpochMs, timeZoneId) in definition.startHour until definition.endHour
        }
        val temperatures = evidenceForSource.mapNotNull { e ->
            e.temperatureAtEpochMs?.takeIf { isInTargetDate(it, targetDateLocal, timeZoneId) &&
                localHour(it, timeZoneId) in definition.startHour until definition.endHour }?.let {
                HourlyTemperatureReading(sourceDisplayName(e.provider, e.underlyingModel), it, e.temperatureCelsius)
            }
        }.distinctBy { it.atEpochMs }.sortedBy { it.atEpochMs }
        val groupedByHour = slotEvidences.groupBy { localHour(it.validFromEpochMs, timeZoneId) }
        val byHour = groupedByHour.mapValues { (_, evidences) ->
            evidences.maxByOrNull { it.issuedAtEpochMs } ?: evidences.first()
        }
        val expectedHours = (definition.startHour until definition.endHour).toList()
        val probabilityEntries = expectedHours.mapNotNull { hour ->
            byHour[hour]?.precipitationProbability?.percentage?.let { percentage -> hour to percentage }
        }
        val amountEntries = expectedHours.mapNotNull { hour ->
            byHour[hour]?.precipitationAmount?.takeIf {
                it.availability == EvidenceAvailability.AVAILABLE && it.valueMm != null
            }?.valueMm?.let { hour to it }
        }
        val duplicateHours = groupedByHour.filterValues { it.size > 1 }.keys
        val amountComplete = duplicateHours.isEmpty() &&
            byHour.keys.containsAll(expectedHours) &&
            amountEntries.size == expectedHours.size
        val maxProbability = probabilityEntries.maxByOrNull { it.second }

        return SourceSlotMetrics(
            sourceId = first.sourceIdentity.provider.id + ":" + first.sourceIdentity.underlyingModel.id,
            displayName = sourceDisplayName(first.provider, first.underlyingModel),
            providerName = if (first.provider == WeatherProvider.BREEZY_CHINA) "小米天气网关（缓存）" else first.provider.displayName,
            modelName = first.underlyingModel.displayName,
            provenanceDetail = first.provenanceDetail,
            rawSource = first.rawSource,
            evidenceCount = maxOf(slotEvidences.size, temperatures.size),
            expectedHourCount = expectedHours.size,
            availableAmountHourCount = amountEntries.size,
            maxProbabilityPercent = maxProbability?.second,
            maxProbabilityHour = maxProbability?.first,
            totalAmountMm = amountEntries.takeIf { amountComplete }?.sumOf { it.second },
            amountComplete = amountComplete,
            latestIssuedAtEpochMs = evidenceForSource.maxOfOrNull { it.issuedAtEpochMs } ?: 0L,
            latestFetchedAtEpochMs = evidenceForSource.mapNotNull { it.fetchedAtEpochMs }.maxOrNull(),
            sourceUpdatedAtEpochMs = evidenceForSource.mapNotNull { it.sourceUpdatedAtEpochMs }.maxOrNull(),
            spatialResolutionDetail = first.spatialResolutionDetail,
            temporalResolutionDetail = first.temporalResolutionDetail,
            gridLatitude = first.resolvedLatitude,
            gridLongitude = first.resolvedLongitude,
            precipitationPeriodKnown = first.precipitationPeriodKnown,
            temperatureReadings = temperatures,
            conditionKnownHourCount = byHour.values.count { it.weatherCondition?.isKnown == true },
            duplicateHourCount = duplicateHours.size,
            amountPhase = aggregatePhase(slotEvidences),
            probabilityEventDefinitions = slotEvidences.mapNotNull { it.probabilityEventDefinition }
                .filter { it.isNotBlank() }
                .distinct(),
        )
    }

    private fun choosePrimarySource(slots: List<RainTimeSlot>): SourceSlotMetrics? {
        val allMetrics = slots.flatMap { it.sourceMetrics }
        val withData = allMetrics.filter { it.hasUsableMetric }
        return withData.firstOrNull { it.sourceId.startsWith("qweather:") }
            ?: withData.firstOrNull { it.sourceId == "openmeteo:${UnderlyingModel.OPEN_METEO_BEST_MATCH.id}" }
            ?: withData.firstOrNull { it.sourceId.startsWith("openmeteo:") }
            ?: withData.firstOrNull()
    }

    private fun buildHeadline(
        dayLabel: String,
        targetDateLocal: String,
        startHour: Int,
        allDayForecastResults: List<ModelPeriodForecastResult>,
        allDayEvidences: List<ForecastEvidence>,
    ): String {
        if (allDayEvidences.isEmpty()) return "$dayLabel ${if (startHour == 0) "全天" else "%02d:00起".format(Locale.US, startHour)}暂无有效天气数据（$targetDateLocal）"
        val results = allDayForecastResults.filterNot { it.isDuplicatePhysicalModelEvidence }
        return when {
            results.any { it.rainWindows.isNotEmpty() } -> "${dayLabel}有雨预报时段"
            results.any { it.precipitationWindows.isNotEmpty() } -> "${dayLabel}有降水信号，类型或资料待确认"
            results.any { it.noObviousPrecipitationWindows.isNotEmpty() } -> "${dayLabel}的完整预报暂未报出明显降水"
            else -> "${dayLabel}预报资料不足，暂不能判断有无明显降水"
        }
    }

    private fun buildModelPeriodResults(
        assessments: List<HourlyForecastAssessment>,
        startHour: Int,
        endHour: Int,
        timeZoneId: String,
    ): List<ModelPeriodForecastResult> {
        val expectedHourCount = (endHour - startHour).coerceAtLeast(0)
        if (expectedHourCount == 0) return emptyList()
        val inPeriod = assessments.filter {
            localHour(it.validFromEpochMs, timeZoneId) in startHour until endHour
        }
        return inPeriod.groupBy { it.forecastKey }
            .values
            .map { rows ->
                val ordered = rows.sortedBy { it.validFromEpochMs }
                val first = ordered.first()
                val observedHourCount = ordered.map { localHour(it.validFromEpochMs, timeZoneId) }.distinct().size
                val amountAvailableHourCount = ordered.count { it.amountMm != null }
                val amountComplete = observedHourCount == expectedHourCount &&
                    amountAvailableHourCount == expectedHourCount
                ModelPeriodForecastResult(
                    forecastKey = first.forecastKey,
                    sourceIdentityId = first.sourceIdentityId,
                    displayName = first.displayName,
                    providerName = first.providerName,
                    modelName = first.modelName,
                    isIndependentPhysicalModel = ordered.any { it.isIndependentPhysicalModel },
                    isDuplicatePhysicalModelEvidence = ordered.all { it.isDuplicatePhysicalModelEvidence },
                    expectedHourCount = expectedHourCount,
                    observedHourCount = observedHourCount,
                    hourlyAssessments = ordered,
                    rainWindows = windowsFor(ordered, HourlyForecastResult.RAIN_SIGNAL),
                    precipitationWindows = windowsFor(ordered, HourlyForecastResult.PRECIPITATION_SIGNAL),
                    noObviousPrecipitationWindows = windowsFor(ordered, HourlyForecastResult.NO_OBVIOUS_PRECIPITATION),
                    insufficientWindows = windowsFor(ordered, HourlyForecastResult.INSUFFICIENT_DATA),
                    amountAvailableHourCount = amountAvailableHourCount,
                    amountComplete = amountComplete,
                    totalAmountMm = ordered.sumOf { it.amountMm ?: 0.0 }.takeIf { amountComplete },
                )
            }
            .sortedWith(compareBy<ModelPeriodForecastResult> { !it.isIndependentPhysicalModel }
                .thenBy { it.isDuplicatePhysicalModelEvidence }
                .thenBy { it.displayName })
    }

    private fun windowsFor(
        assessments: List<HourlyForecastAssessment>,
        result: HourlyForecastResult,
    ): List<ForecastTimeWindow> {
        val matching = assessments.filter { it.result == result }.sortedBy { it.validFromEpochMs }
        if (matching.isEmpty()) return emptyList()
        val windows = mutableListOf<ForecastTimeWindow>()
        var start = matching.first().validFromEpochMs
        var end = matching.first().validToEpochMs
        matching.drop(1).forEach { assessment ->
            if (assessment.validFromEpochMs == end) {
                end = assessment.validToEpochMs
            } else {
                windows += ForecastTimeWindow(start, end)
                start = assessment.validFromEpochMs
                end = assessment.validToEpochMs
            }
        }
        windows += ForecastTimeWindow(start, end)
        return windows
    }

    private fun buildReliableNoPrecipitationWindows(
        results: List<ModelPeriodForecastResult>,
        expectedModelKeys: Set<String>? = null,
    ): List<ForecastTimeWindow> {
        val independentModels = expectedModelKeys ?: results
            .filter { it.isIndependentPhysicalModel && !it.isDuplicatePhysicalModelEvidence }
            .map { it.forecastKey }
            .toSet()
        if (independentModels.size < 2) return emptyList()

        val assessmentsByHour = results
            .filter { it.isIndependentPhysicalModel && !it.isDuplicatePhysicalModelEvidence }
            .flatMap { it.hourlyAssessments }
            .groupBy { it.validFromEpochMs }
        val fullyDryHours = assessmentsByHour.values.mapNotNull { hourAssessments ->
            val byModel = hourAssessments.associateBy { it.forecastKey }
            if (byModel.keys == independentModels &&
                byModel.values.all { it.result == HourlyForecastResult.NO_OBVIOUS_PRECIPITATION }
            ) hourAssessments.first() else null
        }.sortedBy { it.validFromEpochMs }
        if (fullyDryHours.isEmpty()) return emptyList()

        val windows = mutableListOf<ForecastTimeWindow>()
        var start = fullyDryHours.first().validFromEpochMs
        var end = fullyDryHours.first().validToEpochMs
        fullyDryHours.drop(1).forEach { assessment ->
            if (assessment.validFromEpochMs == end) {
                end = assessment.validToEpochMs
            } else {
                windows += ForecastTimeWindow(start, end)
                start = assessment.validFromEpochMs
                end = assessment.validToEpochMs
            }
        }
        windows += ForecastTimeWindow(start, end)
        return windows
    }

    private fun aggregatePhase(evidences: List<ForecastEvidence>): PrecipitationPhase {
        val phases = evidences.mapNotNull { it.weatherCondition?.precipitationPhase }.toSet()
        val meaningful = phases - PrecipitationPhase.OTHER
        return when {
            PrecipitationPhase.MIXED in meaningful -> PrecipitationPhase.MIXED
            PrecipitationPhase.RAIN in meaningful && PrecipitationPhase.SNOW in meaningful -> PrecipitationPhase.MIXED
            PrecipitationPhase.RAIN in meaningful -> PrecipitationPhase.RAIN
            PrecipitationPhase.SNOW in meaningful -> PrecipitationPhase.SNOW
            PrecipitationPhase.UNKNOWN in phases -> PrecipitationPhase.UNKNOWN
            else -> PrecipitationPhase.OTHER
        }
    }

    private fun isInTargetDate(epochMs: Long, targetDateLocal: String, timeZoneId: String): Boolean {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone(timeZoneId)
        }
        return format.format(Date(epochMs)) == targetDateLocal
    }

    private fun localHour(epochMs: Long, timeZoneId: String): Int {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone(timeZoneId), Locale.CHINA).apply {
            timeInMillis = epochMs
        }
        return calendar.get(Calendar.HOUR_OF_DAY)
    }

    private val sourceComparator = compareBy<SourceSlotMetrics> {
        when {
            it.sourceId.startsWith("qweather:") -> 0
            it.sourceId.startsWith("openmeteo:") -> 1
            it.sourceId.startsWith("china:") -> 2
            else -> 3
        }
    }.thenBy { it.displayName }
}
