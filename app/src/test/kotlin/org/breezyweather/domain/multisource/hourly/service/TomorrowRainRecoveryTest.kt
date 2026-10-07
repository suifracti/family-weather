/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource.hourly.service

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.TestScheduler
import org.breezyweather.domain.multisource.hourly.presentation.SourceFetchState
import org.breezyweather.domain.multisource.hourly.presentation.SourceFetchStatus
import org.breezyweather.domain.multisource.hourly.presentation.TomorrowRainPresentation
import org.breezyweather.domain.multisource.location.LocationAuthority
import org.breezyweather.domain.multisource.model.ForecastEvidence
import org.breezyweather.domain.multisource.model.PrecipitationProbabilityValue
import org.breezyweather.domain.multisource.model.PrecipitationValue
import org.breezyweather.domain.multisource.model.SourceIdentity
import org.breezyweather.domain.multisource.model.UnderlyingModel
import org.breezyweather.domain.multisource.model.WeatherCondition
import org.breezyweather.domain.multisource.model.WeatherProvider
import org.breezyweather.ui.main.adapters.main.holder.TomorrowRainReadTimeText
import org.junit.jupiter.api.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class TomorrowRainRecoveryTest {

    @Test
    fun `cache accepts only matching orchard date and schema version`() {
        val storage = MemoryStorage()
        val store = TomorrowRainSnapshotStore(storage)
        val snapshot = snapshot("2026-09-24", fetchedAt = 10_000L)
        store.save(snapshot)

        val restored = store.load(LocationAuthority.WORKSITE, "2026-09-24")
        restored?.targetDateLocal shouldBe "2026-09-24"
        restored?.recoveredFromStorage shouldBe true
        restored?.fetchedAtEpochMs shouldBe 10_000L
        restored?.evidences?.single()?.issuedAtEpochMs shouldBe 500L
        TomorrowRainReadTimeText.build(restored!!, nowEpochMs = 20_000L) shouldBe
            "已恢复 · 读取于 1970-01-01 08:00"
        TomorrowRainReadTimeText.build(restored, nowEpochMs = 10_000L + 6L * 60L * 60L * 1000L + 1L) shouldBe
            "旧预报 · 读取于 1970-01-01 08:00"
        store.load(LocationAuthority.WORKSITE, "2026-09-25") shouldBe null

        storage.value = storage.value?.replace(
            LocationAuthority.WORKSITE.canonicalLocationId,
            LocationAuthority.COUNTY_TOWN.canonicalLocationId,
        )
        store.load(LocationAuthority.WORKSITE, "2026-09-24") shouldBe null

        store.save(snapshot)
        storage.value = storage.value?.replace("\"version\":${TomorrowRainSnapshotStore.CACHE_VERSION}", "\"version\":1")
        store.load(LocationAuthority.WORKSITE, "2026-09-24") shouldBe null

        store.save(snapshot)
        storage.value = storage.value?.replace("\"version\":${TomorrowRainSnapshotStore.CACHE_VERSION}", "\"version\":99")
        store.load(LocationAuthority.WORKSITE, "2026-09-24") shouldBe null
    }

    @Test
    fun `cache retains at most the two newest target dates`() {
        val storage = MemoryStorage()
        val store = TomorrowRainSnapshotStore(storage)
        store.save(snapshot("2026-09-24", 1_000L))
        store.save(snapshot("2026-09-25", 2_000L))
        store.save(snapshot("2026-09-26", 3_000L))

        store.load(LocationAuthority.WORKSITE, "2026-09-24") shouldBe null
        store.load(LocationAuthority.WORKSITE, "2026-09-25")?.targetDateLocal shouldBe "2026-09-25"
        store.load(LocationAuthority.WORKSITE, "2026-09-26")?.targetDateLocal shouldBe "2026-09-26"
    }

    @Test
    fun `timed out source does not discard successful source outcomes`() {
        val scheduler = TestScheduler()
        val qWeather = outcome("qweather", evidence("2026-09-24", WeatherProvider.QWEATHER))
        val china = outcome("china", evidence("2026-09-24", WeatherProvider.BREEZY_CHINA))
        val observer = TomorrowRainRequestCoordinator.aggregate(
            qWeather = Observable.just(qWeather),
            openMeteo = Observable.never(),
            breezyChina = Observable.just(china),
            qWeatherFailure = failed("qweather"),
            openMeteoFailure = failed("openmeteo"),
            breezyChinaFailure = failed("china"),
            singleSourceTimeout = 20,
            aggregateTimeout = 25,
            timeUnit = TimeUnit.SECONDS,
            scheduler = scheduler,
        ).test()

        scheduler.advanceTimeBy(20, TimeUnit.SECONDS)

        observer.assertComplete().assertNoErrors()
        val outcomes = observer.values().single()
        outcomes.map { it.status.sourceId } shouldContainExactly listOf("qweather", "openmeteo", "china")
        outcomes.first { it.status.sourceId == "qweather" }.evidences.size shouldBe 1
        outcomes.first { it.status.sourceId == "openmeteo" }.status.state shouldBe SourceFetchState.FAILED
        outcomes.first { it.status.sourceId == "china" }.evidences.size shouldBe 1
    }

    private fun snapshot(date: String, fetchedAt: Long) = TomorrowRainPresentation.buildSnapshot(
        targetLocation = LocationAuthority.WORKSITE,
        targetDateLocal = date,
        rawEvidences = listOf(evidence(date, WeatherProvider.QWEATHER)),
        sourceStatuses = listOf(SourceFetchStatus("qweather", "和风天气", SourceFetchState.SUCCESS, 1)),
        fetchedAtEpochMs = fetchedAt,
    )

    private fun outcome(sourceId: String, evidence: ForecastEvidence) = FetchOutcome(
        evidences = listOf(evidence),
        status = SourceFetchStatus(sourceId, sourceId, SourceFetchState.SUCCESS, 1),
    )

    private fun failed(sourceId: String) = FetchOutcome(
        status = SourceFetchStatus(sourceId, sourceId, SourceFetchState.FAILED),
    )

    private fun evidence(date: String, provider: WeatherProvider): ForecastEvidence {
        val epochMs = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.parse("$date 13:00")!!.time
        val model = when (provider) {
            WeatherProvider.QWEATHER -> UnderlyingModel.QWEATHER_AGGREGATED
            WeatherProvider.BREEZY_CHINA -> UnderlyingModel.CAIYUN_REGIONAL
            else -> UnderlyingModel.OPEN_METEO_BEST_MATCH
        }
        return ForecastEvidence(
            canonicalLocationId = LocationAuthority.WORKSITE.canonicalLocationId,
            sourceIdentity = SourceIdentity.resolve(provider, model),
            issuedAtEpochMs = 500L,
            validFromEpochMs = epochMs,
            validToEpochMs = epochMs + 3_600_000L,
            weatherCondition = WeatherCondition("小雨", isRainCondition = true),
            precipitationProbability = PrecipitationProbabilityValue.available(60),
            precipitationAmount = PrecipitationValue.available(0.5),
            precipitationIntensity = PrecipitationValue.available(0.5),
            freshnessSeconds = 0L,
            rawSource = "test://forecast",
            provenanceDetail = "test",
        )
    }

    private class MemoryStorage : SnapshotCacheStorage {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) {
            this.value = value
        }
    }
}
