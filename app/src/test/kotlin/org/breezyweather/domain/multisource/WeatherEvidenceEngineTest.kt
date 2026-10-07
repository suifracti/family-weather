/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.breezyweather.domain.multisource.engine.WeatherEvidenceEngine
import org.breezyweather.domain.multisource.model.EvidenceAvailability
import org.breezyweather.domain.multisource.model.ForecastEvidence
import org.breezyweather.domain.multisource.model.PrecipitationProbabilityValue
import org.breezyweather.domain.multisource.model.PrecipitationValue
import org.breezyweather.domain.multisource.model.RainEventDefinition
import org.breezyweather.domain.multisource.model.RiskTendency
import org.breezyweather.domain.multisource.model.SourceIdentity
import org.breezyweather.domain.multisource.model.UnderlyingModel
import org.breezyweather.domain.multisource.model.WeatherCondition
import org.breezyweather.domain.multisource.model.WeatherProvider
import org.junit.jupiter.api.Test

class WeatherEvidenceEngineTest {

    private val testLocation = "coord:39.9042,116.4074"
    private val validFromTime = 1789700000000L
    private val validToTime = 1789786400000L

    @Test
    fun `test deduplication retains freshest report for identical location provider model and valid window`() {
        val older = ForecastEvidence(
            canonicalLocationId = testLocation,
            sourceIdentity = SourceIdentity.resolve(WeatherProvider.OPEN_METEO, UnderlyingModel.ECMWF_IFS),
            issuedAtEpochMs = 1000L,
            validFromEpochMs = validFromTime,
            validToEpochMs = validToTime,
            weatherCondition = WeatherCondition("多云"),
            precipitationProbability = PrecipitationProbabilityValue.available(20),
            precipitationAmount = PrecipitationValue.available(0.0),
            precipitationIntensity = PrecipitationValue.available(0.0),
            probabilityEventDefinition = "precip >= 0.1mm",
            freshnessSeconds = 500L,
            rawSource = "https://api.open-meteo.com/v1/forecast",
            provenanceDetail = "ECMWF run at 00z"
        )
        val newer = older.copy(
            issuedAtEpochMs = 2000L,
            precipitationProbability = PrecipitationProbabilityValue.available(35),
            precipitationAmount = PrecipitationValue.available(0.2),
            provenanceDetail = "ECMWF run at 06z"
        )

        val deduped = WeatherEvidenceEngine.deduplicate(listOf(older, newer))
        deduped.size shouldBe 1
        deduped.first().issuedAtEpochMs shouldBe 2000L
        deduped.first().precipitationProbability.percentage shouldBe 35
    }

    @Test
    fun `test Open-Meteo Best Match does not count as independent physical model when explicit NWP models exist`() {
        val bestMatch = ForecastEvidence(
            canonicalLocationId = testLocation,
            sourceIdentity = SourceIdentity.resolve(WeatherProvider.OPEN_METEO, UnderlyingModel.OPEN_METEO_BEST_MATCH),
            issuedAtEpochMs = 1500L,
            validFromEpochMs = validFromTime,
            validToEpochMs = validToTime,
            weatherCondition = WeatherCondition("小雨", isRainCondition = true),
            precipitationProbability = PrecipitationProbabilityValue.available(60),
            precipitationAmount = PrecipitationValue.available(1.5),
            precipitationIntensity = PrecipitationValue.available(0.5),
            probabilityEventDefinition = "precip >= 0.1mm",
            freshnessSeconds = 300L,
            rawSource = "openmeteo/best_match",
            provenanceDetail = "Open-Meteo Best Match composite"
        )

        val ecmwf = ForecastEvidence(
            canonicalLocationId = testLocation,
            sourceIdentity = SourceIdentity.resolve(WeatherProvider.OPEN_METEO, UnderlyingModel.ECMWF_IFS),
            issuedAtEpochMs = 1500L,
            validFromEpochMs = validFromTime,
            validToEpochMs = validToTime,
            weatherCondition = WeatherCondition("小雨", isRainCondition = true),
            precipitationProbability = PrecipitationProbabilityValue.available(60),
            precipitationAmount = PrecipitationValue.available(1.5),
            precipitationIntensity = PrecipitationValue.available(0.5),
            probabilityEventDefinition = "precip >= 0.1mm",
            freshnessSeconds = 300L,
            rawSource = "openmeteo/ecmwf_ifs",
            provenanceDetail = "ECMWF IFS 0.25 physical run"
        )

        val consensus = WeatherEvidenceEngine.calculateConsensus(listOf(bestMatch, ecmwf))

        // Evidence items = 2, but independent physical models = 1 (ECMWF only)
        consensus.evidenceCount shouldBe 2
        consensus.independentProviderCount shouldBe 1
        consensus.independentPhysicalModelCount shouldBe 1
        consensus.agreeingRainModels shouldBe listOf(UnderlyingModel.ECMWF_IFS)
    }

    @Test
    fun `test QWeather and Breezy China provide provider evidence but do not masquerade as physical models`() {
        val qweather = ForecastEvidence(
            canonicalLocationId = testLocation,
            sourceIdentity = SourceIdentity.resolve(WeatherProvider.QWEATHER, UnderlyingModel.QWEATHER_AGGREGATED),
            issuedAtEpochMs = 1200L,
            validFromEpochMs = validFromTime,
            validToEpochMs = validToTime,
            weatherCondition = WeatherCondition("雷阵雨", isRainCondition = true),
            precipitationProbability = PrecipitationProbabilityValue.available(70),
            precipitationAmount = PrecipitationValue.available(5.0),
            precipitationIntensity = PrecipitationValue.unavailable(),
            probabilityEventDefinition = "native QWeather pop",
            freshnessSeconds = 400L,
            rawSource = "devapi.qweather.com/v7/weather/24h",
            provenanceDetail = "QWeather multi-model composite"
        )

        val china = ForecastEvidence(
            canonicalLocationId = testLocation,
            sourceIdentity = SourceIdentity.resolve(WeatherProvider.BREEZY_CHINA, UnderlyingModel.CAIYUN_REGIONAL),
            issuedAtEpochMs = 1100L,
            validFromEpochMs = validFromTime,
            validToEpochMs = validToTime,
            weatherCondition = WeatherCondition("阴"),
            precipitationProbability = PrecipitationProbabilityValue.failClosedSuppressed("1"),
            precipitationAmount = PrecipitationValue.unavailable(), // Regular daily/hourly does not provide mm
            precipitationIntensity = PrecipitationValue.unavailable(),
            probabilityEventDefinition = null,
            freshnessSeconds = 500L,
            rawSource = "weatherbj.caiyunapp.com",
            provenanceDetail = "Caiyun regional assimilation"
        )

        val consensus = WeatherEvidenceEngine.calculateConsensus(listOf(qweather, china))

        // Distinct providers = 2
        consensus.independentProviderCount shouldBe 2
        // Independent physical NWP models = 0 (both are opaque/composite)
        consensus.independentPhysicalModelCount shouldBe 0
    }

    @Test
    fun `test missing fields fail-closed and throw on artificial zero padding`() {
        val cmaEvidence = ForecastEvidence(
            canonicalLocationId = testLocation,
            sourceIdentity = SourceIdentity.resolve(WeatherProvider.OPEN_METEO, UnderlyingModel.CMA_GRAPES),
            issuedAtEpochMs = 1000L,
            validFromEpochMs = validFromTime,
            validToEpochMs = validToTime,
            weatherCondition = WeatherCondition("多云"),
            precipitationProbability = PrecipitationProbabilityValue.unavailable(),
            precipitationAmount = PrecipitationValue.available(0.0),
            precipitationIntensity = PrecipitationValue.available(0.0),
            probabilityEventDefinition = null,
            freshnessSeconds = 600L,
            rawSource = "api.open-meteo.com/v1/forecast?models=cma_grapes_global",
            provenanceDetail = "CMA GRAPES deterministic"
        )

        cmaEvidence.precipitationProbability.availability shouldBe EvidenceAvailability.UNAVAILABLE
        cmaEvidence.precipitationProbability.percentage shouldBe null

        var threwAvailableWithNull = false
        try {
            PrecipitationValue(null, EvidenceAvailability.AVAILABLE)
        } catch (e: IllegalArgumentException) {
            threwAvailableWithNull = true
        }
        threwAvailableWithNull shouldBe true

        var threwUnavailableWithZero = false
        try {
            PrecipitationValue(0.0, EvidenceAvailability.UNAVAILABLE)
        } catch (e: IllegalArgumentException) {
            threwUnavailableWithZero = true
        }
        threwUnavailableWithZero shouldBe true
    }

    @Test
    fun `test multi-model consensus and explicit physical disagreement breakdown`() {
        val ecmwf = ForecastEvidence(
            canonicalLocationId = testLocation,
            sourceIdentity = SourceIdentity.resolve(WeatherProvider.OPEN_METEO, UnderlyingModel.ECMWF_IFS),
            issuedAtEpochMs = 1000L,
            validFromEpochMs = validFromTime,
            validToEpochMs = validToTime,
            weatherCondition = WeatherCondition("中雨", isRainCondition = true),
            precipitationProbability = PrecipitationProbabilityValue.available(75),
            precipitationAmount = PrecipitationValue.available(4.0),
            precipitationIntensity = PrecipitationValue.available(1.2),
            probabilityEventDefinition = "precip >= 0.1mm",
            freshnessSeconds = 300L,
            rawSource = "openmeteo",
            provenanceDetail = "ECMWF"
        )
        val gfs = ForecastEvidence(
            canonicalLocationId = testLocation,
            sourceIdentity = SourceIdentity.resolve(WeatherProvider.OPEN_METEO, UnderlyingModel.NOAA_GFS),
            issuedAtEpochMs = 1000L,
            validFromEpochMs = validFromTime,
            validToEpochMs = validToTime,
            weatherCondition = WeatherCondition("小雨", isRainCondition = true),
            precipitationProbability = PrecipitationProbabilityValue.available(60),
            precipitationAmount = PrecipitationValue.available(2.0),
            precipitationIntensity = PrecipitationValue.available(0.6),
            probabilityEventDefinition = "precip >= 0.1mm",
            freshnessSeconds = 300L,
            rawSource = "openmeteo",
            provenanceDetail = "GFS"
        )
        val icon = ForecastEvidence(
            canonicalLocationId = testLocation,
            sourceIdentity = SourceIdentity.resolve(WeatherProvider.OPEN_METEO, UnderlyingModel.DWD_ICON),
            issuedAtEpochMs = 1000L,
            validFromEpochMs = validFromTime,
            validToEpochMs = validToTime,
            weatherCondition = WeatherCondition("阴"),
            precipitationProbability = PrecipitationProbabilityValue.available(15),
            precipitationAmount = PrecipitationValue.available(0.0),
            precipitationIntensity = PrecipitationValue.available(0.0),
            probabilityEventDefinition = "precip >= 0.1mm",
            freshnessSeconds = 300L,
            rawSource = "openmeteo",
            provenanceDetail = "ICON"
        )
        val jma = ForecastEvidence(
            canonicalLocationId = testLocation,
            sourceIdentity = SourceIdentity.resolve(WeatherProvider.OPEN_METEO, UnderlyingModel.JMA_GSM),
            issuedAtEpochMs = 1000L,
            validFromEpochMs = validFromTime,
            validToEpochMs = validToTime,
            weatherCondition = WeatherCondition("多云"),
            precipitationProbability = PrecipitationProbabilityValue.unavailable(),
            precipitationAmount = PrecipitationValue.available(0.0),
            precipitationIntensity = PrecipitationValue.available(0.0),
            probabilityEventDefinition = null,
            freshnessSeconds = 300L,
            rawSource = "openmeteo",
            provenanceDetail = "JMA"
        )

        val consensus = WeatherEvidenceEngine.calculateConsensus(listOf(ecmwf, gfs, icon, jma))

        consensus.independentPhysicalModelCount shouldBe 4
        consensus.agreeingRainModels.size shouldBe 2
        consensus.agreeingNoRainModels.size shouldBe 2
        consensus.agreementPercentage shouldBe 50.0
        consensus.overallRiskTendency shouldBe RiskTendency.MODERATE_RISK

        consensus.disagreementSummary shouldBe "物理模式分歧：ECMWF、NOAA 预测达到降雨事件；DWD、JMA 预测未达降雨标准"
    }
}
