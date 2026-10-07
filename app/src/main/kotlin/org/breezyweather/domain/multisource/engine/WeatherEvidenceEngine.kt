/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource.engine

import org.breezyweather.domain.multisource.model.ForecastEvidence
import org.breezyweather.domain.multisource.model.MultiSourceConsensus
import org.breezyweather.domain.multisource.model.RainEventDefinition
import org.breezyweather.domain.multisource.model.RiskTendency
import org.breezyweather.domain.multisource.model.UnderlyingModel

object WeatherEvidenceEngine {

    /**
     * Deduplicates forecast evidence:
     * When multiple reports exist for the same (location, provider, underlyingModel, validFrom, validTo),
     * retains only the freshest report (highest issuedAtEpochMs).
     */
    fun deduplicate(evidences: List<ForecastEvidence>): List<ForecastEvidence> {
        return evidences
            .groupBy {
                "${it.canonicalLocationId}:${it.provider.id}:${it.underlyingModel.id}:${it.validFromEpochMs}:${it.validToEpochMs}"
            }
            .values
            .map { duplicateGroup ->
                duplicateGroup.maxByOrNull { it.issuedAtEpochMs } ?: duplicateGroup.first()
            }
    }

    /**
     * Resolves independent physical numerical models.
     *
     * Rules:
     * 1. QWeather and Breezy China are composite/opaque providers. They provide provider evidence,
     *    but CANNOT masquerade as independent physical numerical model votes without verifiable NWP lineage.
     * 2. Open-Meteo Best Match is a ROUTER_COMPOSITE. If explicit physical models (ECMWF, GFS, ICON, CMA, JMA)
     *    are present, Best Match does NOT contribute to independentPhysicalModelCount.
     * 3. Only deduplicated physical models with verified distinct NWP lineage cast independent model votes.
     */
    fun resolveIndependentPhysicalEvidences(evidences: List<ForecastEvidence>): List<ForecastEvidence> {
        val deduped = deduplicate(evidences)

        // Find explicit standalone physical models
        val explicitPhysical = deduped.filter { it.underlyingModel.isPhysicalNumericalModel }

        // If explicit physical models exist, Best Match does NOT participate in physical model count
        val candidatePool = if (explicitPhysical.isNotEmpty()) {
            explicitPhysical
        } else {
            // If no explicit physical models exist, check if any router evidence has a proven resolved physical model
            deduped.filter { it.resolvedPhysicalModel != null && it.resolvedPhysicalModel!!.isPhysicalNumericalModel }
        }

        // Group by resolved physical model to prevent double-counting across different endpoints
        return candidatePool
            .groupBy { it.resolvedPhysicalModel ?: it.underlyingModel }
            .values
            .map { modelGroup ->
                // Prioritize standalone physical run over composite router
                val standalone = modelGroup.firstOrNull { it.underlyingModel.isPhysicalNumericalModel }
                standalone ?: modelGroup.maxByOrNull { it.issuedAtEpochMs } ?: modelGroup.first()
            }
    }

    /**
     * Aggregates multi-source forecast evidence into a verified consensus.
     *
     * Strict invariants:
     * 1. Distinguishes independentProviderCount from independentPhysicalModelCount.
     * 2. Prohibits simple arithmetic averaging of PoP across models.
     * 3. Prohibits duplicate voting from identical upstream models or router selectors.
     * 4. Missing fields remain null / UNAVAILABLE and are never padded with 0.
     * 5. Weather conditions are never converted into synthetic probability values.
     */
    fun calculateConsensus(
        rawEvidences: List<ForecastEvidence>,
        eventDef: RainEventDefinition = RainEventDefinition()
    ): MultiSourceConsensus {
        if (rawEvidences.isEmpty()) {
            return MultiSourceConsensus(
                evidenceCount = 0,
                independentProviderCount = 0,
                independentPhysicalModelCount = 0,
                agreeingRainModels = emptyList(),
                agreeingNoRainModels = emptyList(),
                contributingProviders = emptyList(),
                agreementPercentage = 0.0,
                disagreementSummary = "无可用天气证据",
                overallRiskTendency = RiskTendency.INCONCLUSIVE,
                freshestIssueTimeEpochMs = 0L,
                oldestIssueTimeEpochMs = 0L,
                evidences = emptyList()
            )
        }

        val deduped = deduplicate(rawEvidences)
        val independentPhysical = resolveIndependentPhysicalEvidences(deduped)
        val distinctProviders = deduped.map { it.provider }.distinct()

        val rainModels = mutableListOf<UnderlyingModel>()
        val noRainModels = mutableListOf<UnderlyingModel>()

        for (evidence in independentPhysical) {
            val model = evidence.resolvedPhysicalModel ?: evidence.underlyingModel
            if (evidence.indicatesRain(eventDef)) {
                rainModels.add(model)
            } else {
                noRainModels.add(model)
            }
        }

        val totalPhysical = independentPhysical.size
        val rainCount = rainModels.size
        val agreementRatio = if (totalPhysical > 0) {
            rainCount.toDouble() / totalPhysical.toDouble()
        } else {
            // If no physical NWP models available, check provider-level indications
            val providerRainCount = deduped.count { it.indicatesRain(eventDef) }
            if (deduped.isNotEmpty()) providerRainCount.toDouble() / deduped.size.toDouble() else 0.0
        }

        val maxPrecipMm = deduped.mapNotNull { it.precipitationAmount.valueMm }.maxOrNull() ?: 0.0

        val tendency = when {
            deduped.isEmpty() -> RiskTendency.INCONCLUSIVE
            maxPrecipMm >= 10.0 || agreementRatio >= 0.70 -> RiskTendency.HIGH_RISK
            agreementRatio >= 0.40 -> RiskTendency.MODERATE_RISK
            else -> RiskTendency.LOW_RISK
        }

        val disagreementSummary = when {
            totalPhysical == 0 -> {
                val providerNames = distinctProviders.joinToString("、") { it.displayName }
                "已获取 ${distinctProviders.size} 个预报源（${providerNames}），但未获取到独立物理数值模式跑次。"
            }
            rainCount == totalPhysical -> "所有 ${totalPhysical} 个独立物理数值模式一致预测有降雨"
            rainCount == 0 -> "所有 ${totalPhysical} 个独立物理数值模式一致预测无明显降雨"
            else -> {
                val rainNames = rainModels.joinToString("、") { it.displayName.substringBefore(" ") }
                val noRainNames = noRainModels.joinToString("、") { it.displayName.substringBefore(" ") }
                "物理模式分歧：${rainNames} 预测达到降雨事件；${noRainNames} 预测未达降雨标准"
            }
        }

        val freshest = deduped.maxOfOrNull { it.issuedAtEpochMs } ?: 0L
        val oldest = deduped.minOfOrNull { it.issuedAtEpochMs } ?: 0L

        return MultiSourceConsensus(
            evidenceCount = deduped.size,
            independentProviderCount = distinctProviders.size,
            independentPhysicalModelCount = totalPhysical,
            agreeingRainModels = rainModels,
            agreeingNoRainModels = noRainModels,
            contributingProviders = distinctProviders,
            agreementPercentage = agreementRatio * 100.0,
            disagreementSummary = disagreementSummary,
            overallRiskTendency = tendency,
            freshestIssueTimeEpochMs = freshest,
            oldestIssueTimeEpochMs = oldest,
            evidences = deduped
        )
    }
}
