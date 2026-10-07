/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.breezyweather.domain.multisource.hourly.engine.TomorrowHourlyRainEngine
import org.breezyweather.domain.multisource.hourly.model.ElderTimePeriod
import org.breezyweather.domain.multisource.hourly.model.DecisionQualifier
import org.breezyweather.domain.multisource.hourly.model.PeriodRainStatus
import org.breezyweather.domain.multisource.model.ForecastEvidence
import org.breezyweather.domain.multisource.model.PrecipitationProbabilityValue
import org.breezyweather.domain.multisource.model.PrecipitationValue
import org.breezyweather.domain.multisource.model.SourceIdentity
import org.breezyweather.domain.multisource.model.UnderlyingModel
import org.breezyweather.domain.multisource.model.WeatherCondition
import org.breezyweather.domain.multisource.model.WeatherProvider
import org.junit.jupiter.api.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class TomorrowHourlyRainEngineTest {

    private val testLocation = "coord:39.9042,116.4074"
    private val targetDate = "2026-09-19"
    private val timeZoneId = "Asia/Shanghai"

    private fun createHourEvidence(
        model: UnderlyingModel,
        hourOfDay: Int,
        precipMm: Double,
        popPercent: Int? = null,
        conditionText: String = "晴"
    ): ForecastEvidence {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone(timeZoneId)
        }
        val date = sdf.parse("$targetDate ${String.format(Locale.US, "%02d:00", hourOfDay)}")!!
        val validFrom = date.time
        val validTo = validFrom + 3600_000L

        val isRain = precipMm >= 0.1 || conditionText.contains("雨")

        return ForecastEvidence(
            canonicalLocationId = testLocation,
            sourceIdentity = SourceIdentity.resolve(WeatherProvider.OPEN_METEO, model),
            issuedAtEpochMs = 1000L,
            validFromEpochMs = validFrom,
            validToEpochMs = validTo,
            modelRunInitializationEpochMs = null,
            modelRunId = null,
            weatherCondition = WeatherCondition(conditionText, isRainCondition = isRain),
            precipitationProbability = popPercent?.let { PrecipitationProbabilityValue.available(it) }
                ?: PrecipitationProbabilityValue.unavailable(),
            precipitationAmount = PrecipitationValue.available(precipMm),
            precipitationIntensity = PrecipitationValue.available(precipMm),
            probabilityEventDefinition = if (popPercent != null) "PROVIDER_DEFINED_OR_UNKNOWN" else null,
            freshnessSeconds = 0L,
            rawSource = "openmeteo/${model.id}",
            provenanceDetail = model.displayName
        )
    }

    @Test
    fun `test diurnal boundary conditions (00_00, 05_59, 06_00, 11_59, 12_00, 17_59, 18_00, 23_59)`() {
        ElderTimePeriod.forHourAndMinute(0, 0) shouldBe ElderTimePeriod.EARLY_MORNING
        ElderTimePeriod.forHourAndMinute(5, 59) shouldBe ElderTimePeriod.EARLY_MORNING
        ElderTimePeriod.forHourAndMinute(6, 0) shouldBe ElderTimePeriod.MORNING
        ElderTimePeriod.forHourAndMinute(11, 59) shouldBe ElderTimePeriod.MORNING
        ElderTimePeriod.forHourAndMinute(12, 0) shouldBe ElderTimePeriod.AFTERNOON
        ElderTimePeriod.forHourAndMinute(17, 59) shouldBe ElderTimePeriod.AFTERNOON
        ElderTimePeriod.forHourAndMinute(18, 0) shouldBe ElderTimePeriod.NIGHT
        ElderTimePeriod.forHourAndMinute(23, 59) shouldBe ElderTimePeriod.NIGHT
    }

    @Test
    fun `test dry scenario produces clean no-rain projection across all 4 periods`() {
        val evidences = mutableListOf<ForecastEvidence>()
        val models = listOf(UnderlyingModel.ECMWF_IFS, UnderlyingModel.NOAA_GFS, UnderlyingModel.DWD_ICON)

        for (model in models) {
            for (h in 0..23) {
                evidences.add(createHourEvidence(model, h, 0.0, popPercent = 0, conditionText = "晴"))
            }
        }

        val summary = TomorrowHourlyRainEngine.synthesizeTomorrowSummary(
            canonicalLocationId = testLocation,
            targetDateLocal = targetDate,
            timeZoneId = timeZoneId,
            rawEvidences = evidences
        )

        summary.independentPhysicalModelCount shouldBe 3
        summary.dayOccurrenceConsensus.status shouldBe org.breezyweather.domain.multisource.hourly.model.DayOccurrenceStatus.CONSENSUS_NO_RAIN
        summary.dayOccurrenceConsensus.ratioText shouldBe "0/3"
        summary.earlyMorning.rainStatus shouldBe PeriodRainStatus.NO_RAIN
        summary.morning.rainStatus shouldBe PeriodRainStatus.NO_RAIN
        summary.afternoon.rainStatus shouldBe PeriodRainStatus.NO_RAIN
        summary.night.rainStatus shouldBe PeriodRainStatus.NO_RAIN

        summary.morning.allModelAmountRange shouldBe "0.0 mm"
        summary.morning.rainPositiveModelRange shouldBe null
        summary.allModelDailyAmountRange shouldBe "0.0 mm"
        summary.rainPositiveDailyAmountRange shouldBe null
        summary.overallElderConclusion shouldContain "基本无明显降雨"
    }

    @Test
    fun `test day occurrence consensus with 5 models - 5 of 5 produces very likely rain but timing note`() {
        val evidences = mutableListOf<ForecastEvidence>()
        val models = listOf(
            UnderlyingModel.ECMWF_IFS, UnderlyingModel.CMA_GRAPES,
            UnderlyingModel.NOAA_GFS, UnderlyingModel.DWD_ICON, UnderlyingModel.JMA_GSM
        )

        // All 5 models predict rain across the day, but at different periods
        // ECMWF: afternoon (2.0mm)
        evidences.add(createHourEvidence(UnderlyingModel.ECMWF_IFS, 14, 2.0, 75, "中雨"))
        // CMA: night (1.5mm)
        evidences.add(createHourEvidence(UnderlyingModel.CMA_GRAPES, 19, 1.5, null, "中雨"))
        // NOAA: afternoon (1.0mm)
        evidences.add(createHourEvidence(UnderlyingModel.NOAA_GFS, 15, 1.0, 60, "小雨"))
        // DWD: night (0.8mm)
        evidences.add(createHourEvidence(UnderlyingModel.DWD_ICON, 22, 0.8, 50, "小雨"))
        // JMA: morning (1.2mm)
        evidences.add(createHourEvidence(UnderlyingModel.JMA_GSM, 8, 1.2, null, "小雨"))

        // Add 0.0mm for remaining hours
        for (m in models) {
            for (h in 0..23) {
                if (evidences.none { it.sourceIdentity.underlyingModel == m && SimpleDateFormat("HH", Locale.CHINA).apply { timeZone = TimeZone.getTimeZone(timeZoneId) }.format(Date(it.validFromEpochMs)).toInt() == h }) {
                    evidences.add(createHourEvidence(m, h, 0.0, 0, "多云"))
                }
            }
        }

        val summary = TomorrowHourlyRainEngine.synthesizeTomorrowSummary(
            canonicalLocationId = testLocation,
            targetDateLocal = targetDate,
            timeZoneId = timeZoneId,
            rawEvidences = evidences
        )

        summary.independentPhysicalModelCount shouldBe 5
        summary.rainAgreeCount shouldBe 5
        summary.dayOccurrenceConsensus.status shouldBe org.breezyweather.domain.multisource.hourly.model.DayOccurrenceStatus.VERY_LIKELY_RAIN
        summary.dayOccurrenceConsensus.ratioText shouldBe "5/5"
        summary.timingConsensus.hasTimingDisagreement shouldBe true
        summary.overallElderConclusion shouldBe "明天很可能会下雨。多个模型都认为明天有雨，但具体降雨时间仍有分歧。"
    }

    @Test
    fun `test day occurrence consensus with 5 models - 4 of 5 produces majority rain but timing note`() {
        val evidences = mutableListOf<ForecastEvidence>()
        val models = listOf(
            UnderlyingModel.ECMWF_IFS, UnderlyingModel.CMA_GRAPES,
            UnderlyingModel.NOAA_GFS, UnderlyingModel.DWD_ICON, UnderlyingModel.JMA_GSM
        )

        // 4 models predict rain, DWD predicts completely dry
        evidences.add(createHourEvidence(UnderlyingModel.ECMWF_IFS, 14, 1.8, 65, "小雨"))
        evidences.add(createHourEvidence(UnderlyingModel.CMA_GRAPES, 19, 0.5, null, "小雨"))
        evidences.add(createHourEvidence(UnderlyingModel.NOAA_GFS, 10, 2.4, 60, "小雨"))
        evidences.add(createHourEvidence(UnderlyingModel.JMA_GSM, 10, 0.5, null, "小雨"))

        for (m in models) {
            for (h in 0..23) {
                if (evidences.none { it.sourceIdentity.underlyingModel == m && SimpleDateFormat("HH", Locale.CHINA).apply { timeZone = TimeZone.getTimeZone(timeZoneId) }.format(Date(it.validFromEpochMs)).toInt() == h }) {
                    evidences.add(createHourEvidence(m, h, 0.0, 0, "阴"))
                }
            }
        }

        val summary = TomorrowHourlyRainEngine.synthesizeTomorrowSummary(
            canonicalLocationId = testLocation,
            targetDateLocal = targetDate,
            timeZoneId = timeZoneId,
            rawEvidences = evidences
        )

        summary.independentPhysicalModelCount shouldBe 5
        summary.rainAgreeCount shouldBe 4
        summary.dayOccurrenceConsensus.status shouldBe org.breezyweather.domain.multisource.hourly.model.DayOccurrenceStatus.MAJORITY_RAIN
        summary.dayOccurrenceConsensus.ratioText shouldBe "4/5"
        summary.overallElderConclusion shouldBe "大多数预报认为明天会下雨，具体时间仍有分歧。"

        // Afternoon amount range contains 0.0mm from non-rain models
        summary.afternoon.allModelAmountRange shouldBe "0.0~1.8 mm"
        summary.afternoon.rainPositiveModelRange shouldBe "约 1.8 mm"
        summary.afternoon.rainModelRatio shouldBe "1/5"
    }

    @Test
    fun `test day occurrence consensus with 5 models - 1 of 5 produces majority no rain minor divergence`() {
        val evidences = mutableListOf<ForecastEvidence>()
        val models = listOf(
            UnderlyingModel.ECMWF_IFS, UnderlyingModel.CMA_GRAPES,
            UnderlyingModel.NOAA_GFS, UnderlyingModel.DWD_ICON, UnderlyingModel.JMA_GSM
        )

        // Only JMA predicts 0.2mm in the night (23:00), other 4 models are 0.0mm
        evidences.add(createHourEvidence(UnderlyingModel.JMA_GSM, 23, 0.2, null, "阵雨"))

        for (m in models) {
            for (h in 0..23) {
                if (evidences.none { it.sourceIdentity.underlyingModel == m && SimpleDateFormat("HH", Locale.CHINA).apply { timeZone = TimeZone.getTimeZone(timeZoneId) }.format(Date(it.validFromEpochMs)).toInt() == h }) {
                    evidences.add(createHourEvidence(m, h, 0.0, 0, "晴"))
                }
            }
        }

        val summary = TomorrowHourlyRainEngine.synthesizeTomorrowSummary(
            canonicalLocationId = testLocation,
            targetDateLocal = targetDate,
            timeZoneId = timeZoneId,
            rawEvidences = evidences
        )

        summary.independentPhysicalModelCount shouldBe 5
        summary.rainAgreeCount shouldBe 1
        summary.dayOccurrenceConsensus.status shouldBe org.breezyweather.domain.multisource.hourly.model.DayOccurrenceStatus.MAJORITY_NO_RAIN_MINOR_DIVERGENCE
        summary.dayOccurrenceConsensus.ratioText shouldBe "1/5"
        summary.overallElderConclusion shouldBe "大多数预报认为明天基本无雨，但晚上仍有少量分歧。"
    }

    @Test
    fun `test single model high risk demarcation versus majority high risk`() {
        val evidences = mutableListOf<ForecastEvidence>()
        val models = listOf(
            UnderlyingModel.ECMWF_IFS, UnderlyingModel.CMA_GRAPES,
            UnderlyingModel.NOAA_GFS, UnderlyingModel.DWD_ICON, UnderlyingModel.JMA_GSM
        )

        // Single model (ECMWF) predicts 12.0mm, other models predict mild rain (1.0mm)
        evidences.add(createHourEvidence(UnderlyingModel.ECMWF_IFS, 14, 12.0, 85, "暴雨"))
        evidences.add(createHourEvidence(UnderlyingModel.CMA_GRAPES, 14, 1.0, null, "小雨"))
        evidences.add(createHourEvidence(UnderlyingModel.NOAA_GFS, 14, 1.0, 60, "小雨"))
        evidences.add(createHourEvidence(UnderlyingModel.DWD_ICON, 14, 0.5, 40, "小雨"))
        evidences.add(createHourEvidence(UnderlyingModel.JMA_GSM, 14, 1.0, null, "小雨"))

        for (m in models) {
            for (h in 0..23) {
                if (evidences.none { it.sourceIdentity.underlyingModel == m && SimpleDateFormat("HH", Locale.CHINA).apply { timeZone = TimeZone.getTimeZone(timeZoneId) }.format(Date(it.validFromEpochMs)).toInt() == h }) {
                    evidences.add(createHourEvidence(m, h, 0.0, 0, "多云"))
                }
            }
        }

        val summary = TomorrowHourlyRainEngine.synthesizeTomorrowSummary(
            canonicalLocationId = testLocation,
            targetDateLocal = targetDate,
            timeZoneId = timeZoneId,
            rawEvidences = evidences
        )

        // Single model >= 10mm must NOT claim "预计明显强降雨", but "有模型预计雨量可能较大"
        summary.agriculturalRisk.level shouldBe org.breezyweather.domain.multisource.hourly.model.WashoutRiskLevel.ANY_MODEL_HIGH_RISK
        summary.agriculturalRisk.adviceText shouldContain "有模型预计雨量可能较大（ECMWF 累计可达 12.0mm），预报分歧明显"
        summary.overallElderConclusion shouldContain "注：有模型预计雨量可能较大（ECMWF 累计可达 12.0mm），预报分歧明显"
    }

    @Test
    fun `test 0mm model included in allModelAmountRange and separated from rainPositiveModelRange`() {
        val evidences = mutableListOf<ForecastEvidence>()
        val models = listOf(
            UnderlyingModel.ECMWF_IFS, UnderlyingModel.CMA_GRAPES,
            UnderlyingModel.NOAA_GFS, UnderlyingModel.DWD_ICON, UnderlyingModel.JMA_GSM
        )

        // 5 models in afternoon: 0 / 0.6 / 2.1 / 3.5 / 8.4 mm
        evidences.add(createHourEvidence(UnderlyingModel.ECMWF_IFS, 14, 0.0, 0, "多云"))
        evidences.add(createHourEvidence(UnderlyingModel.CMA_GRAPES, 14, 0.6, null, "小雨"))
        evidences.add(createHourEvidence(UnderlyingModel.NOAA_GFS, 14, 2.1, 60, "中雨"))
        evidences.add(createHourEvidence(UnderlyingModel.DWD_ICON, 14, 3.5, 70, "中雨"))
        evidences.add(createHourEvidence(UnderlyingModel.JMA_GSM, 14, 8.4, null, "大雨"))

        for (m in models) {
            for (h in 0..23) {
                if (h != 14) {
                    evidences.add(createHourEvidence(m, h, 0.0, 0, "晴"))
                }
            }
        }

        val summary = TomorrowHourlyRainEngine.synthesizeTomorrowSummary(
            canonicalLocationId = testLocation,
            targetDateLocal = targetDate,
            timeZoneId = timeZoneId,
            rawEvidences = evidences
        )

        val afternoon = summary.afternoon
        afternoon.allModelAmountRange shouldBe "0.0~8.4 mm"
        afternoon.rainPositiveModelRange shouldBe "0.6~8.4 mm"
        afternoon.rainModelRatio shouldBe "4/5"
        afternoon.rainModelCount shouldBe 4
        afternoon.totalModelCount shouldBe 5
    }

    @Test
    fun `test QWeather v1 probability 0_31 normalized to 0_31 canonical ratio and v7 pop 31 to 0_31`() {
        val adapter = org.breezyweather.domain.multisource.hourly.adapter.QWeatherHourlyAdapter()

        // 1. New v1 schema JSON with probability ratio 0.31
        val v1Json = """
            {
              "hours": [
                {
                  "forecastTime": "2026-09-19T14:00+08:00",
                  "condition": { "text": "小雨", "code": "305" },
                  "precipitation": {
                    "amount": { "value": 1.2, "unit": "mm" },
                    "intensity": { "value": 1.2, "unit": "mm/h" },
                    "type": "rain",
                    "probability": 0.31
                  }
                }
              ]
            }
        """.trimIndent()

        val v1Evidences = adapter.parseV1HourlyResponse(
            bodyString = v1Json,
            canonicalLocationId = testLocation,
            targetDateLocal = targetDate,
            timeZoneId = timeZoneId
        )
        v1Evidences.size shouldBe 1
        val v1Pop = v1Evidences[0].precipitationProbability
        v1Pop.percentage shouldBe 31
        v1Pop.canonicalRatio shouldBe 0.31
        v1Pop.canonicalProbabilityRatio shouldBe 0.31

        // 2. Legacy v7 schema JSON with pop 31
        val v7Json = """
            {
              "code": "200",
              "hourly": [
                {
                  "fxTime": "2026-09-19T14:00+08:00",
                  "text": "小雨",
                  "icon": "305",
                  "precip": "1.2",
                  "pop": "31"
                }
              ]
            }
        """.trimIndent()

        val v7Evidences = adapter.parseV7HourlyResponse(
            bodyString = v7Json,
            canonicalLocationId = testLocation,
            targetDateLocal = targetDate,
            timeZoneId = timeZoneId
        )
        v7Evidences.size shouldBe 1
        val v7Pop = v7Evidences[0].precipitationProbability
        v7Pop.percentage shouldBe 31
        v7Pop.canonicalRatio shouldBe 0.31
        v7Pop.canonicalProbabilityRatio shouldBe 0.31
    }

    @Test
    fun `missing hour coverage cannot be presented as a complete dry day`() {
        val evidences = (0..22).map { hour ->
            createHourEvidence(
                model = UnderlyingModel.ECMWF_IFS,
                hourOfDay = hour,
                precipMm = 0.0,
                popPercent = 0,
                conditionText = "晴"
            )
        }

        val summary = TomorrowHourlyRainEngine.synthesizeTomorrowSummary(
            canonicalLocationId = testLocation,
            targetDateLocal = targetDate,
            timeZoneId = timeZoneId,
            rawEvidences = evidences
        )

        summary.dayOccurrenceConsensus.status shouldBe org.breezyweather.domain.multisource.hourly.model.DayOccurrenceStatus.INCONCLUSIVE
        summary.decisionContract?.qualifiers?.contains(DecisionQualifier.PARTIAL_COVERAGE) shouldBe true
        summary.decisionContract?.qualifiers?.contains(DecisionQualifier.INSUFFICIENT_DATA) shouldBe true
        summary.allModelDailyAmountRange shouldBe "时段雨量暂不完整"
    }

    @Test
    fun `duplicate hourly slot is retained as a coverage defect`() {
        val complete = (0..23).map { hour ->
            createHourEvidence(
                model = UnderlyingModel.ECMWF_IFS,
                hourOfDay = hour,
                precipMm = 0.0,
                popPercent = 0,
                conditionText = "晴"
            )
        }.toMutableList()
        val duplicate = complete.first { hourOfDay(it.validFromEpochMs) == 12 }
            .copy(validToEpochMs = complete.first { hourOfDay(it.validFromEpochMs) == 12 }.validFromEpochMs + 2 * 3_600_000L)
        complete += duplicate

        val summary = TomorrowHourlyRainEngine.synthesizeTomorrowSummary(
            canonicalLocationId = testLocation,
            targetDateLocal = targetDate,
            timeZoneId = timeZoneId,
            rawEvidences = complete
        )

        summary.dayOccurrenceConsensus.status shouldBe org.breezyweather.domain.multisource.hourly.model.DayOccurrenceStatus.INCONCLUSIVE
        summary.decisionContract?.coverageBySource?.values?.single()?.duplicateHours shouldBe listOf(12)
        summary.decisionContract?.coverageBySource?.values?.single()?.amountComplete shouldBe false
    }

    @Test
    fun `zero amount with positive probability keeps both facts without claiming dry`() {
        val evidences = (0..23).map { hour ->
            createHourEvidence(
                model = UnderlyingModel.ECMWF_IFS,
                hourOfDay = hour,
                precipMm = 0.0,
                popPercent = 70,
                conditionText = "晴"
            )
        }

        val summary = TomorrowHourlyRainEngine.synthesizeTomorrowSummary(
            canonicalLocationId = testLocation,
            targetDateLocal = targetDate,
            timeZoneId = timeZoneId,
            rawEvidences = evidences
        )

        summary.dayOccurrenceConsensus.status shouldBe org.breezyweather.domain.multisource.hourly.model.DayOccurrenceStatus.PRECIPITATION_SIGNAL
        summary.decisionContract?.positiveAmountSourceIds shouldBe emptyList()
        summary.decisionContract?.positiveProbabilitySourceIds shouldBe listOf("openmeteo:ecmwf_ifs")
        summary.allModelDailyAmountRange shouldBe "0.0 mm"
        summary.overallElderConclusion shouldContain "有降水信号"
    }

    private fun hourOfDay(epochMs: Long): Int = SimpleDateFormat("HH", Locale.CHINA).apply {
        timeZone = TimeZone.getTimeZone(timeZoneId)
    }.format(Date(epochMs)).toInt()
}
