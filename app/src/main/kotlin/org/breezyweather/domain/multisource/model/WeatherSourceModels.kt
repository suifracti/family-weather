/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource.model

/**
 * Weather service / API gateway provider.
 */
enum class WeatherProvider(val id: String, val displayName: String) {
    BREEZY_CHINA("china", "北京气象服务 / 彩云"),
    QWEATHER("qweather", "和风天气 QWeather"),
    OPEN_METEO("openmeteo", "Open-Meteo"),
    UNKNOWN("unknown", "未知预报源")
}

/**
 * Lineage classification of a forecast model or aggregator.
 */
enum class ModelLineage {
    /** Independent physical Numerical Weather Prediction (NWP) global model */
    PHYSICAL_NWP_GLOBAL,
    /** Independent physical NWP regional/national model */
    PHYSICAL_NWP_REGIONAL,
    /** Multi-model router / dynamic ensemble selector (e.g. Open-Meteo Best Match) */
    ROUTER_COMPOSITE,
    /** Commercial / regional gateway with opaque multi-model assimilation */
    OPAQUE_COMPOSITE,
    UNKNOWN
}

/**
 * Specific underlying forecast model, assimilation pipeline, or composite router.
 */
enum class UnderlyingModel(
    val id: String,
    val displayName: String,
    val lineage: ModelLineage,
    val isPhysicalNumericalModel: Boolean
) {
    ECMWF_IFS("ecmwf_ifs", "ECMWF IFS (欧洲中期数值预报)", ModelLineage.PHYSICAL_NWP_GLOBAL, true),
    CMA_GRAPES("cma_grapes", "CMA GRAPES / CMA-GFS (中国气象局全球模式)", ModelLineage.PHYSICAL_NWP_REGIONAL, true),
    NOAA_GFS("gfs_global", "NOAA GFS (美国国家气象局全球系统)", ModelLineage.PHYSICAL_NWP_GLOBAL, true),
    DWD_ICON("icon_global", "DWD ICON (德国气象局非静力模型)", ModelLineage.PHYSICAL_NWP_GLOBAL, true),
    JMA_GSM("jma_gsm", "JMA GSM (日本气象厅全球谱模式)", ModelLineage.PHYSICAL_NWP_REGIONAL, true),

    // Opaque / composite providers: contribute provider evidence, but CANNOT masquerade as independent physical NWP models
    CAIYUN_REGIONAL("caiyun_radar", "彩云 / 北京局雷达外推与区域同化", ModelLineage.OPAQUE_COMPOSITE, false),
    QWEATHER_AGGREGATED("qweather_multi_model", "和风天气多源综合同化预报", ModelLineage.OPAQUE_COMPOSITE, false),

    // Router / composite selector: strictly non-physical until a verified model run proves its resolution
    OPEN_METEO_BEST_MATCH("best_match", "Open-Meteo 最佳匹配 (组合优选)", ModelLineage.ROUTER_COMPOSITE, false),

    UNKNOWN("unknown", "未知模式", ModelLineage.UNKNOWN, false)
}

/**
 * Distinguishes service provider from underlying model and tracks lineage.
 */
data class SourceIdentity(
    val provider: WeatherProvider,
    val underlyingModel: UnderlyingModel,
    /**
     * Set only when an explicit, verifiable physical NWP model run is proven.
     * For Open-Meteo Best Match, this is null by default (RESOLVED_MODEL_UNKNOWN)
     * unless verified by response metadata.
     */
    val resolvedPhysicalModel: UnderlyingModel? = null
) {
    val id: String = "${provider.id}:${underlyingModel.id}"

    companion object {
        fun resolve(
            provider: WeatherProvider,
            model: UnderlyingModel,
            verifiedPhysicalModel: UnderlyingModel? = null
        ): SourceIdentity {
            val resolved = when {
                model.isPhysicalNumericalModel -> model
                verifiedPhysicalModel != null && verifiedPhysicalModel.isPhysicalNumericalModel -> verifiedPhysicalModel
                else -> null
            }
            return SourceIdentity(provider, model, resolved)
        }
    }
}

/**
 * Availability state of an evidence field.
 */
enum class EvidenceAvailability {
    AVAILABLE,
    UNAVAILABLE,
    FAIL_CLOSED_SUPPRESSED
}

/**
 * Numerical precipitation value with explicit availability.
 * Never defaults missing values to 0.0 mm.
 */
data class PrecipitationValue(
    val valueMm: Double?,
    val availability: EvidenceAvailability,
    val unit: String = "mm"
) {
    init {
        if (availability == EvidenceAvailability.AVAILABLE) {
            require(valueMm != null && valueMm >= 0.0) {
                "Precipitation amount must be non-null and >= 0.0 when AVAILABLE."
            }
        } else {
            require(valueMm == null) {
                "Precipitation amount must be null when UNAVAILABLE or FAIL_CLOSED_SUPPRESSED. Prohibited to pad with 0."
            }
        }
    }

    companion object {
        fun available(mm: Double, unit: String = "mm"): PrecipitationValue =
            PrecipitationValue(mm, EvidenceAvailability.AVAILABLE, unit)

        fun unavailable(): PrecipitationValue =
            PrecipitationValue(null, EvidenceAvailability.UNAVAILABLE)
    }
}

/**
 * Probability of precipitation (PoP) with strict truth guarantees.
 * Prohibits synthetic generation from weather text/icons.
 */
data class PrecipitationProbabilityValue(
    val percentage: Int?,
    val availability: EvidenceAvailability,
    val rawDiscreteValue: String? = null,
    val canonicalRatio: Double? = percentage?.let { it / 100.0 }
) {
    val canonicalProbabilityRatio: Double? get() = canonicalRatio

    init {
        if (availability == EvidenceAvailability.AVAILABLE) {
            require(percentage != null && percentage in 0..100) {
                "Precipitation probability must be in range 0..100 when AVAILABLE."
            }
        } else {
            require(percentage == null) {
                "Precipitation probability must be null when UNAVAILABLE or FAIL_CLOSED_SUPPRESSED."
            }
        }
    }

    companion object {
        fun available(percent: Int): PrecipitationProbabilityValue =
            PrecipitationProbabilityValue(
                percentage = percent.coerceIn(0, 100),
                availability = EvidenceAvailability.AVAILABLE,
                canonicalRatio = percent.coerceIn(0, 100) / 100.0
            )

        fun fromRatio(ratio: Double, rawString: String? = null): PrecipitationProbabilityValue {
            val clampedRatio = ratio.coerceIn(0.0, 1.0)
            val percent = Math.round(clampedRatio * 100.0).toInt().coerceIn(0, 100)
            return PrecipitationProbabilityValue(
                percentage = percent,
                availability = EvidenceAvailability.AVAILABLE,
                rawDiscreteValue = rawString,
                canonicalRatio = clampedRatio
            )
        }

        fun unavailable(): PrecipitationProbabilityValue =
            PrecipitationProbabilityValue(null, EvidenceAvailability.UNAVAILABLE, null, null)

        fun failClosedSuppressed(rawDiscrete: String?): PrecipitationProbabilityValue =
            PrecipitationProbabilityValue(null, EvidenceAvailability.FAIL_CLOSED_SUPPRESSED, rawDiscrete, null)
    }
}

/**
 * The physical phase of a precipitation signal.  Total precipitation is not
 * silently relabelled as rain when the upstream response does not establish
 * its phase.
 */
enum class PrecipitationPhase {
    RAIN,
    SNOW,
    MIXED,
    OTHER,
    UNKNOWN
}

/**
 * Small, shared classifier for provider weather codes/text.  This is an
 * allow-list of known phenomena, not a `contains("雨")` event detector.
 */
object WeatherConditionClassifier {
    fun classify(
        text: String?,
        code: String? = null,
        explicitType: String? = null,
    ): PrecipitationPhase {
        val type = explicitType?.trim()?.lowercase().orEmpty()
        when {
            type.contains("mixed") || type.contains("sleet") || type.contains("rain_snow") || type.contains("rain-snow") ->
                return PrecipitationPhase.MIXED
            type.contains("snow") -> return PrecipitationPhase.SNOW
            type.contains("rain") || type.contains("drizzle") -> return PrecipitationPhase.RAIN
        }

        val numericCode = code?.trim()?.toIntOrNull()
        if (numericCode != null) {
            when {
                numericCode in setOf(400, 401, 402, 403, 404, 405, 406, 407, 408, 409, 410) ->
                    return PrecipitationPhase.SNOW
                numericCode in setOf(300, 301, 302, 303, 304, 305, 306, 307, 308, 309, 310, 311, 312, 313, 314, 315, 316, 317, 318, 350, 351) ->
                    return PrecipitationPhase.RAIN
                numericCode in setOf(399, 499) -> return PrecipitationPhase.UNKNOWN
            }
        }

        val normalized = text?.trim().orEmpty()
        if (normalized.isBlank() || normalized == "未知" || normalized == "天气现象未知") {
            return PrecipitationPhase.UNKNOWN
        }
        if (normalized.contains("无雨") || normalized.contains("无降水") || normalized.contains("无明显降水")) {
            return PrecipitationPhase.OTHER
        }
        if (normalized.contains("雨夹雪") || normalized.contains("雨雪") || normalized.contains("冰雨")) {
            return PrecipitationPhase.MIXED
        }
        if (normalized.contains("雪")) return PrecipitationPhase.SNOW
        if (normalized.contains("雨") || normalized.contains("毛毛雨")) return PrecipitationPhase.RAIN

        return when (normalized) {
            "晴", "多云", "阴", "雾", "霾", "沙尘", "浮尘", "扬沙", "强沙尘暴" -> PrecipitationPhase.OTHER
            else -> PrecipitationPhase.UNKNOWN
        }
    }
}

/**
 * Weather condition description and classification.
 */
data class WeatherCondition(
    val text: String,
    val code: String? = null,
    val isRainCondition: Boolean = false,
    val precipitationPhase: PrecipitationPhase = if (isRainCondition) {
        PrecipitationPhase.RAIN
    } else {
        WeatherConditionClassifier.classify(text, code)
    }
) {
    val isKnown: Boolean
        get() = precipitationPhase != PrecipitationPhase.UNKNOWN

    companion object {
        fun fromProvider(
            text: String,
            code: String? = null,
            explicitType: String? = null,
        ): WeatherCondition {
            val phase = WeatherConditionClassifier.classify(text, code, explicitType)
            return WeatherCondition(
                text = text,
                code = code,
                isRainCondition = phase == PrecipitationPhase.RAIN,
                precipitationPhase = phase
            )
        }
    }
}

/**
 * Defines a formal rain event definition for consensus comparison.
 */
data class RainEventDefinition(
    val minPrecipitationMm: Double = 0.1,
    val description: String = "预测时段内累计降水 >= 0.1mm"
)

enum class ForecastHorizonType {
    /**
     * Forecast horizon: typically tomorrow (or Day 1~7) numerical or daily/hourly forecast.
     */
    FORECAST,

    /**
     * Nowcast horizon: next 0~2 hours radar extrapolation / minutely precipitation.
     */
    NOWCAST
}

/**
 * Atomic forecast evidence item with complete provenance and temporal lineage.
 */
data class ForecastEvidence(
    val canonicalLocationId: String,
    val sourceIdentity: SourceIdentity,
    val issuedAtEpochMs: Long,
    val validFromEpochMs: Long,
    val validToEpochMs: Long,
    val modelRunInitializationEpochMs: Long? = null,
    val modelRunId: String? = null,
    val weatherCondition: WeatherCondition?,
    val precipitationProbability: PrecipitationProbabilityValue,
    val precipitationAmount: PrecipitationValue,
    val precipitationIntensity: PrecipitationValue,
    val probabilityEventDefinition: String? = null,
    val freshnessSeconds: Long,
    val rawSource: String,
    val provenanceDetail: String,
    val horizonType: ForecastHorizonType = ForecastHorizonType.FORECAST,
    // 0 issuedAt means publication time was not supplied, never fetch time.
    val fetchedAtEpochMs: Long? = null,
    val sourceUpdatedAtEpochMs: Long? = null,
    val temperatureCelsius: Double? = null,
    val temperatureAtEpochMs: Long? = null,
    val spatialResolutionDetail: String = "范围未提供",
    val temporalResolutionDetail: String = "时间分辨率未提供",
    val resolvedLatitude: Double? = null,
    val resolvedLongitude: Double? = null,
    val precipitationPeriodKnown: Boolean = true,
) {
    val provider: WeatherProvider get() = sourceIdentity.provider
    val underlyingModel: UnderlyingModel get() = sourceIdentity.underlyingModel
    val resolvedPhysicalModel: UnderlyingModel? get() = sourceIdentity.resolvedPhysicalModel

    /**
     * Determines whether this evidence indicates a positive rain event
     * based on strict, non-manufactured facts.
     */
    fun indicatesRain(eventDef: RainEventDefinition = RainEventDefinition()): Boolean {
        val amount = precipitationAmount.valueMm
        val phase = weatherCondition?.precipitationPhase ?: PrecipitationPhase.UNKNOWN
        return phase == PrecipitationPhase.RAIN &&
            (amount == null || amount >= eventDef.minPrecipitationMm || weatherCondition?.isRainCondition == true)
    }

    fun hasPositiveAmount(eventDef: RainEventDefinition = RainEventDefinition()): Boolean {
        return precipitationAmount.valueMm?.let { it >= eventDef.minPrecipitationMm } == true
    }

    fun hasPositiveProbability(): Boolean {
        return precipitationProbability.percentage?.let { it > 0 } == true
    }

    fun hasPrecipitationSignal(eventDef: RainEventDefinition = RainEventDefinition()): Boolean {
        val phase = weatherCondition?.precipitationPhase ?: PrecipitationPhase.UNKNOWN
        return hasPositiveAmount(eventDef) || hasPositiveProbability() ||
            phase == PrecipitationPhase.RAIN ||
            phase == PrecipitationPhase.SNOW ||
            phase == PrecipitationPhase.MIXED
    }
}

/**
 * Risk tendency enum for agricultural decision support.
 */
enum class RiskTendency(val displayText: String) {
    HIGH_RISK("很可能"),
    MODERATE_RISK("可能"),
    LOW_RISK("不会"),
    INCONCLUSIVE("待确定")
}

/**
 * Aggregated multi-source consensus report.
 * Separates provider counts from independent physical NWP model counts.
 */
data class MultiSourceConsensus(
    val evidenceCount: Int,
    val independentProviderCount: Int,
    val independentPhysicalModelCount: Int,
    val agreeingRainModels: List<UnderlyingModel>,
    val agreeingNoRainModels: List<UnderlyingModel>,
    val contributingProviders: List<WeatherProvider>,
    val agreementPercentage: Double,
    val disagreementSummary: String,
    val overallRiskTendency: RiskTendency,
    val freshestIssueTimeEpochMs: Long,
    val oldestIssueTimeEpochMs: Long,
    val evidences: List<ForecastEvidence>
)
