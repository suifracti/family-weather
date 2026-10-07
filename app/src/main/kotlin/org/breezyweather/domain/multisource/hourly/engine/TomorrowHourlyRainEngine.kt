/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource.hourly.engine

import org.breezyweather.domain.multisource.engine.WeatherEvidenceEngine
import org.breezyweather.domain.multisource.hourly.model.AgriculturalWashoutRisk
import org.breezyweather.domain.multisource.hourly.model.DayOccurrenceConsensus
import org.breezyweather.domain.multisource.hourly.model.DayOccurrenceStatus
import org.breezyweather.domain.multisource.hourly.model.DecisionQualifier
import org.breezyweather.domain.multisource.hourly.model.ElderPeriodRainProjection
import org.breezyweather.domain.multisource.hourly.model.ElderTimePeriod
import org.breezyweather.domain.multisource.hourly.model.HourlyForecastAssessment
import org.breezyweather.domain.multisource.hourly.model.HourlyForecastResult
import org.breezyweather.domain.multisource.hourly.model.ModelCoverage
import org.breezyweather.domain.multisource.hourly.model.PeriodRainStatus
import org.breezyweather.domain.multisource.hourly.model.RainDecisionContract
import org.breezyweather.domain.multisource.hourly.model.TimingConsensus
import org.breezyweather.domain.multisource.hourly.model.TomorrowHourlyRainSummary
import org.breezyweather.domain.multisource.hourly.model.WashoutRiskLevel
import org.breezyweather.domain.multisource.model.ForecastEvidence
import org.breezyweather.domain.multisource.model.EvidenceAvailability
import org.breezyweather.domain.multisource.model.PrecipitationPhase
import org.breezyweather.domain.multisource.model.RainEventDefinition
import org.breezyweather.domain.multisource.model.UnderlyingModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Formal 00:00–24:00 decision engine. Missing data never becomes zero. */
object TomorrowHourlyRainEngine {

    private const val HOUR_MS = 3_600_000L

    fun synthesizeTomorrowSummary(
        canonicalLocationId: String,
        targetDateLocal: String,
        timeZoneId: String = "Asia/Shanghai",
        rawEvidences: List<ForecastEvidence>,
        eventDef: RainEventDefinition = RainEventDefinition(),
        effectiveStartEpochMs: Long? = null,
    ): TomorrowHourlyRainSummary {
        val deduped = WeatherEvidenceEngine.deduplicate(rawEvidences)
            .filter { it.canonicalLocationId == canonicalLocationId }

        val calendar = Calendar.getInstance(TimeZone.getTimeZone(timeZoneId), Locale.CHINA)
        val parts = targetDateLocal.split("-")
        require(parts.size == 3) { "targetDateLocal must be yyyy-MM-dd" }
        calendar.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 0, 0, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startOfDayEpochMs = calendar.timeInMillis
        val endOfDayEpochMs = startOfDayEpochMs + 24 * HOUR_MS
        val boundedStartEpochMs = effectiveStartEpochMs?.coerceIn(startOfDayEpochMs, endOfDayEpochMs)
        val firstAnalysisHour = boundedStartEpochMs?.let { localHour(it, timeZoneId) } ?: 0
        val expectedDayHours = (firstAnalysisHour until 24).toSet()
        val rangeStart = boundedStartEpochMs?.let { formatLocalTime(it, timeZoneId) }
            ?: "$targetDateLocal 00:00"
        val dayOccurrenceWindow = "$rangeStart ~ $targetDateLocal 24:00:00 ($timeZoneId)"

        val dayEvidences = deduped.filter {
            it.validFromEpochMs >= startOfDayEpochMs && it.validFromEpochMs < endOfDayEpochMs
        }.filter {
            boundedStartEpochMs == null || it.validToEpochMs > boundedStartEpochMs
        }
        val independentHourlyEvidences = representativePhysicalEvidences(dayEvidences)
        val hourlyForecastAssessments = buildHourlyForecastAssessments(dayEvidences, independentHourlyEvidences, eventDef)

        val earlyMorning = buildPeriodProjection(
            ElderTimePeriod.EARLY_MORNING,
            dayEvidences.filter { localHour(it.validFromEpochMs, timeZoneId) in 0 until 6 },
            expectedDayHours intersect (0 until 6).toSet(),
            timeZoneId,
            eventDef
        )
        val morning = buildPeriodProjection(
            ElderTimePeriod.MORNING,
            dayEvidences.filter { localHour(it.validFromEpochMs, timeZoneId) in 6 until 12 },
            expectedDayHours intersect (6 until 12).toSet(),
            timeZoneId,
            eventDef
        )
        val afternoon = buildPeriodProjection(
            ElderTimePeriod.AFTERNOON,
            dayEvidences.filter { localHour(it.validFromEpochMs, timeZoneId) in 12 until 18 },
            expectedDayHours intersect (12 until 18).toSet(),
            timeZoneId,
            eventDef
        )
        val night = buildPeriodProjection(
            ElderTimePeriod.NIGHT,
            dayEvidences.filter { localHour(it.validFromEpochMs, timeZoneId) in 18 until 24 },
            expectedDayHours intersect (18 until 24).toSet(),
            timeZoneId,
            eventDef
        )

        val physicalModelGroups = independentHourlyEvidences
            .filter { (it.resolvedPhysicalModel ?: it.underlyingModel).isPhysicalNumericalModel }
            .groupBy { it.resolvedPhysicalModel ?: it.underlyingModel }
        val physicalModelCoverages = physicalModelGroups.mapValues { (model, evidences) ->
            buildCoverage(model.id, evidences, expectedDayHours, timeZoneId)
        }
        val sourceCoverages = dayEvidences
            .groupBy { it.sourceIdentity.id }
            .mapValues { (sourceId, evidences) ->
                buildCoverage(sourceId, evidences, expectedDayHours, timeZoneId)
            }

        val modelSignals = physicalModelGroups.mapValues { (model, evidences) ->
            buildSignal(model.id, evidences, physicalModelCoverages.getValue(model), eventDef)
        }
        val physicalModels = physicalModelGroups.keys.toList()
        val rainModels = modelSignals.filterValues { it.rainSignal }.keys.toList()
        val dryModels = modelSignals.filterValues { it.completeDry }.keys.toList()
        val unknownModels = physicalModels.filter { it !in rainModels && it !in dryModels }
        val hasAnyPositiveSignal = dayEvidences.any { it.hasPrecipitationSignal(eventDef) }
        val phase = aggregatePhase(dayEvidences)

        val qualifiers = linkedSetOf<DecisionQualifier>()
        if (sourceCoverages.values.any { !it.hasCompleteHourlyCoverage }) {
            qualifiers += DecisionQualifier.PARTIAL_COVERAGE
        }
        if (physicalModels.size < 2) {
            qualifiers += DecisionQualifier.LIMITED_SOURCE
        }
        if (rainModels.isNotEmpty() && dryModels.isNotEmpty()) {
            qualifiers += DecisionQualifier.DISAGREEMENT
        }
        if (phase == PrecipitationPhase.UNKNOWN && dayEvidences.any { it.hasPositiveAmount(eventDef) }) {
            qualifiers += DecisionQualifier.PHASE_UNKNOWN
        }
        if (!hasAnyPositiveSignal && dryModels.isEmpty()) {
            qualifiers += DecisionQualifier.INSUFFICIENT_DATA
        }

        val dayStatus = when {
            !hasAnyPositiveSignal && dryModels.isEmpty() -> DayOccurrenceStatus.INCONCLUSIVE
            rainModels.size >= 2 && rainModels.size == physicalModels.size && unknownModels.isEmpty() ->
                DayOccurrenceStatus.VERY_LIKELY_RAIN
            rainModels.size >= 2 && rainModels.size > dryModels.size && unknownModels.isEmpty() ->
                DayOccurrenceStatus.MAJORITY_RAIN
            dryModels.size >= 2 && rainModels.isNotEmpty() && dryModels.size > rainModels.size ->
                DayOccurrenceStatus.MAJORITY_NO_RAIN_MINOR_DIVERGENCE
            dryModels.size >= 2 && dryModels.size == physicalModels.size && rainModels.isEmpty() ->
                DayOccurrenceStatus.CONSENSUS_NO_RAIN
            dryModels.size == 1 && physicalModels.size == 1 && rainModels.isEmpty() ->
                DayOccurrenceStatus.SINGLE_MODEL_NO_RAIN
            rainModels.isNotEmpty() -> DayOccurrenceStatus.SIGNIFICANT_DIVERGENCE
            hasAnyPositiveSignal -> DayOccurrenceStatus.PRECIPITATION_SIGNAL
            else -> DayOccurrenceStatus.INCONCLUSIVE
        }

        val dayConsensus = DayOccurrenceConsensus(
            status = dayStatus,
            rainModelCount = rainModels.size,
            totalPhysicalModelCount = physicalModels.size,
            ratioText = "${rainModels.size}/${physicalModels.size}",
            headline = dayStatus.headline
        )

        val periodProjections = listOf(earlyMorning, morning, afternoon, night)
        val divergentPeriods = periodProjections
            .filter { it.rainStatus == PeriodRainStatus.DIVERGENT }
            .map { it.period }
        val rainingPeriods = periodProjections.filter { it.hasRain }.map { it.period }
        val timingConsensus = TimingConsensus(
            hasTimingDisagreement = divergentPeriods.isNotEmpty(),
            rainingPeriods = rainingPeriods,
            divergentPeriods = divergentPeriods,
            timingSummary = when {
                divergentPeriods.isNotEmpty() -> "但具体时段存在分歧"
                rainingPeriods.isNotEmpty() -> "主要降水集中在${rainingPeriods.joinToString("、") { it.displayName }}"
                else -> "全天各时段无明确降雨信号"
            }
        )

        val completeDailyAmounts = physicalModelGroups.mapNotNull { (model, evidences) ->
            if (physicalModelCoverages.getValue(model).amountComplete) {
                model to evidences.sumOf { it.precipitationAmount.valueMm ?: 0.0 }
            } else {
                null
            }
        }.toMap()
        val highRiskModels = completeDailyAmounts.filterValues { it >= 10.0 }.keys.toList()
        val maxSingleModelMm = completeDailyAmounts.values.maxOrNull() ?: 0.0
        val washoutLevel = when {
            highRiskModels.isEmpty() -> WashoutRiskLevel.NONE
            highRiskModels.size == 1 -> WashoutRiskLevel.ANY_MODEL_HIGH_RISK
            highRiskModels.size == physicalModels.size -> WashoutRiskLevel.CONSENSUS_HIGH_RISK
            highRiskModels.size * 2 > physicalModels.size -> WashoutRiskLevel.MAJORITY_HIGH_RISK
            else -> WashoutRiskLevel.ANY_MODEL_HIGH_RISK
        }
        val agriculturalRisk = AgriculturalWashoutRisk(
            level = washoutLevel,
            maxSingleModelMm = maxSingleModelMm,
            modelsOverThresholdCount = highRiskModels.size,
            totalPhysicalModelCount = physicalModels.size,
            thresholdMm = 10.0,
            adviceText = buildWashoutAdvice(washoutLevel, highRiskModels, maxSingleModelMm)
        )

        val allModelDailyAmountRange = formatAmountRange(completeDailyAmounts.values.toList())
        val rainPositiveDailyAmountRange = formatPositiveAmountRange(
            completeDailyAmounts.values.toList(),
            eventDef.minPrecipitationMm
        )

        val freshest = dayEvidences.maxOfOrNull { it.issuedAtEpochMs } ?: 0L
        val freshnessSummary = if (freshest > 0L) {
            val freshnessSdf = SimpleDateFormat("HH:mm", Locale.CHINA).apply {
                timeZone = TimeZone.getTimeZone(timeZoneId)
            }
            "数据最新生成于 ${freshnessSdf.format(Date(freshest))}"
        } else {
            "时效未知"
        }

        val decisionContract = RainDecisionContract(
            canonicalLocationId = canonicalLocationId,
            targetDateLocal = targetDateLocal,
            timeZoneId = timeZoneId,
            effectiveRange = dayOccurrenceWindow,
            status = dayStatus,
            precipitationPhase = phase,
            qualifiers = qualifiers,
            coverageBySource = sourceCoverages,
            positiveAmountSourceIds = dayEvidences
                .filter { it.hasPositiveAmount(eventDef) }
                .map { it.sourceIdentity.id }
                .distinct(),
            positiveProbabilitySourceIds = dayEvidences
                .filter { it.hasPositiveProbability() }
                .map { it.sourceIdentity.id }
                .distinct(),
            rainPhenomenonSourceIds = dayEvidences
                .filter { it.weatherCondition?.precipitationPhase == PrecipitationPhase.RAIN }
                .map { it.sourceIdentity.id }
                .distinct(),
            precipitationSourceIds = dayEvidences
                .filter { it.hasPrecipitationSignal(eventDef) }
                .map { it.sourceIdentity.id }
                .distinct(),
            rainModelIds = rainModels.map { it.id },
            dryModelIds = dryModels.map { it.id }
        )

        return TomorrowHourlyRainSummary(
            canonicalLocationId = canonicalLocationId,
            targetDateLocal = targetDateLocal,
            timeZone = timeZoneId,
            dayOccurrenceWindow = dayOccurrenceWindow,
            earlyMorning = earlyMorning,
            morning = morning,
            afternoon = afternoon,
            night = night,
            dayOccurrenceConsensus = dayConsensus,
            timingConsensus = timingConsensus,
            agriculturalRisk = agriculturalRisk,
            allModelDailyAmountRange = allModelDailyAmountRange,
            rainPositiveDailyAmountRange = rainPositiveDailyAmountRange,
            independentPhysicalModelCount = physicalModels.size,
            independentProviderCount = dayEvidences.map { it.provider }.distinct().size,
            rainAgreeCount = rainModels.size,
            noRainAgreeCount = dryModels.size,
            disagreementSummary = buildDisagreementSummary(
                physicalModels,
                rainModels,
                dryModels,
                qualifiers,
                hasAnyPositiveSignal
            ),
            freshnessSummary = freshnessSummary,
            overallElderConclusion = buildOverallConclusion(
                dayConsensus,
                timingConsensus,
                agriculturalRisk,
                qualifiers,
                phase
            ),
            allEvidences = dayEvidences,
            decisionContract = decisionContract,
            analysisStartEpochMs = boundedStartEpochMs,
            hourlyForecastAssessments = hourlyForecastAssessments,
        )
    }

    private fun buildPeriodProjection(
        period: ElderTimePeriod,
        evidences: List<ForecastEvidence>,
        expectedHours: Set<Int>,
        timeZoneId: String,
        eventDef: RainEventDefinition,
    ): ElderPeriodRainProjection {
        if (evidences.isEmpty() || expectedHours.isEmpty()) {
            return ElderPeriodRainProjection(
                period = period,
                hasRain = false,
                rainStatus = PeriodRainStatus.INCONCLUSIVE,
                verifiedProbabilityText = null,
                expectedPrecipitationText = "暂无数据",
                allModelAmountRange = "暂无数据",
                rainPositiveModelRange = null,
                rainModelCount = 0,
                totalModelCount = 0,
                rainModelRatio = "0/0",
                maxPrecipitationMm = 0.0,
                disagreementNote = null,
                hourlyEvidences = emptyList(),
                hasPrecipitationSignal = false
            )
        }

        val physicalGroups = representativePhysicalEvidences(evidences)
            .filter { (it.resolvedPhysicalModel ?: it.underlyingModel).isPhysicalNumericalModel }
            .groupBy { it.resolvedPhysicalModel ?: it.underlyingModel }
        val coverages = physicalGroups.mapValues { (model, modelEvidences) ->
            buildCoverage(model.id, modelEvidences, expectedHours, timeZoneId)
        }
        val signals = physicalGroups.mapValues { (model, modelEvidences) ->
            buildSignal(model.id, modelEvidences, coverages.getValue(model), eventDef)
        }
        val physicalModels = physicalGroups.keys.toList()
        val rainModels = signals.filterValues { it.rainSignal }.keys.toList()
        val dryModels = signals.filterValues { it.completeDry }.keys.toList()
        val positiveSignals = evidences.any { it.hasPrecipitationSignal(eventDef) }
        val phase = aggregatePhase(evidences)

        val completeAmounts = physicalGroups.mapNotNull { (model, modelEvidences) ->
            if (coverages.getValue(model).amountComplete) {
                model to modelEvidences.sumOf { it.precipitationAmount.valueMm ?: 0.0 }
            } else {
                null
            }
        }.toMap()
        val maxKnownAmount = evidences.mapNotNull { it.precipitationAmount.valueMm }.maxOrNull() ?: 0.0
        val allAmountRange = formatAmountRange(completeAmounts.values.toList())
        val positiveAmountRange = formatPositiveAmountRange(
            completeAmounts.values.toList(),
            eventDef.minPrecipitationMm
        )
        val hasPartialCoverage = coverages.values.any { !it.hasCompleteHourlyCoverage }
        val divergent = rainModels.isNotEmpty() && dryModels.isNotEmpty()
        val status = when {
            divergent -> PeriodRainStatus.DIVERGENT
            rainModels.isNotEmpty() && maxKnownAmount >= 10.0 -> PeriodRainStatus.SUBSTANTIAL_RAIN
            rainModels.isNotEmpty() -> PeriodRainStatus.PROBABLE_RAIN
            positiveSignals && (phase == PrecipitationPhase.UNKNOWN || phase == PrecipitationPhase.SNOW || phase == PrecipitationPhase.MIXED) ->
                if (phase == PrecipitationPhase.UNKNOWN) PeriodRainStatus.PHASE_UNKNOWN else PeriodRainStatus.PRECIPITATION_SIGNAL
            dryModels.isNotEmpty() && dryModels.size == physicalModels.size && physicalModels.isNotEmpty() && !hasPartialCoverage ->
                PeriodRainStatus.NO_RAIN
            positiveSignals -> PeriodRainStatus.PRECIPITATION_SIGNAL
            else -> PeriodRainStatus.INCONCLUSIVE
        }

        val probabilityText = evidences
            .filter { it.precipitationProbability.percentage != null }
            .groupBy { it.sourceIdentity.id }
            .entries
            .joinToString("，") { (sourceId, sourceEvidences) ->
                val max = sourceEvidences.mapNotNull { it.precipitationProbability.percentage }.maxOrNull()!!
                "${sourceId.substringAfter(":").substringBefore("_")} $max%"
            }.ifBlank { null }

        val disagreementNote = if (divergent) {
            val rainNames = rainModels.joinToString("、") { it.displayName.substringBefore(" ") }
            val dryNames = dryModels.joinToString("、") { it.displayName.substringBefore(" ") }
            "多个预报对明天${period.displayName}是否下雨存在分歧（${rainNames} 预测有雨，${dryNames} 预测无雨）"
        } else {
            null
        }

        val expectedPrecipitationText = when {
            allAmountRange != "时段雨量暂不完整" -> allAmountRange
            positiveSignals && phase == PrecipitationPhase.RAIN -> "已提供降雨信号，但时段雨量不完整"
            positiveSignals -> "有降水信号，但时段量值不完整"
            else -> allAmountRange
        }

        return ElderPeriodRainProjection(
            period = period,
            hasRain = status == PeriodRainStatus.PROBABLE_RAIN || status == PeriodRainStatus.SUBSTANTIAL_RAIN,
            rainStatus = status,
            verifiedProbabilityText = probabilityText,
            expectedPrecipitationText = expectedPrecipitationText,
            allModelAmountRange = allAmountRange,
            rainPositiveModelRange = positiveAmountRange,
            rainModelCount = rainModels.size,
            totalModelCount = physicalModels.size,
            rainModelRatio = "${rainModels.size}/${physicalModels.size}",
            maxPrecipitationMm = maxKnownAmount,
            disagreementNote = disagreementNote,
            hourlyEvidences = evidences,
            precipitationPhase = phase,
            hasPrecipitationSignal = positiveSignals
        )
    }

    private fun representativePhysicalEvidences(
        evidences: List<ForecastEvidence>,
    ): List<ForecastEvidence> = evidences
        .groupBy { it.validFromEpochMs to it.validToEpochMs }
        .values
        .flatMap { intervalEvidences ->
            WeatherEvidenceEngine.resolveIndependentPhysicalEvidences(intervalEvidences)
        }

    private fun buildHourlyForecastAssessments(
        evidences: List<ForecastEvidence>,
        independentEvidences: List<ForecastEvidence>,
        eventDef: RainEventDefinition,
    ): List<HourlyForecastAssessment> {
        val independentSourceIntervals = independentEvidences
            .map { Triple(it.sourceIdentity.id, it.validFromEpochMs, it.validToEpochMs) }
            .toSet()
        val independentModelIntervals = independentEvidences
            .map {
                val model = it.resolvedPhysicalModel ?: it.underlyingModel
                Triple(model.id, it.validFromEpochMs, it.validToEpochMs)
            }
            .toSet()
        val sourceHourEvidence = evidences
            .groupBy { Triple(it.sourceIdentity.id, it.validFromEpochMs, it.validToEpochMs) }
            .values
            .mapNotNull { duplicateGroup -> duplicateGroup.maxByOrNull { it.issuedAtEpochMs } }

        return sourceHourEvidence.map { evidence ->
            val physicalModel = (evidence.resolvedPhysicalModel ?: evidence.underlyingModel)
                .takeIf { it.isPhysicalNumericalModel }
            val isIndependent = physicalModel != null && Triple(
                evidence.sourceIdentity.id,
                evidence.validFromEpochMs,
                evidence.validToEpochMs
            ) in independentSourceIntervals
            val duplicatePhysical = physicalModel != null && !isIndependent && Triple(
                physicalModel.id,
                evidence.validFromEpochMs,
                evidence.validToEpochMs
            ) in independentModelIntervals
            val forecastKey = when {
                isIndependent -> "model:${physicalModel!!.id}"
                duplicatePhysical -> "duplicate:${evidence.sourceIdentity.id}"
                else -> "source:${evidence.sourceIdentity.id}"
            }
            val displayName = when {
                isIndependent -> physicalModel!!.displayName.substringBefore(" (")
                duplicatePhysical -> "${providerDisplayName(evidence)} · ${physicalModel!!.displayName}"
                else -> when (evidence.provider) {
                    org.breezyweather.domain.multisource.model.WeatherProvider.QWEATHER -> "和风天气"
                    org.breezyweather.domain.multisource.model.WeatherProvider.BREEZY_CHINA -> "小米天气（缓存）"
                    else -> evidence.underlyingModel.displayName
                }
            }
            val amount = evidence.precipitationAmount.valueMm.takeIf {
                evidence.precipitationAmount.availability == EvidenceAvailability.AVAILABLE
            }
            val phase = evidence.weatherCondition?.precipitationPhase ?: PrecipitationPhase.UNKNOWN
            val result = when {
                evidence.indicatesRain(eventDef) -> HourlyForecastResult.RAIN_SIGNAL
                evidence.hasPositiveAmount(eventDef) ||
                    phase == PrecipitationPhase.SNOW || phase == PrecipitationPhase.MIXED ->
                    HourlyForecastResult.PRECIPITATION_SIGNAL
                amount != null && amount < eventDef.minPrecipitationMm &&
                    evidence.weatherCondition?.isKnown == true && !evidence.hasPositiveProbability() ->
                    HourlyForecastResult.NO_OBVIOUS_PRECIPITATION
                else -> HourlyForecastResult.INSUFFICIENT_DATA
            }
            HourlyForecastAssessment(
                forecastKey = forecastKey,
                sourceIdentityId = evidence.sourceIdentity.id,
                displayName = displayName,
                providerName = providerDisplayName(evidence),
                modelName = evidence.underlyingModel.displayName,
                isIndependentPhysicalModel = isIndependent,
                isDuplicatePhysicalModelEvidence = duplicatePhysical,
                validFromEpochMs = evidence.validFromEpochMs,
                validToEpochMs = evidence.validToEpochMs,
                result = result,
                amountMm = amount,
                evidence = evidence,
            )
        }.sortedWith(compareBy<HourlyForecastAssessment> { it.validFromEpochMs }.thenBy { it.displayName })
    }

    private fun providerDisplayName(evidence: ForecastEvidence): String = when (evidence.provider) {
        org.breezyweather.domain.multisource.model.WeatherProvider.QWEATHER -> "和风天气"
        org.breezyweather.domain.multisource.model.WeatherProvider.BREEZY_CHINA -> "小米天气（缓存）"
        org.breezyweather.domain.multisource.model.WeatherProvider.OPEN_METEO -> "Open-Meteo"
        org.breezyweather.domain.multisource.model.WeatherProvider.UNKNOWN -> evidence.provider.displayName
    }

    private data class ModelSignal(
        val modelId: String,
        val rainSignal: Boolean,
        val completeDry: Boolean,
    )

    private fun buildSignal(
        modelId: String,
        evidences: List<ForecastEvidence>,
        coverage: ModelCoverage,
        eventDef: RainEventDefinition,
    ): ModelSignal {
        val rainSignal = evidences.any { it.indicatesRain(eventDef) }
        val nonRainPhase = evidences.any {
            it.weatherCondition?.precipitationPhase == PrecipitationPhase.SNOW ||
                it.weatherCondition?.precipitationPhase == PrecipitationPhase.MIXED
        }
        val completeDry = coverage.hasCompleteHourlyCoverage &&
            coverage.amountComplete &&
            coverage.conditionComplete &&
            !rainSignal &&
            !nonRainPhase &&
            evidences.none { it.hasPositiveAmount(eventDef) || it.hasPositiveProbability() }
        return ModelSignal(modelId, rainSignal, completeDry)
    }

    private fun buildCoverage(
        modelId: String,
        evidences: List<ForecastEvidence>,
        expectedHours: Set<Int>,
        timeZoneId: String,
    ): ModelCoverage {
        val entriesByHour = evidences
            .filter { localHour(it.validFromEpochMs, timeZoneId) in expectedHours }
            .groupBy { localHour(it.validFromEpochMs, timeZoneId) }
        val validEntries = evidences.filter { isValidHourlySlot(it, expectedHours, timeZoneId) }
        val validByHour = validEntries.groupBy { localHour(it.validFromEpochMs, timeZoneId) }
        val observedHours = validByHour.keys
        val duplicateHours = entriesByHour.filterValues { it.size > 1 }.keys.sorted()
        val hasCompleteCoverage = observedHours == expectedHours &&
            duplicateHours.isEmpty() &&
            validEntries.size == expectedHours.size
        val amountAvailable = validEntries.count { it.precipitationAmount.valueMm != null }
        val probabilityAvailable = validEntries.count { it.precipitationProbability.percentage != null }
        val conditionKnown = validEntries.count { it.weatherCondition?.isKnown == true }
        return ModelCoverage(
            modelId = modelId,
            expectedSlotCount = expectedHours.size,
            observedSlotCount = observedHours.size,
            missingHours = expectedHours.filter { it !in observedHours }.sorted(),
            duplicateHours = duplicateHours,
            amountAvailableSlotCount = amountAvailable,
            probabilityAvailableSlotCount = probabilityAvailable,
            conditionKnownSlotCount = conditionKnown,
            hasCompleteHourlyCoverage = hasCompleteCoverage,
            amountComplete = hasCompleteCoverage && amountAvailable == expectedHours.size,
            probabilityComplete = hasCompleteCoverage && probabilityAvailable == expectedHours.size,
            conditionComplete = hasCompleteCoverage && conditionKnown == expectedHours.size
        )
    }

    private fun isValidHourlySlot(
        evidence: ForecastEvidence,
        expectedHours: Set<Int>,
        timeZoneId: String,
    ): Boolean {
        val hour = localHour(evidence.validFromEpochMs, timeZoneId)
        val calendar = Calendar.getInstance(TimeZone.getTimeZone(timeZoneId), Locale.CHINA).apply {
            timeInMillis = evidence.validFromEpochMs
        }
        return hour in expectedHours &&
            calendar.get(Calendar.MINUTE) == 0 &&
            calendar.get(Calendar.SECOND) == 0 &&
            calendar.get(Calendar.MILLISECOND) == 0 &&
            evidence.validToEpochMs - evidence.validFromEpochMs == HOUR_MS
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

    private fun formatAmountRange(values: List<Double>): String {
        if (values.isEmpty()) return "时段雨量暂不完整"
        val min = values.minOrNull() ?: return "时段雨量暂不完整"
        val max = values.maxOrNull() ?: return "时段雨量暂不完整"
        return when {
            min == max && min == 0.0 -> "0.0 mm"
            min == max -> String.format(Locale.US, "%.1f mm", max)
            else -> String.format(Locale.US, "%.1f~%.1f mm", min, max)
        }
    }

    private fun formatPositiveAmountRange(values: List<Double>, thresholdMm: Double): String? {
        val positive = values.filter { it >= thresholdMm }
        if (positive.isEmpty()) return null
        val min = positive.minOrNull() ?: return null
        val max = positive.maxOrNull() ?: return null
        return if (min == max) {
            String.format(Locale.US, "约 %.1f mm", max)
        } else {
            String.format(Locale.US, "%.1f~%.1f mm", min, max)
        }
    }

    private fun buildWashoutAdvice(
        level: WashoutRiskLevel,
        highRiskModels: List<UnderlyingModel>,
        maxSingleModelMm: Double,
    ): String = when (level) {
        WashoutRiskLevel.NONE -> "当前完整量值未见明显冲刷风险。"
        WashoutRiskLevel.ANY_MODEL_HIGH_RISK -> {
            val name = highRiskModels.firstOrNull()?.displayName?.substringBefore(" ") ?: "一个模型"
            "有模型预计雨量可能较大（$name 累计可达 ${String.format(Locale.US, "%.1f", maxSingleModelMm)}mm），预报分歧明显，建议关注最新预报。"
        }
        WashoutRiskLevel.MAJORITY_HIGH_RISK -> "多数完整预报预测累计雨量达 10mm 以上，施肥后可能被雨水冲刷，建议暂缓。"
        WashoutRiskLevel.CONSENSUS_HIGH_RISK -> "所有完整预报一致预测累计雨量达 10mm 以上，建议暂缓施肥。"
    }

    private fun buildDisagreementSummary(
        physicalModels: List<UnderlyingModel>,
        rainModels: List<UnderlyingModel>,
        dryModels: List<UnderlyingModel>,
        qualifiers: Set<DecisionQualifier>,
        hasAnySignal: Boolean,
    ): String {
        if (!hasAnySignal && dryModels.isEmpty()) return "全天天气量或覆盖不足，暂不能形成降雨结论。"
        if (physicalModels.isEmpty()) return "当前没有独立物理数值模式，保留已有来源信号但不形成多模型结论。"
        if (rainModels.isNotEmpty() && dryModels.isNotEmpty()) {
            val rainNames = rainModels.joinToString("、") { it.displayName.substringBefore(" ") }
            val dryNames = dryModels.joinToString("、") { it.displayName.substringBefore(" ") }
            return "物理模式分歧：$rainNames 预测有降雨，而 $dryNames 具备完整未达阈值证据。"
        }
        if (dryModels.size == physicalModels.size && dryModels.size >= 2) {
            return "所有 ${dryModels.size} 个独立物理模式具备完整未达阈值证据。"
        }
        if (qualifiers.contains(DecisionQualifier.PARTIAL_COVERAGE)) {
            return "已有降水信号，但部分有效小时或字段未取得。"
        }
        return "当前仅保留已取得的来源原值。"
    }

    private fun buildOverallConclusion(
        dayConsensus: DayOccurrenceConsensus,
        timingConsensus: TimingConsensus,
        agriculturalRisk: AgriculturalWashoutRisk,
        qualifiers: Set<DecisionQualifier>,
        phase: PrecipitationPhase,
    ): String {
        val base = when (dayConsensus.status) {
            DayOccurrenceStatus.VERY_LIKELY_RAIN -> if (timingConsensus.hasTimingDisagreement) {
                "明天很可能会下雨。多个模型都认为明天有雨，但具体降雨时间仍有分歧。"
            } else if (timingConsensus.rainingPeriods.isNotEmpty()) {
                "明天很可能会下雨，${timingConsensus.timingSummary}。"
            } else {
                "明天很可能会下雨。"
            }
            DayOccurrenceStatus.MAJORITY_RAIN -> if (timingConsensus.hasTimingDisagreement) {
                "大多数预报认为明天会下雨，具体时间仍有分歧。"
            } else if (timingConsensus.rainingPeriods.isNotEmpty()) {
                "大多数预报认为明天会下雨，${timingConsensus.timingSummary}。"
            } else {
                "大多数预报认为明天会下雨。"
            }
            DayOccurrenceStatus.MAJORITY_NO_RAIN_MINOR_DIVERGENCE -> {
                val periods = timingConsensus.divergentPeriods.joinToString("与") { it.displayName }
                if (periods.isBlank()) "大多数预报认为明天基本无雨，但仍有少量分歧。"
                else "大多数预报认为明天基本无雨，但${periods}仍有少量分歧。"
            }
            DayOccurrenceStatus.CONSENSUS_NO_RAIN -> "所有独立模式具备完整未达阈值证据，明天基本无明显降雨。施肥前建议再确认最新预报。"
            DayOccurrenceStatus.SINGLE_MODEL_NO_RAIN -> "目前仅一个完整模型，暂未见明显降雨。"
            DayOccurrenceStatus.PRECIPITATION_SIGNAL -> when (phase) {
                PrecipitationPhase.SNOW -> "明天有降水信号，包含降雪现象，不能按无雨处理。"
                PrecipitationPhase.MIXED -> "明天有降水信号，雨雪相态存在混合或分歧。"
                PrecipitationPhase.UNKNOWN -> "明天有降水量或概率信号，但降水类型待确认。"
                else -> "明天有降水信号，但当前证据不足以形成更强结论。"
            }
            DayOccurrenceStatus.SIGNIFICANT_DIVERGENCE -> "多个预报对明天是否下雨存在明显分歧，施肥前建议再次确认最新预报。"
            DayOccurrenceStatus.INCONCLUSIVE -> "明天降雨资料不足，暂时不能确定。"
        }

        var qualified = base
        if (qualifiers.contains(DecisionQualifier.PARTIAL_COVERAGE) && !qualified.contains("资料不完整")) {
            qualified += "（全天资料不完整）"
        }
        if (qualifiers.contains(DecisionQualifier.PHASE_UNKNOWN) && !qualified.contains("类型待确认")) {
            qualified += "（降水类型待确认）"
        }
        return when (agriculturalRisk.level) {
            WashoutRiskLevel.MAJORITY_HIGH_RISK,
            WashoutRiskLevel.CONSENSUS_HIGH_RISK -> "$qualified（${agriculturalRisk.adviceText}）"
            WashoutRiskLevel.ANY_MODEL_HIGH_RISK -> "$qualified（注：${agriculturalRisk.adviceText}）"
            WashoutRiskLevel.NONE -> qualified
        }
    }

    private fun localHour(epochMs: Long, timeZoneId: String): Int {
        return Calendar.getInstance(TimeZone.getTimeZone(timeZoneId), Locale.CHINA).apply {
            timeInMillis = epochMs
        }.get(Calendar.HOUR_OF_DAY)
    }

    private fun formatLocalTime(epochMs: Long, timeZoneId: String): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone(timeZoneId)
        }.format(Date(epochMs))
}
