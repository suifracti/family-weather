/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource.hourly.service

import android.content.Context
import breezyweather.domain.location.model.Location
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.schedulers.Schedulers
import okhttp3.OkHttpClient
import org.breezyweather.BuildConfig
import org.breezyweather.domain.multisource.hourly.adapter.BreezyChinaHourlyAdapter
import org.breezyweather.domain.multisource.hourly.adapter.OpenMeteoHourlyAdapter
import org.breezyweather.domain.multisource.hourly.adapter.QWeatherHourlyAdapter
import org.breezyweather.domain.multisource.hourly.presentation.SourceFetchState
import org.breezyweather.domain.multisource.hourly.presentation.SourceFetchStatus
import org.breezyweather.domain.multisource.hourly.presentation.TomorrowRainPresentation
import org.breezyweather.domain.multisource.hourly.presentation.TomorrowRainSnapshot
import org.breezyweather.domain.multisource.location.LocationAuthority
import org.breezyweather.domain.multisource.location.WeatherCoordinateSerializer
import org.breezyweather.domain.multisource.model.ForecastEvidence
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Fetches the agricultural primary location only and builds one immutable
 * evidence snapshot for both the home card and source comparison dialog.
 *
 * Each provider is isolated. A missing QWeather key or a failed request does
 * not discard Open-Meteo evidence, and no raw exception text is sent to UI.
 */
class TomorrowRainEvidenceService private constructor(
    private val okHttpClient: OkHttpClient,
    private val snapshotStore: TomorrowRainSnapshotStore,
) {

    private val qWeatherAdapter = QWeatherHourlyAdapter(okHttpClient)
    private val openMeteoAdapter = OpenMeteoHourlyAdapter(okHttpClient)
    private val breezyChinaAdapter = BreezyChinaHourlyAdapter()

    fun getTomorrowRain(existingLocation: Location?): Observable<TomorrowRainSnapshot> {
        val target = LocationAuthority.getAgriculturalPrimary()
        val now = System.currentTimeMillis()
        val targetDate = TomorrowRainPresentation.tomorrowDateLocal(
            nowEpochMs = now,
            timeZoneId = target.timeZoneId
        )
        return getForecastForDate(existingLocation, targetDate, now)
    }

    fun getForecastForDate(
        existingLocation: Location?,
        targetDate: String,
        requestedAtEpochMs: Long = System.currentTimeMillis(),
    ): Observable<TomorrowRainSnapshot> {
        val target = LocationAuthority.getAgriculturalPrimary()
        val now = requestedAtEpochMs
        val targetDates = setOf(
            TomorrowRainPresentation.todayDateLocal(now, target.timeZoneId),
            TomorrowRainPresentation.tomorrowDateLocal(now, target.timeZoneId),
        )
        val cached = snapshotStore.load(target, targetDate, now)
        val cachedPairIsFresh = targetDates.all { date ->
            snapshotStore.load(target, date, now)?.let { now - it.fetchedAtEpochMs in 0..REFRESH_AFTER_MS } == true
        }
        if (cached != null && cachedPairIsFresh) {
            return Observable.just(cached)
        }

        val qWeather = Observable.fromCallable {
            fetchQWeather(target, targetDates)
        }.subscribeOn(Schedulers.io())
        val openMeteo = Observable.fromCallable {
            fetchOpenMeteo(target, targetDates)
        }.subscribeOn(Schedulers.io())
        val cma = Observable.fromCallable {
            fetchOpenMeteo(target, targetDates, cmaOnly = true)
        }.subscribeOn(Schedulers.io())
        val breezyChina = Observable.fromCallable {
            fetchBreezyChina(target, targetDates, existingLocation)
        }.subscribeOn(Schedulers.io())

        val refreshed = TomorrowRainRequestCoordinator.aggregate(
            qWeather,
            openMeteo,
            breezyChina,
            failedOutcome(SOURCE_QWEATHER, "和风天气"),
            failedOutcome(SOURCE_OPEN_METEO, "Open-Meteo"),
            failedOutcome(SOURCE_CHINA, "小米天气（缓存）"),
            cma = cma,
            cmaFailure = failedOutcome(SOURCE_CMA, "CMA 国内模式，经 Open-Meteo 获取"),
        ).map { outcomes ->
            targetDates.map { date ->
                TomorrowRainPresentation.buildSnapshot(
                    targetLocation = target,
                    targetDateLocal = date,
                    rawEvidences = outcomes.flatMap { it.evidences },
                    sourceStatuses = outcomes.map { outcome -> statusForDate(outcome, date, target.timeZoneId) },
                    fetchedAtEpochMs = now,
                    nowEpochMs = now,
                )
            }
        }.doOnNext { snapshots ->
            snapshots.filter { it.evidences.isNotEmpty() || it.temperatureEvidences.isNotEmpty() }.forEach(snapshotStore::save)
        }.map { snapshots ->
            snapshots.first { it.targetDateLocal == targetDate }
        }

        return if (cached == null) {
            refreshed
        } else {
            Observable.concat(
                Observable.just(cached),
                refreshed.filter { it.evidences.isNotEmpty() || it.temperatureEvidences.isNotEmpty() }
            )
        }
    }

    private fun fetchQWeather(
        target: org.breezyweather.domain.multisource.location.TargetLocation,
        targetDates: Set<String>,
    ): FetchOutcome {
        if (BuildConfig.QWEATHER_API_HOST.isBlank()) return FetchOutcome(status = SourceFetchStatus(
            SOURCE_QWEATHER, "和风天气", SourceFetchState.MISSING_CONFIGURATION
        ))
        val apiKey = BuildConfig.QWEATHER_KEY.takeIf { it.isNotBlank() }
            ?: return FetchOutcome(
                status = SourceFetchStatus(
                    sourceId = SOURCE_QWEATHER,
                    displayName = "和风天气",
                    state = SourceFetchState.MISSING_CONFIGURATION
                )
            )

        return try {
            val result = qWeatherAdapter.fetchHourlyEvidencesForDatesDetailed(
                apiKey = apiKey,
                latitude = target.latitude,
                longitude = target.longitude,
                timeZoneId = target.timeZoneId,
                targetDatesLocal = targetDates,
                customApiHost = BuildConfig.QWEATHER_API_HOST.takeIf { it.isNotBlank() }
            )
            val state = when {
                result.evidences.isNotEmpty() -> SourceFetchState.SUCCESS
                result.hadSuccessfulResponse -> SourceFetchState.NO_DATA
                else -> SourceFetchState.FAILED
            }
            FetchOutcome(
                evidences = result.evidences,
                status = SourceFetchStatus(
                    sourceId = SOURCE_QWEATHER,
                    displayName = "和风天气",
                    state = state,
                    evidenceCount = result.evidences.size
                )
            )
        } catch (e: Exception) {
            FetchOutcome(
                status = SourceFetchStatus(
                    sourceId = SOURCE_QWEATHER,
                    displayName = "和风天气",
                    state = SourceFetchState.FAILED
                )
            )
        }
    }

    private fun fetchOpenMeteo(
        target: org.breezyweather.domain.multisource.location.TargetLocation,
        targetDates: Set<String>,
        cmaOnly: Boolean = false,
    ): FetchOutcome {
        val sourceId = if (cmaOnly) SOURCE_CMA else SOURCE_OPEN_METEO
        val displayName = if (cmaOnly) "CMA 国内模式，经 Open-Meteo 获取" else "Open-Meteo 国际对照"
        return try {
            val evidences = openMeteoAdapter.fetchHourlyEvidencesForDates(
                latitude = target.latitude,
                longitude = target.longitude,
                timeZoneId = target.timeZoneId,
                targetDatesLocal = targetDates,
                cmaOnly = cmaOnly
            )
            FetchOutcome(
                evidences = evidences,
                status = SourceFetchStatus(
                    sourceId = sourceId,
                    displayName = displayName,
                    state = if (evidences.isEmpty()) SourceFetchState.NO_DATA else SourceFetchState.SUCCESS,
                    evidenceCount = evidences.size
                )
            )
        } catch (e: Exception) {
            FetchOutcome(
                status = SourceFetchStatus(
                    sourceId = sourceId,
                    displayName = displayName,
                    state = SourceFetchState.FAILED
                )
            )
        }
    }

    /**
     * The Breezy China channel is already part of the app's selected weather
     * source. We adapt it only when the current weather cache is the fixed
     * agricultural primary location; county data must not be relabeled as the
     * orchard. The channel itself does not provide numeric PoP/mm fields.
     */
    private fun fetchBreezyChina(
        target: org.breezyweather.domain.multisource.location.TargetLocation,
        targetDates: Set<String>,
        existingLocation: Location?,
    ): FetchOutcome {
        val sameAgriculturalLocation = existingLocation != null &&
            existingLocation.forecastSource.equals("china", ignoreCase = true) &&
            LocationAuthority.isAgriculturalPrimary(
                existingLocation.latitude,
                existingLocation.longitude
            )
        if (!sameAgriculturalLocation) {
            return FetchOutcome(
                status = SourceFetchStatus(
                    sourceId = SOURCE_CHINA,
                    displayName = "小米天气（缓存）",
                    state = SourceFetchState.NOT_RUN
                )
            )
        }

        val entries = existingLocation.weather?.hourlyForecast.orEmpty().map { hourly ->
            BreezyChinaHourlyAdapter.HourlyWeatherEntry(
                validFromEpochMs = hourly.date.time,
                weatherText = hourly.weatherText ?: "未知",
                weatherCode = hourly.weatherCode?.toString(),
                temperatureCelsius = hourly.temperature?.temperature?.toDouble(org.breezyweather.unit.temperature.TemperatureUnit.CELSIUS)
            )
        }
        val evidences = targetDates.flatMap { targetDate ->
            breezyChinaAdapter.adaptHourlyForecast(
                canonicalLocationId = WeatherCoordinateSerializer.toCanonicalLocationId(target.latitude, target.longitude),
                hourlyWeatherEntries = entries,
                timeZoneId = target.timeZoneId,
                targetDateLocal = targetDate,
                fetchedAtEpochMs = existingLocation?.weather?.base?.refreshTime?.time,
                sourceUpdatedAtEpochMs = existingLocation?.weather?.base?.forecastUpdateTime?.time,
            )
        }
        return FetchOutcome(
            evidences = evidences,
            status = SourceFetchStatus(
                sourceId = SOURCE_CHINA,
                displayName = "小米天气（缓存）",
                state = if (evidences.isEmpty()) SourceFetchState.NO_DATA else SourceFetchState.SUCCESS,
                evidenceCount = evidences.size
            )
        )
    }

    private fun statusForDate(outcome: FetchOutcome, date: String, timeZoneId: String): SourceFetchStatus {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone(timeZoneId)
        }
        val count = outcome.evidences.count { formatter.format(Date(it.validFromEpochMs)) == date ||
            it.temperatureAtEpochMs?.let { t -> formatter.format(Date(t)) == date } == true }
        val state = if (count == 0 && outcome.status.state == SourceFetchState.SUCCESS) {
            SourceFetchState.NO_DATA
        } else {
            outcome.status.state
        }
        return outcome.status.copy(state = state, evidenceCount = count)
    }

    companion object {
        internal const val SOURCE_QWEATHER = "qweather"
        internal const val SOURCE_CMA = "openmeteo:cma_grapes"
        internal const val SOURCE_OPEN_METEO = "openmeteo"
        internal const val SOURCE_CHINA = "china"
        internal const val REFRESH_AFTER_MS = 30L * 60L * 1000L

        @Volatile
        private var instance: TomorrowRainEvidenceService? = null

        fun getInstance(context: Context): TomorrowRainEvidenceService {
            return instance ?: synchronized(this) {
                instance ?: TomorrowRainEvidenceService(
                    OkHttpClient.Builder()
                        .callTimeout(20, TimeUnit.SECONDS)
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(20, TimeUnit.SECONDS)
                        .writeTimeout(20, TimeUnit.SECONDS)
                        .build(),
                    TomorrowRainSnapshotStore.from(context),
                ).also { instance = it }
            }
        }
    }
}

internal data class FetchOutcome(
    val evidences: List<ForecastEvidence> = emptyList(),
    val status: SourceFetchStatus,
)

private fun failedOutcome(sourceId: String, displayName: String) = FetchOutcome(
    status = SourceFetchStatus(sourceId, displayName, SourceFetchState.FAILED)
)

internal object TomorrowRainRequestCoordinator {
    fun aggregate(
        qWeather: Observable<FetchOutcome>,
        openMeteo: Observable<FetchOutcome>,
        breezyChina: Observable<FetchOutcome>,
        qWeatherFailure: FetchOutcome,
        openMeteoFailure: FetchOutcome,
        breezyChinaFailure: FetchOutcome,
        singleSourceTimeout: Long = 20L,
        aggregateTimeout: Long = 25L,
        timeUnit: TimeUnit = TimeUnit.SECONDS,
        scheduler: Scheduler = Schedulers.computation(),
        cma: Observable<FetchOutcome>? = null,
        cmaFailure: FetchOutcome = failedOutcome("openmeteo:cma_grapes", "CMA 国内模式，经 Open-Meteo 获取"),
    ): Observable<List<FetchOutcome>> {
        fun guarded(source: Observable<FetchOutcome>, failure: FetchOutcome): Observable<FetchOutcome> =
            source.timeout(singleSourceTimeout, timeUnit, scheduler).onErrorReturnItem(failure)

        val sources = listOf(
            guarded(qWeather, qWeatherFailure),
            guarded(openMeteo, openMeteoFailure),
            guarded(breezyChina, breezyChinaFailure),
        ) + listOfNotNull(cma?.let { guarded(it, cmaFailure) })
        return Observable.zip(sources) { outcomes -> outcomes.map { it as FetchOutcome } }
            .timeout(aggregateTimeout, timeUnit, scheduler)
            .onErrorReturnItem(listOf(qWeatherFailure, openMeteoFailure, breezyChinaFailure) +
                if (cma != null) listOf(cmaFailure) else emptyList())
    }
}
