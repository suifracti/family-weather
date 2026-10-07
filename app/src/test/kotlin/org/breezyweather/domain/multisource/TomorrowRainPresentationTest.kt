/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldNotBe
import org.breezyweather.domain.multisource.hourly.presentation.SourceFetchState
import org.breezyweather.domain.multisource.hourly.presentation.SourceFetchStatus
import org.breezyweather.domain.multisource.hourly.presentation.TomorrowRainPresentation
import org.breezyweather.domain.multisource.location.LocationAuthority
import org.breezyweather.domain.multisource.location.WeatherCoordinateSerializer
import org.breezyweather.domain.multisource.model.ForecastEvidence
import org.breezyweather.domain.multisource.model.PrecipitationProbabilityValue
import org.breezyweather.domain.multisource.model.PrecipitationValue
import org.breezyweather.domain.multisource.model.SourceIdentity
import org.breezyweather.domain.multisource.model.UnderlyingModel
import org.breezyweather.domain.multisource.model.WeatherCondition
import org.breezyweather.domain.multisource.model.WeatherProvider
import org.junit.jupiter.api.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class TomorrowRainPresentationTest {

    private val target = LocationAuthority.WORKSITE
    private val targetDate = "2026-09-20"
    private val timeZoneId = "Asia/Shanghai"
    private val referenceNow = localEpoch("2026-09-19 12:00")

    @Test
    fun `hourly rows keep every remaining today hour and all tomorrow hours`() {
        val todayNow = localEpoch("2026-09-20 18:26")
        val today = TomorrowRainPresentation.buildSnapshot(
            targetLocation = target,
            targetDateLocal = targetDate,
            rawEvidences = listOf(evidence(WeatherProvider.QWEATHER, UnderlyingModel.QWEATHER_AGGREGATED, 19, 40, 0.2)),
            sourceStatuses = emptyList(),
            nowEpochMs = todayNow,
        )
        TomorrowRainPresentation.hourlyDisplayRows(today, todayNow).map { it.hour } shouldBe (18..23).toList()

        val tomorrowRows = TomorrowRainPresentation.hourlyDisplayRows(
            buildSnapshotFor(listOf(evidence(WeatherProvider.QWEATHER, UnderlyingModel.QWEATHER_AGGREGATED, 19, 40, 0.2))),
            referenceNow,
        )
        tomorrowRows.map { it.hour } shouldBe (0..23).toList()
        tomorrowRows.count { it.forecasts.isEmpty() } shouldBe 23
    }

    @Test
    fun `hourly rows keep missing probability missing and align amount to the same hour`() {
        val snapshot = buildSnapshotFor(
            listOf(
                evidence(WeatherProvider.QWEATHER, UnderlyingModel.QWEATHER_AGGREGATED, 18, null, 0.4),
                evidence(WeatherProvider.QWEATHER, UnderlyingModel.QWEATHER_AGGREGATED, 19, 70, 1.2),
            )
        )
        val rows = TomorrowRainPresentation.hourlyDisplayRows(snapshot, referenceNow)
        rows.single { it.hour == 18 }.forecasts.single().apply {
            probabilityPercent shouldBe null
            amountMm shouldBe 0.4
        }
        rows.single { it.hour == 19 }.forecasts.single().apply {
            probabilityPercent shouldBe 70
            amountMm shouldBe 1.2
        }
        TomorrowRainPresentation.hourIntervalLabel(0) shouldBe "凌晨0–1点"
        TomorrowRainPresentation.hourIntervalLabel(18) shouldBe "下午6–7点"
        TomorrowRainPresentation.hourIntervalLabel(19) shouldBe "晚上7–8点"
    }

    @Test
    fun `homepage probability rows hide missing values while counted evidence remains available`() {
        val snapshot = buildSnapshotFor(
            listOf(
                evidence(WeatherProvider.QWEATHER, UnderlyingModel.QWEATHER_AGGREGATED, 18, 35, 0.0),
                evidence(
                    WeatherProvider.OPEN_METEO,
                    UnderlyingModel.ECMWF_IFS,
                    18,
                    null,
                    0.6,
                    weatherText = "小雨",
                ),
                evidence(
                    WeatherProvider.OPEN_METEO,
                    UnderlyingModel.NOAA_GFS,
                    19,
                    null,
                    0.4,
                    weatherText = "小雨",
                ),
            )
        )

        val rows = TomorrowRainPresentation.hourlyDisplayRows(snapshot, referenceNow)
        val mixed = rows.single { it.hour == 18 }
        mixed.forecasts.map { it.probabilityPercent } shouldBe listOf(35, null)
        mixed.forecastsWithProbability.map { it.probabilityPercent } shouldBe listOf(35)
        mixed.forecastsWithProbability.single().displayName shouldBe "和风天气"
        mixed.forecasts.single { it.probabilityPercent == null }.apply {
            amountMm shouldBe 0.6
            weatherDescription shouldBe "小雨"
        }
        mixed.summary.totalForecastCount shouldBe 2

        val allMissing = rows.single { it.hour == 19 }
        allMissing.forecasts.size shouldBe 1
        allMissing.forecastsWithProbability shouldBe emptyList()
        allMissing.forecasts.single().amountMm shouldBe 0.4
        allMissing.summary.totalForecastCount shouldBe 1

        val noHourlyRecord = rows.single { it.hour == 20 }
        noHourlyRecord.forecastsWithProbability shouldBe emptyList()
        noHourlyRecord.forecasts shouldBe emptyList()
    }

    @Test
    fun `hourly rows prioritize QWeather and show each forecast same-hour condition`() {
        val snapshot = buildSnapshotFor(
            listOf(
                evidence(
                    WeatherProvider.OPEN_METEO,
                    UnderlyingModel.CMA_GRAPES,
                    18,
                    null,
                    0.6,
                    weatherText = "小雨",
                ),
                evidence(
                    WeatherProvider.QWEATHER,
                    UnderlyingModel.QWEATHER_AGGREGATED,
                    18,
                    35,
                    0.0,
                    weatherText = "多云",
                ),
            )
        )

        val forecasts = TomorrowRainPresentation.hourlyDisplayRows(snapshot, referenceNow)
            .single { it.hour == 18 }
            .forecasts

        forecasts.map { it.displayName } shouldBe listOf("和风天气", "中国气象局 GRAPES")
        forecasts.map { it.weatherDescription } shouldBe listOf("多云", "小雨")
        forecasts.map { it.amountMm } shouldBe listOf(0.0, 0.6)
        forecasts.map { it.probabilityPercent } shouldBe listOf(35, null)
    }

    @Test
    fun `hourly rows recalculate the current hour when the page returns later`() {
        val firstNow = localEpoch("2026-09-20 18:05")
        val laterNow = localEpoch("2026-09-20 19:05")
        val snapshot = TomorrowRainPresentation.buildSnapshot(
            targetLocation = target,
            targetDateLocal = targetDate,
            rawEvidences = (18..23).map { hour ->
                evidence(WeatherProvider.QWEATHER, UnderlyingModel.QWEATHER_AGGREGATED, hour, 30, 0.1)
            },
            sourceStatuses = emptyList(),
            nowEpochMs = firstNow,
        )
        TomorrowRainPresentation.hourlyDisplayRows(snapshot, firstNow).first().hour shouldBe 18
        TomorrowRainPresentation.hourlyDisplayRows(snapshot, laterNow).first().hour shouldBe 19
    }

    @Test
    fun `slot uses maximum valid hourly probability and complete same-source amount sum`() {
        val evidences = listOf(
            evidence(WeatherProvider.QWEATHER, UnderlyingModel.QWEATHER_AGGREGATED, 12, 30, 0.5),
            evidence(WeatherProvider.QWEATHER, UnderlyingModel.QWEATHER_AGGREGATED, 13, 55, 0.9),
            evidence(WeatherProvider.QWEATHER, UnderlyingModel.QWEATHER_AGGREGATED, 14, null, 1.0),
            // A different date must not leak into the selected slot.
            evidence(WeatherProvider.QWEATHER, UnderlyingModel.QWEATHER_AGGREGATED, 14, 99, 9.0, "2026-09-19")
        )

        val snapshot = TomorrowRainPresentation.buildSnapshot(
            targetLocation = target,
            targetDateLocal = targetDate,
            rawEvidences = evidences,
            sourceStatuses = listOf(
                SourceFetchStatus("qweather", "和风天气", SourceFetchState.SUCCESS, evidences.size)
            ),
            nowEpochMs = referenceNow,
        )

        val metric = snapshot.slots[4].sourceMetrics.single()
        metric.maxProbabilityPercent shouldBe 55
        metric.maxProbabilityHour shouldBe 13
        metric.totalAmountMm shouldBe 2.4
        metric.amountComplete shouldBe true
        snapshot.headline shouldBe "明天有雨预报时段"
    }

    @Test
    fun `early morning rain is included in the whole day conclusion`() {
        val snapshot = buildSnapshotFor(
            (0..8).map { hour ->
                evidence(
                    provider = WeatherProvider.QWEATHER,
                    model = UnderlyingModel.QWEATHER_AGGREGATED,
                    hour = hour,
                    probability = if (hour < 6) 80 else 15,
                    amount = if (hour < 6) 1.0 else 0.0,
                )
            }
        )

        snapshot.headline shouldBe "明天有雨预报时段"
        snapshot.headline shouldNotBe "明天降雨数据暂不可用"
    }

    @Test
    fun `rain only from six to nine is not dropped because it is before visible slots`() {
        val snapshot = buildSnapshotFor(
            (6..8).map { hour ->
                evidence(
                    provider = WeatherProvider.QWEATHER,
                    model = UnderlyingModel.QWEATHER_AGGREGATED,
                    hour = hour,
                    probability = 70,
                    amount = 0.4,
                )
            }
        )

        snapshot.headline shouldBe "明天有雨预报时段"
    }

    @Test
    fun `complete middle probability data has a conclusion instead of no data`() {
        val snapshot = buildSnapshotFor(
            (0..23).map { hour ->
                evidence(
                    provider = WeatherProvider.QWEATHER,
                    model = UnderlyingModel.QWEATHER_AGGREGATED,
                    hour = hour,
                    probability = if (hour % 2 == 0) 35 else 45,
                    amount = 0.0,
                )
            }
        )

        snapshot.headline shouldBe "明天预报资料不足，暂不能判断有无明显降水"
        snapshot.headline shouldNotBe "明天降雨数据暂不可用"
    }

    @Test
    fun `incomplete whole day low probability remains qualified rather than unavailable`() {
        val snapshot = buildSnapshotFor(
            (15..17).map { hour ->
                evidence(
                    provider = WeatherProvider.QWEATHER,
                    model = UnderlyingModel.QWEATHER_AGGREGATED,
                    hour = hour,
                    probability = 10,
                    amount = 0.0,
                )
            }
        )

        snapshot.headline shouldBe "明天预报资料不足，暂不能判断有无明显降水"
        snapshot.headline shouldNotBe "明天降雨数据暂不可用"
    }

    @Test
    fun `incomplete hourly coverage never becomes a complete amount`() {
        val evidences = listOf(
            evidence(WeatherProvider.OPEN_METEO, UnderlyingModel.ECMWF_IFS, 13, 45, 0.5),
            evidence(WeatherProvider.OPEN_METEO, UnderlyingModel.ECMWF_IFS, 14, 40, 0.6)
        )

        val snapshot = TomorrowRainPresentation.buildSnapshot(
            targetLocation = target,
            targetDateLocal = targetDate,
            rawEvidences = evidences,
            sourceStatuses = listOf(
                SourceFetchStatus("openmeteo", "Open-Meteo", SourceFetchState.SUCCESS, evidences.size)
            ),
            nowEpochMs = referenceNow,
        )

        val metric = snapshot.slots[4].sourceMetrics.single()
        metric.maxProbabilityPercent shouldBe 45
        metric.maxProbabilityHour shouldBe 13
        metric.availableAmountHourCount shouldBe 2
        metric.expectedHourCount shouldBe 3
        metric.totalAmountMm shouldBe null
        metric.amountComplete shouldBe false
    }

    @Test
    fun `location and target date filtering prevents county or old data from becoming orchard data`() {
        val countyId = LocationAuthority.COUNTY_TOWN.canonicalLocationId
        val countyEvidence = evidence(
            provider = WeatherProvider.QWEATHER,
            model = UnderlyingModel.QWEATHER_AGGREGATED,
            hour = 15,
            probability = 100,
            amount = 10.0,
            locationId = countyId
        )
        val oldWorksiteEvidence = evidence(
            provider = WeatherProvider.QWEATHER,
            model = UnderlyingModel.QWEATHER_AGGREGATED,
            hour = 15,
            probability = 100,
            amount = 10.0,
            date = "2026-09-19"
        )

        val snapshot = TomorrowRainPresentation.buildSnapshot(
            targetLocation = target,
            targetDateLocal = targetDate,
            rawEvidences = listOf(countyEvidence, oldWorksiteEvidence),
            sourceStatuses = emptyList(),
            nowEpochMs = referenceNow,
        )

        snapshot.evidences shouldBe emptyList()
        snapshot.primarySourceId shouldBe null
        snapshot.headline shouldContain "暂无有效天气数据"
    }

    @Test
    fun `provider failure status is kept separate from unavailable field values`() {
        val snapshot = TomorrowRainPresentation.buildSnapshot(
            targetLocation = target,
            targetDateLocal = targetDate,
            rawEvidences = listOf(
                evidence(WeatherProvider.OPEN_METEO, UnderlyingModel.ECMWF_IFS, 15, null, 1.2)
            ),
            sourceStatuses = listOf(
                SourceFetchStatus("qweather", "和风天气", SourceFetchState.FAILED),
                SourceFetchStatus("openmeteo", "Open-Meteo", SourceFetchState.SUCCESS, 1)
            ),
            nowEpochMs = referenceNow,
        )

        snapshot.sourceStatuses.first { it.sourceId == "qweather" }.state shouldBe SourceFetchState.FAILED
        snapshot.slots[5].sourceMetrics.single().maxProbabilityPercent shouldBe null
        snapshot.slots[5].sourceMetrics.single().totalAmountMm shouldBe null
        snapshot.slots[5].sourceMetrics.single().hasEvidence shouldBe true
    }

    @Test
    fun `today and tomorrow snapshots keep target dates separate and remove ended today forecasts`() {
        val todayDate = "2026-09-23"
        val tomorrowDate = "2026-09-24"
        val now = localEpoch("2026-09-23 14:30")
        val evidences = listOf(
            evidence(WeatherProvider.OPEN_METEO, UnderlyingModel.ECMWF_IFS, 13, 0, 0.8, todayDate),
            evidence(WeatherProvider.OPEN_METEO, UnderlyingModel.ECMWF_IFS, 14, 0, 0.4, todayDate),
            evidence(WeatherProvider.OPEN_METEO, UnderlyingModel.ECMWF_IFS, 0, 0, 0.0, tomorrowDate),
            evidence(WeatherProvider.OPEN_METEO, UnderlyingModel.ECMWF_IFS, 1, 0, 0.0, tomorrowDate),
        )

        val today = TomorrowRainPresentation.buildSnapshot(
            targetLocation = target,
            targetDateLocal = todayDate,
            rawEvidences = evidences,
            sourceStatuses = emptyList(),
            nowEpochMs = now,
        )
        today.isToday shouldBe true
        today.analysisStartEpochMs shouldBe now
        today.slots.first().definition.timeRange shouldBe "14:00–15:00"
        today.evidences.map { localHour(it.validFromEpochMs) } shouldBe listOf(14)

        val tomorrow = TomorrowRainPresentation.buildSnapshot(
            targetLocation = target,
            targetDateLocal = tomorrowDate,
            rawEvidences = evidences,
            sourceStatuses = emptyList(),
            nowEpochMs = now,
        )
        tomorrow.isToday shouldBe false
        tomorrow.analysisStartEpochMs shouldBe null
        tomorrow.slots.first().definition.timeRange shouldBe "00:00–03:00"
        tomorrow.evidences.map { localHour(it.validFromEpochMs) } shouldBe listOf(0, 1)
    }

    @Test
    fun `different physical models retain separate rain windows and millimeter totals`() {
        val evidences = (0..23).flatMap { hour ->
            val ecmwfAmount = when (hour) {
                14 -> 1.2
                15 -> 0.5
                else -> 0.0
            }
            val gfsAmount = when (hour) {
                16 -> 2.4
                17 -> 0.8
                else -> 0.0
            }
            listOf(
                evidence(WeatherProvider.OPEN_METEO, UnderlyingModel.ECMWF_IFS, hour, 0, ecmwfAmount),
                evidence(WeatherProvider.OPEN_METEO, UnderlyingModel.NOAA_GFS, hour, 0, gfsAmount),
            )
        }
        val snapshot = buildSnapshotFor(evidences)
        val ecmwf = snapshot.allDayForecastResults.single { it.forecastKey == "model:ecmwf_ifs" }
        val gfs = snapshot.allDayForecastResults.single { it.forecastKey == "model:gfs_global" }

        ecmwf.rainWindows.map { formatWindow(it.startEpochMs, it.endEpochMs) } shouldBe listOf("14:00–16:00")
        gfs.rainWindows.map { formatWindow(it.startEpochMs, it.endEpochMs) } shouldBe listOf("16:00–18:00")
        ecmwf.totalAmountMm shouldBe 1.7
        gfs.totalAmountMm shouldBe 3.2
        snapshot.reliableNoObviousPrecipitationWindows
            .map { it.startEpochMs to it.endEpochMs } shouldBe listOf(
                localEpoch("2026-09-20 00:00") to localEpoch("2026-09-20 14:00"),
                localEpoch("2026-09-20 18:00") to localEpoch("2026-09-21 00:00"),
            )
        snapshot.slots[4].reliableNoObviousPrecipitationWindows
            .map { it.startEpochMs to it.endEpochMs } shouldBe listOf(
                localEpoch("2026-09-20 12:00") to localEpoch("2026-09-20 14:00"),
            )
        val mixedSlotSummary = TomorrowRainPresentation.summarizeSlot(snapshot.slots[4])
        mixedSlotSummary.state shouldBe org.breezyweather.domain.multisource.hourly.presentation.RainSlotDisplayState.MIXED_RAIN_AND_NO_OBVIOUS
        mixedSlotSummary.hasCompleteNoRainForecast shouldBe true
    }

    @Test
    fun `slot counts mixed outcomes once and groups identical rain windows`() {
        val explicitModels = (12..14).flatMap { hour ->
            listOf(
                evidence(WeatherProvider.OPEN_METEO, UnderlyingModel.ECMWF_IFS, hour, 0, 0.1),
                evidence(WeatherProvider.OPEN_METEO, UnderlyingModel.CMA_GRAPES, hour, 0, 0.0),
            )
        }
        val otherForecasts = listOf(
            // This partial forecast reports rain for 12–13 but has no later hours.
            evidence(WeatherProvider.OPEN_METEO, UnderlyingModel.NOAA_GFS, 12, 0, 0.2),
            // A platform forecast with unknown precipitation evidence is insufficient, not a dry vote.
            evidence(WeatherProvider.QWEATHER, UnderlyingModel.QWEATHER_AGGREGATED, 12, null, null),
        )
        val bestMatch: (UnderlyingModel?) -> List<ForecastEvidence> = { resolved ->
            (12..14).map { hour ->
                evidence(
                    WeatherProvider.OPEN_METEO,
                    UnderlyingModel.OPEN_METEO_BEST_MATCH,
                    hour,
                    0,
                    0.1,
                    resolvedPhysicalModel = resolved,
                )
            }
        }
        val slot = buildSnapshotFor(explicitModels + otherForecasts + bestMatch(null)).slots[4]

        val summary = TomorrowRainPresentation.summarizeSlot(slot)

        summary.state shouldBe org.breezyweather.domain.multisource.hourly.presentation.RainSlotDisplayState.MIXED_RAIN_AND_NO_OBVIOUS
        summary.totalForecastCount shouldBe 4
        summary.rainForecastCount shouldBe 2
        summary.noObviousPrecipitationForecastCount shouldBe 1
        summary.insufficientForecastCount shouldBe 1
        summary.rainForecastCount + summary.noObviousPrecipitationForecastCount + summary.insufficientForecastCount shouldBe summary.totalForecastCount
        summary.partialRainForecastCount shouldBe 1
        summary.omittedOverlappingBestMatchForecasts.map { it.modelName } shouldBe listOf(UnderlyingModel.OPEN_METEO_BEST_MATCH.displayName)
        summary.omittedDuplicateForecasts shouldBe emptyList()
        summary.rainWindowPatternCounts.map { group ->
            group.windows.map { it.startEpochMs to it.endEpochMs } to group.forecastCount
        } shouldBe listOf(
            listOf(localEpoch("2026-09-20 12:00") to localEpoch("2026-09-20 15:00")) to 1,
            listOf(localEpoch("2026-09-20 12:00") to localEpoch("2026-09-20 13:00")) to 1,
        )
        summary.minCompleteAmountMm shouldBe 0.0
        summary.maxCompleteAmountMm shouldBe 0.1 + 0.1 + 0.1
        summary.hasCompleteNoRainForecast shouldBe true
        summary.hasIncompleteForecasts shouldBe true

        val knownDuplicateSummary = TomorrowRainPresentation.summarizeSlot(
            buildSnapshotFor(explicitModels + otherForecasts + bestMatch(UnderlyingModel.ECMWF_IFS)).slots[4]
        )
        knownDuplicateSummary.totalForecastCount shouldBe 4
        knownDuplicateSummary.omittedDuplicateForecasts.map { it.modelName } shouldBe listOf(UnderlyingModel.OPEN_METEO_BEST_MATCH.displayName)
    }

    @Test
    fun `probability display keeps comparable ranges and single values while preserving missing hours`() {
        val knownEvent = "1小时累计降水 >= 0.1mm 成员占比"
        val evidence = listOf(
            evidence(WeatherProvider.OPEN_METEO, UnderlyingModel.ECMWF_IFS, 12, 20, 0.2, probabilityEventDefinition = knownEvent),
            evidence(WeatherProvider.OPEN_METEO, UnderlyingModel.NOAA_GFS, 12, 60, 0.2, probabilityEventDefinition = knownEvent),
            // A single value at 13:00 remains an exact source value; missing values do not become 0%.
            evidence(WeatherProvider.OPEN_METEO, UnderlyingModel.NOAA_GFS, 13, 30, null, probabilityEventDefinition = knownEvent),
            // Provider-defined probability is kept separate from the numerical model event scope.
            evidence(
                WeatherProvider.QWEATHER,
                UnderlyingModel.QWEATHER_AGGREGATED,
                12,
                40,
                0.0,
                probabilityEventDefinition = "PROVIDER_DEFINED_OR_UNKNOWN",
            ),
            // Best Match overlaps the known ECMWF forecast and must not add a 99% vote.
            evidence(
                WeatherProvider.OPEN_METEO,
                UnderlyingModel.OPEN_METEO_BEST_MATCH,
                12,
                99,
                0.2,
                resolvedPhysicalModel = UnderlyingModel.ECMWF_IFS,
                probabilityEventDefinition = knownEvent,
            ),
        )
        val slot = buildSnapshotFor(evidence).slots[4]

        val display = TomorrowRainPresentation.probabilityDisplay(slot, timeZoneId)
        val noon = display.hourly[0]
        val comparableNoon = noon.groups.single { !it.sourceSpecificScope }
        comparableNoon.minPercent shouldBe 20
        comparableNoon.maxPercent shouldBe 60
        comparableNoon.readings.map { it.percentage }.toSet() shouldBe setOf(20, 60)
        noon.groups.single { it.sourceSpecificScope }.readings.single().percentage shouldBe 40
        noon.groups.flatMap { it.readings }.any { it.percentage == 99 } shouldBe false

        val oneValueHour = display.hourly[1]
        oneValueHour.groups.single().readings.single().percentage shouldBe 30
        oneValueHour.forecastsWithoutProbabilityCount shouldBe 2
        val missingHour = display.hourly[2]
        missingHour.groups shouldBe emptyList()
        missingHour.forecastsWithoutProbabilityCount shouldBe 3

        display.incompleteForecastCount shouldBe 3
        display.maximumByForecastGroups.single { !it.sourceSpecificScope }.readings
            .map { it.percentage }.toSet() shouldBe setOf(20, 60)
        val rainPattern = TomorrowRainPresentation.summarizeSlot(slot).rainWindowPatternCounts.single()
        rainPattern.incompleteCoverageForecastCount shouldBe 2
        rainPattern.windows.single().endEpochMs shouldBe localEpoch("2026-09-20 13:00")
        TomorrowRainPresentation.summarizeSlot(slot).omittedDuplicateForecasts.size shouldBe 1
    }

    @Test
    fun `missing amount breaks shared dry window and a lone forecast cannot make one`() {
        val twoModelEvidence = (0..23).flatMap { hour ->
            listOf(
                evidence(WeatherProvider.OPEN_METEO, UnderlyingModel.ECMWF_IFS, hour, 0, 0.0),
                evidence(
                    WeatherProvider.OPEN_METEO,
                    UnderlyingModel.NOAA_GFS,
                    hour,
                    0,
                    if (hour == 13) null else 0.0,
                ),
            )
        }
        val twoModelSnapshot = buildSnapshotFor(twoModelEvidence)
        val gfsHour = twoModelSnapshot.summary.hourlyForecastAssessments.single {
            it.forecastKey == "model:gfs_global" && localHour(it.validFromEpochMs) == 13
        }
        gfsHour.result shouldBe org.breezyweather.domain.multisource.hourly.model.HourlyForecastResult.INSUFFICIENT_DATA
        twoModelSnapshot.reliableNoObviousPrecipitationWindows.any {
            it.startEpochMs <= localEpoch("2026-09-20 13:00") &&
                it.endEpochMs > localEpoch("2026-09-20 13:00")
        } shouldBe false
        twoModelSnapshot.slots[4].reliableNoObviousPrecipitationWindows.any {
            it.startEpochMs <= localEpoch("2026-09-20 13:00") &&
                it.endEpochMs > localEpoch("2026-09-20 13:00")
        } shouldBe false
        val incompleteSlotSummary = TomorrowRainPresentation.summarizeSlot(twoModelSnapshot.slots[4])
        incompleteSlotSummary.state shouldBe org.breezyweather.domain.multisource.hourly.presentation.RainSlotDisplayState.NO_OBVIOUS_PRECIPITATION
        incompleteSlotSummary.hasCompleteNoRainForecast shouldBe true
        incompleteSlotSummary.hasIncompleteForecasts shouldBe true

        val singleSource = (0..23).map { hour ->
            evidence(WeatherProvider.QWEATHER, UnderlyingModel.QWEATHER_AGGREGATED, hour, 0, 0.0)
        }
        val singleSnapshot = buildSnapshotFor(singleSource)
        singleSnapshot.allDayForecastResults.size shouldBe 1
        singleSnapshot.reliableNoObviousPrecipitationWindows shouldBe emptyList()
    }

    @Test
    fun `tomorrow date follows target timezone`() {
        val utc = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val justAfterMidnightShanghai = utc.parse("2026-09-19 16:30")!!.time

        TomorrowRainPresentation.tomorrowDateLocal(justAfterMidnightShanghai, timeZoneId) shouldBe "2026-09-21"
    }

    private fun evidence(
        provider: WeatherProvider,
        model: UnderlyingModel,
        hour: Int,
        probability: Int?,
        amount: Double?,
        date: String = targetDate,
        locationId: String = target.canonicalLocationId,
        resolvedPhysicalModel: UnderlyingModel? = null,
        probabilityEventDefinition: String? = if (probability != null) "TEST" else null,
        weatherText: String? = if (amount != null && amount >= 0.1) "小雨" else "晴",
    ): ForecastEvidence {
        val parser = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone(timeZoneId)
        }
        val dateTime = parser.parse("$date ${String.format(Locale.US, "%02d:00", hour)}")!!.time
        val probabilityValue = probability?.let { PrecipitationProbabilityValue.available(it) }
            ?: PrecipitationProbabilityValue.unavailable()
        val amountValue = amount?.let { PrecipitationValue.available(it) } ?: PrecipitationValue.unavailable()
        return ForecastEvidence(
            canonicalLocationId = locationId,
            sourceIdentity = SourceIdentity.resolve(provider, model, resolvedPhysicalModel),
            issuedAtEpochMs = 1_000L,
            validFromEpochMs = dateTime,
            validToEpochMs = dateTime + 3_600_000L,
            weatherCondition = weatherText?.let {
                WeatherCondition(it, isRainCondition = amount != null && amount >= 0.1)
            },
            precipitationProbability = probabilityValue,
            precipitationAmount = amountValue,
            precipitationIntensity = amountValue,
            probabilityEventDefinition = probabilityEventDefinition,
            freshnessSeconds = 0L,
            rawSource = "test://forecast",
            provenanceDetail = "test"
        )
    }

    private fun buildSnapshotFor(evidences: List<ForecastEvidence>) =
        TomorrowRainPresentation.buildSnapshot(
            targetLocation = target,
            targetDateLocal = targetDate,
            rawEvidences = evidences,
            sourceStatuses = listOf(
                SourceFetchStatus("qweather", "和风天气", SourceFetchState.SUCCESS, evidences.size)
            ),
            nowEpochMs = referenceNow,
        )

    private fun localEpoch(value: String): Long = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply {
        timeZone = TimeZone.getTimeZone(timeZoneId)
    }.parse(value)!!.time

    private fun localHour(epochMs: Long): Int = java.util.Calendar.getInstance(TimeZone.getTimeZone(timeZoneId), Locale.CHINA).apply {
        timeInMillis = epochMs
    }.get(java.util.Calendar.HOUR_OF_DAY)

    private fun formatWindow(startEpochMs: Long, endEpochMs: Long): String {
        val formatter = SimpleDateFormat("HH:mm", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone(timeZoneId)
        }
        return "${formatter.format(startEpochMs)}–${formatter.format(endEpochMs)}"
    }
}
