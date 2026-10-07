package org.breezyweather.domain.multisource

import io.kotest.matchers.shouldBe
import org.breezyweather.domain.multisource.hourly.adapter.OpenMeteoHourlyAdapter
import org.breezyweather.domain.multisource.hourly.service.TomorrowRainSnapshotStore
import org.breezyweather.domain.multisource.model.UnderlyingModel
import org.junit.jupiter.api.Test
import io.reactivex.rxjava3.core.Observable
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.breezyweather.domain.multisource.hourly.service.*
import org.breezyweather.domain.multisource.hourly.presentation.*
import org.breezyweather.domain.multisource.location.LocationAuthority
import org.breezyweather.domain.multisource.hourly.adapter.BreezyChinaHourlyAdapter
import org.breezyweather.domain.multisource.hourly.adapter.QWeatherHourlyAdapter


class DomesticHourlyEvidenceTest {
    private val adapter = OpenMeteoHourlyAdapter()
    @Test fun `a single model response retains CMA without relabelling best match`() {
        val result = adapter.parseHourlyResponse(
            """{"latitude":12.125,"longitude":56.875,"hourly":{"time":[1791388800,1791392400],"temperature_2m":[18.5,null],"precipitation":[0.4,0],"weather_code":[61,0]}}""",
            LocationAuthority.WORKSITE.latitude,LocationAuthority.WORKSITE.longitude,targetDatesLocal=setOf("2026-10-08"),nowEpochMs=1000L, requestedSingleModel=UnderlyingModel.CMA_GRAPES,
        )
        // Unsuffixed fields cannot be safely attributed without the requested model.
        result.size shouldBe 2
        result.map { it.underlyingModel }.distinct() shouldBe listOf(UnderlyingModel.CMA_GRAPES)
    }
    @Test fun `fetch time is not manufactured publication time`() {
        val result = adapter.parseHourlyResponse(
            """{"hourly":{"time":[1791392400],"precipitation_cma_grapes_global":[0.4],"weather_code_cma_grapes_global":[61]}}""",
            LocationAuthority.WORKSITE.latitude,LocationAuthority.WORKSITE.longitude,targetDatesLocal=setOf("2026-10-08"),nowEpochMs=1000L,
        )
        result.single().issuedAtEpochMs shouldBe 0L
    }
    @Test fun `old weather evidence cannot masquerade as a complete new schema`() {
        TomorrowRainSnapshotStore.CACHE_VERSION shouldBe 3
    }
    private fun parsed(body: String = singleBody, single: UnderlyingModel? = UnderlyingModel.CMA_GRAPES) = adapter.parseHourlyResponse(
        body,LocationAuthority.WORKSITE.latitude,LocationAuthority.WORKSITE.longitude,targetDatesLocal=setOf("2026-10-08"),nowEpochMs=1000L, requestedSingleModel=single,
    )
    private val singleBody = """{"latitude":12.125,"longitude":56.875,"hourly_units":{"temperature_2m":"°C"},"hourly":{"time":[1791388800,1791392400],"temperature_2m":[18.5,null],"precipitation":[0.4,0],"weather_code":[61,0]}}"""

    @Test fun `unsuffixed response without explicit model identity is not guessed`() {
        parsed(single= null).size shouldBe 0
    }
    @Test fun `midnight temperature survives separately from preceding day rainfall`() {
        val snapshot = TomorrowRainPresentation.buildSnapshot(LocationAuthority.WORKSITE,"2026-10-08",parsed(),emptyList(),1000L)
        snapshot.evidences.size shouldBe 1
        snapshot.temperatureEvidences.size shouldBe 2
        snapshot.temperatureEvidences.first().temperatureAtEpochMs shouldBe 1791388800000L
        snapshot.temperatureEvidences.first().validFromEpochMs shouldBe 1791385200000L
        val first = TomorrowRainPresentation.hourlyDisplayRows(snapshot).first()
        first.hour shouldBe 0
        first.temperatureReadings.single().celsius shouldBe 18.5
        first.forecasts.single().amountMm shouldBe 0.0
    }
    @Test fun `missing temperature remains unknown and is not zero or borrowed`() {
        parsed()[1].temperatureCelsius shouldBe null
        HourlyEvidenceText.temperature(null) shouldBe "未提供"
        parsed()[1].precipitationProbability.percentage shouldBe null
    }
    @Test fun `Fahrenheit response is not displayed as Celsius`() {
        parsed(singleBody.replace("°C","°F")).first().temperatureCelsius shouldBe null
    }
    @Test fun `cache round trip preserves time grid temperature and separate model identities`() {
        val storage = Memory(); val store = TomorrowRainSnapshotStore(storage)
        val raw = parsed() + parsed().map { it.copy(sourceIdentity=org.breezyweather.domain.multisource.model.SourceIdentity.resolve(
            org.breezyweather.domain.multisource.model.WeatherProvider.OPEN_METEO, UnderlyingModel.ECMWF_IFS)) }
        val snapshot = TomorrowRainPresentation.buildSnapshot(LocationAuthority.WORKSITE,"2026-10-08",raw,emptyList(),2000L)
        store.save(snapshot)
        val restored = store.load(LocationAuthority.WORKSITE,"2026-10-08")!!
        restored.temperatureEvidences.size shouldBe 4
        restored.temperatureEvidences.map { it.underlyingModel }.distinct().size shouldBe 2
        restored.temperatureEvidences.first().issuedAtEpochMs shouldBe 0L
        restored.temperatureEvidences.first().fetchedAtEpochMs shouldBe 1000L
        restored.temperatureEvidences.first().resolvedLatitude shouldBe 12.125
        restored.fetchedAtEpochMs shouldBe 2000L
        store.load(LocationAuthority.COUNTY_TOWN,"2026-10-08") shouldBe null
        store.load(LocationAuthority.WORKSITE,"2026-10-09") shouldBe null
        storage.raw = storage.raw!!.replace("\"version\":3","\"version\":2")
        store.load(LocationAuthority.WORKSITE,"2026-10-08") shouldBe null
    }
    @Test fun `CMA and international HTTP failures are independent in either direction`() {
        for (failCma in listOf(true,false)) {
            val requested = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request(); val models = request.url.queryParameter("models")!!
                requested.add(models)
                val isCma = models == "cma_grapes_global"
                val body = if (isCma) singleBody else singleBody.replace("temperature_2m", "temperature_2m_ecmwf_ifs025")
                    .replace("precipitation\"", "precipitation_ecmwf_ifs025\"").replace("weather_code", "weather_code_ecmwf_ifs025")
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (isCma==failCma) 503 else 200)
                    .message("test").body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val fetcher = OpenMeteoHourlyAdapter(client)
            fun fetch(cma: Boolean): Observable<FetchOutcome> = Observable.fromCallable {
                val e = fetcher.fetchHourlyEvidencesForDates(LocationAuthority.WORKSITE.latitude,LocationAuthority.WORKSITE.longitude,targetDatesLocal=setOf("2026-10-08"),cmaOnly=cma)
                FetchOutcome(e,SourceFetchStatus(if(cma)"openmeteo:cma_grapes" else "openmeteo","test",SourceFetchState.SUCCESS,e.size))
            }
            fun failure(id: String) = FetchOutcome(status=SourceFetchStatus(id,id,SourceFetchState.FAILED))
            val results = TomorrowRainRequestCoordinator.aggregate(
                Observable.just(failure("qweather")), fetch(false), Observable.just(failure("china")),
                failure("qweather"),failure("openmeteo"),failure("china"),cma=fetch(true),cmaFailure=failure("openmeteo:cma_grapes"),
            ).blockingFirst()
            results.first { it.status.sourceId == if(failCma)"openmeteo:cma_grapes" else "openmeteo" }.status.state shouldBe SourceFetchState.FAILED
            results.first { it.status.sourceId == if(failCma)"openmeteo" else "openmeteo:cma_grapes" }.evidences.isNotEmpty() shouldBe true
            requested.size shouldBe 2
            requested.count { it == "cma_grapes_global" } shouldBe 1
            requested.filter { it != "cma_grapes_global" }.single().contains("cma") shouldBe false
        }
    }
    @Test fun `missing QWeather host does not call a legacy endpoint`() {
        var calls=0
        val client=OkHttpClient.Builder().addInterceptor { calls++; error("no request expected") }.build()
        QWeatherHourlyAdapter(client).fetchHourlyEvidencesForDatesDetailed("test",LocationAuthority.WORKSITE.latitude,LocationAuthority.WORKSITE.longitude,targetDatesLocal=setOf("2026-10-08"))
            .evidences.size shouldBe 0
        calls shouldBe 0
    }
    @Test fun `failed point QWeather never falls back to city v7`() {
        val paths=mutableListOf<String>()
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            paths.add(chain.request().url.encodedPath)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("test")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        QWeatherHourlyAdapter(client).fetchHourlyEvidencesForDatesDetailed("test",LocationAuthority.WORKSITE.latitude,LocationAuthority.WORKSITE.longitude,
            targetDatesLocal=setOf("2026-10-08"),customApiHost="https://example.invalid")
        paths.size shouldBe 1
        paths.single().startsWith("/weather/v1/hourly/") shouldBe true
    }
    @Test fun `China cache keeps original timestamps and does not claim publication`() {
        val e=BreezyChinaHourlyAdapter().adaptHourlyForecast(LocationAuthority.WORKSITE.canonicalLocationId,
            listOf(BreezyChinaHourlyAdapter.HourlyWeatherEntry(1791392400000L,"小雨","61",20.0)),
            targetDateLocal="2026-10-08",fetchedAtEpochMs=900L,sourceUpdatedAtEpochMs=800L).single()
        e.issuedAtEpochMs shouldBe 0L; e.fetchedAtEpochMs shouldBe 900L
        e.sourceUpdatedAtEpochMs shouldBe 800L; e.precipitationAmount.valueMm shouldBe null
        e.precipitationPeriodKnown shouldBe false
    }
    @Test fun `source metadata never formats unknown publication as epoch or fetch time`() {
        val slot=TomorrowRainPresentation.buildSnapshot(LocationAuthority.WORKSITE,"2026-10-08",parsed(),emptyList(),1000L).slots.first()
        val metrics=slot.sourceMetrics.single()
        HourlyEvidenceText.metadata(metrics,"Asia/Shanghai").contains("发布时间：未提供") shouldBe true
        metrics.displayName shouldBe "CMA 国内模式，经 Open-Meteo 获取"
        metrics.temporalResolutionDetail.contains("原生3小时") shouldBe true
        HourlyEvidenceText.matches(SourceFetchStatus("openmeteo","international",SourceFetchState.FAILED),metrics) shouldBe false
    }
    private class Memory: SnapshotCacheStorage {
        var raw: String?=null
        override fun read()=raw
        override fun write(value:String) { raw=value }
    }
    @Test fun `temperature only cache remains available without manufacturing rain evidence`() {
        val storage=Memory(); val store=TomorrowRainSnapshotStore(storage)
        val temperatures = parsed().map { it.copy(precipitationPeriodKnown=false) }
        val snapshot=TomorrowRainPresentation.buildSnapshot(LocationAuthority.WORKSITE,"2026-10-08",temperatures,emptyList(),1000L)
        snapshot.evidences.size shouldBe 0
        store.save(snapshot)
        store.load(LocationAuthority.WORKSITE,"2026-10-08")?.temperatureEvidences?.size shouldBe 2
    }
}
