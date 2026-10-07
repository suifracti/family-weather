/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource.hourly.model

import org.breezyweather.domain.multisource.model.ForecastEvidence
import org.breezyweather.domain.multisource.model.PrecipitationPhase

/**
 * The four coarse-grained, elder-friendly diurnal time periods.
 */
enum class ElderTimePeriod(
    val id: String,
    val displayName: String
) {
    EARLY_MORNING("early_morning", "凌晨"), // 00:00 ~ 06:00
    MORNING("morning", "上午"),             // 06:00 ~ 12:00
    AFTERNOON("afternoon", "下午"),         // 12:00 ~ 18:00
    NIGHT("night", "晚上");                 // 18:00 ~ 24:00

    companion object {
        /**
         * Resolves the corresponding ElderTimePeriod for a given local hour and minute
         * strictly within tomorrow's calendar day [00:00, 24:00).
         * Explicit boundaries:
         * 00:00 -> EARLY_MORNING (凌晨)
         * 05:59 -> EARLY_MORNING (凌晨)
         * 06:00 -> MORNING (上午开始)
         * 11:59 -> MORNING
         * 12:00 -> AFTERNOON (下午开始)
         * 17:59 -> AFTERNOON
         * 18:00 -> NIGHT (晚上开始)
         * 23:59 -> NIGHT
         */
        fun forHourAndMinute(hourOfDay: Int, minuteOfHour: Int = 0): ElderTimePeriod {
            require(hourOfDay in 0..23) { "hourOfDay must be in 0..23, got $hourOfDay" }
            require(minuteOfHour in 0..59) { "minuteOfHour must be in 0..59, got $minuteOfHour" }
            return when (hourOfDay) {
                in 0..5 -> EARLY_MORNING
                in 6..11 -> MORNING
                in 12..17 -> AFTERNOON
                else -> NIGHT
            }
        }

        fun forHour(hourOfDay: Int): ElderTimePeriod = forHourAndMinute(hourOfDay, 0)
    }
}

/**
 * Status of rain in a specific elder time period.
 */
enum class PeriodRainStatus(val displayText: String) {
    NO_RAIN("无雨"),
    PROBABLE_RAIN("可能有雨"),
    SUBSTANTIAL_RAIN("有明显降雨"),
    PRECIPITATION_SIGNAL("有降水信号"),
    PHASE_UNKNOWN("降水类型待确认"),
    DIVERGENT("预报存在分歧"),
    INCONCLUSIVE("待确定")
}

/**
 * Day-level occurrence consensus status based strictly on independent physical models.
 */
enum class DayOccurrenceStatus(val headline: String) {
    VERY_LIKELY_RAIN("明天很可能有雨"),
    MAJORITY_RAIN("多数模型认为明天有雨"),
    MAJORITY_NO_RAIN_MINOR_DIVERGENCE("大多数模型认为基本无雨，但仍有少量分歧"),
    CONSENSUS_NO_RAIN("所有独立模式一致认为明天基本无雨"),
    SINGLE_MODEL_NO_RAIN("目前仅一个完整模型，暂未见明显降雨"),
    PRECIPITATION_SIGNAL("明天有降水信号，但类型或完整性待确认"),
    SIGNIFICANT_DIVERGENCE("多个预报对明天是否下雨存在明显分歧"),
    INCONCLUSIVE("模式数据不足")
}

/** Qualifiers that must survive the domain-to-UI boundary. */
enum class DecisionQualifier {
    INSUFFICIENT_DATA,
    PARTIAL_COVERAGE,
    LIMITED_SOURCE,
    DISAGREEMENT,
    STALE,
    PHASE_UNKNOWN
}

/** Per-source/model slot coverage; missing is not equivalent to zero. */
data class ModelCoverage(
    val modelId: String,
    val expectedSlotCount: Int,
    val observedSlotCount: Int,
    val missingHours: List<Int>,
    val duplicateHours: List<Int>,
    val amountAvailableSlotCount: Int,
    val probabilityAvailableSlotCount: Int,
    val conditionKnownSlotCount: Int,
    val hasCompleteHourlyCoverage: Boolean,
    val amountComplete: Boolean,
    val probabilityComplete: Boolean,
    val conditionComplete: Boolean,
)

/**
 * Structured contract emitted by the formal hourly engine.  Presentation
 * code may choose wording, but it cannot infer stronger semantics than this.
 */
data class RainDecisionContract(
    val canonicalLocationId: String,
    val targetDateLocal: String,
    val timeZoneId: String,
    val effectiveRange: String,
    val status: DayOccurrenceStatus,
    val precipitationPhase: PrecipitationPhase,
    val qualifiers: Set<DecisionQualifier>,
    val coverageBySource: Map<String, ModelCoverage>,
    val positiveAmountSourceIds: List<String>,
    val positiveProbabilitySourceIds: List<String>,
    val rainPhenomenonSourceIds: List<String>,
    val precipitationSourceIds: List<String>,
    val rainModelIds: List<String>,
    val dryModelIds: List<String>,
)

/**
 * Day-level occurrence consensus: addresses whether it will rain tomorrow
 * independent of timing disagreement across diurnal periods.
 */
data class DayOccurrenceConsensus(
    val status: DayOccurrenceStatus,
    val rainModelCount: Int,
    val totalPhysicalModelCount: Int,
    val ratioText: String, // e.g. "5/5", "4/5", "1/5", "0/5"
    val headline: String
)

/**
 * Diurnal timing consensus: addresses when it will rain tomorrow and whether
 * individual period forecasts diverge.
 */
data class TimingConsensus(
    val hasTimingDisagreement: Boolean,
    val rainingPeriods: List<ElderTimePeriod>,
    val divergentPeriods: List<ElderTimePeriod>,
    val timingSummary: String
)

enum class HourlyForecastResult {
    RAIN_SIGNAL,
    PRECIPITATION_SIGNAL,
    NO_OBVIOUS_PRECIPITATION,
    INSUFFICIENT_DATA,
}

/** One source/model's unaveraged result for one forecast hour. */
data class HourlyForecastAssessment(
    val forecastKey: String,
    val sourceIdentityId: String,
    val displayName: String,
    val providerName: String,
    val modelName: String,
    val isIndependentPhysicalModel: Boolean,
    val isDuplicatePhysicalModelEvidence: Boolean,
    val validFromEpochMs: Long,
    val validToEpochMs: Long,
    val result: HourlyForecastResult,
    val amountMm: Double?,
    val evidence: ForecastEvidence,
)

data class ForecastTimeWindow(
    val startEpochMs: Long,
    val endEpochMs: Long,
)

/** Source/model results over the same requested period, retaining gaps as gaps. */
data class ModelPeriodForecastResult(
    val forecastKey: String,
    val sourceIdentityId: String,
    val displayName: String,
    val providerName: String,
    val modelName: String,
    val isIndependentPhysicalModel: Boolean,
    val isDuplicatePhysicalModelEvidence: Boolean,
    val expectedHourCount: Int,
    val observedHourCount: Int,
    val hourlyAssessments: List<HourlyForecastAssessment>,
    val rainWindows: List<ForecastTimeWindow>,
    val precipitationWindows: List<ForecastTimeWindow>,
    val noObviousPrecipitationWindows: List<ForecastTimeWindow>,
    val insufficientWindows: List<ForecastTimeWindow>,
    val amountAvailableHourCount: Int,
    val amountComplete: Boolean,
    val totalAmountMm: Double?,
)

/**
 * Agricultural washout risk level based on independent model agreement.
 */
enum class WashoutRiskLevel(val displayText: String) {
    NONE("无明显冲刷风险"),
    ANY_MODEL_HIGH_RISK("单模型较高风险"),
    MAJORITY_HIGH_RISK("多数模型高风险"),
    CONSENSUS_HIGH_RISK("一致高风险")
}

/**
 * Distinguishes single-model spikes from genuine multi-model consensus risk.
 * A single model exceeding 10mm must NOT claim "预计明显强降雨", but rather "有模型预测明显降雨".
 */
data class AgriculturalWashoutRisk(
    val level: WashoutRiskLevel,
    val maxSingleModelMm: Double,
    val modelsOverThresholdCount: Int,
    val totalPhysicalModelCount: Int,
    val thresholdMm: Double = 10.0,
    val adviceText: String
)

/**
 * Senior-facing rain projection for a single diurnal time period (Early Morning, Morning, Afternoon, Night).
 */
data class ElderPeriodRainProjection(
    val period: ElderTimePeriod,
    val hasRain: Boolean,
    val rainStatus: PeriodRainStatus,
    /** Genuine authoritative PoP with specific model attribution (strictly no arithmetic averaging) */
    val verifiedProbabilityText: String?,
    /** Primary display text for precipitation amount */
    val expectedPrecipitationText: String,
    /** Range across ALL independent models, strictly including 0.0mm models (e.g. "0.0~1.8 mm" or "0.0 mm") */
    val allModelAmountRange: String,
    /** Range for positive models only (e.g. "0.1~1.8 mm", null if no model forecasts rain) */
    val rainPositiveModelRange: String?,
    /** Number of independent models predicting positive rain in this period */
    val rainModelCount: Int,
    /** Total independent physical models active in this period */
    val totalModelCount: Int,
    /** Ratio string (e.g. "4/5") */
    val rainModelRatio: String,
    val maxPrecipitationMm: Double,
    /** Clear natural language note when independent physical models diverge */
    val disagreementNote: String?,
    /** Complete underlying hourly evidences for audit and rationale inspection */
    val hourlyEvidences: List<ForecastEvidence>,
    val precipitationPhase: PrecipitationPhase = PrecipitationPhase.UNKNOWN,
    val hasPrecipitationSignal: Boolean = false
)

/**
 * Unified tomorrow hourly rain summary aggregating multi-source evidence
 * across the entire local calendar day (00:00 - 24:00).
 */
data class TomorrowHourlyRainSummary(
    val canonicalLocationId: String,
    val targetDateLocal: String, // yyyy-MM-dd
    val timeZone: String,
    val dayOccurrenceWindow: String, // e.g. "2026-09-19 00:00:00 ~ 2026-09-19 24:00:00 (Asia/Shanghai)"
    val earlyMorning: ElderPeriodRainProjection,
    val morning: ElderPeriodRainProjection,
    val afternoon: ElderPeriodRainProjection,
    val night: ElderPeriodRainProjection,
    /** Day-level occurrence consensus independent of period timing */
    val dayOccurrenceConsensus: DayOccurrenceConsensus,
    /** Diurnal timing consensus */
    val timingConsensus: TimingConsensus,
    /** Agricultural washout risk with single-model vs consensus demarcation */
    val agriculturalRisk: AgriculturalWashoutRisk,
    /** Range across ALL independent models for the whole day, including 0.0mm models */
    val allModelDailyAmountRange: String,
    /** Range for positive models only for the whole day (null if all 0.0mm) */
    val rainPositiveDailyAmountRange: String?,
    val independentPhysicalModelCount: Int,
    val independentProviderCount: Int,
    val rainAgreeCount: Int,
    val noRainAgreeCount: Int,
    val disagreementSummary: String,
    val freshnessSummary: String,
    /** Natural language elder conclusion combining day occurrence and timing consensus */
    val overallElderConclusion: String,
    val allEvidences: List<ForecastEvidence>,
    val decisionContract: RainDecisionContract? = null,
    val analysisStartEpochMs: Long? = null,
    val hourlyForecastAssessments: List<HourlyForecastAssessment> = emptyList(),
) {
    fun getPeriodProjection(period: ElderTimePeriod): ElderPeriodRainProjection {
        return when (period) {
            ElderTimePeriod.EARLY_MORNING -> earlyMorning
            ElderTimePeriod.MORNING -> morning
            ElderTimePeriod.AFTERNOON -> afternoon
            ElderTimePeriod.NIGHT -> night
        }
    }
}
