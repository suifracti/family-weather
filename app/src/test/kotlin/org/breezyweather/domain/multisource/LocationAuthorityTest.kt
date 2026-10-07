/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.breezyweather.domain.multisource.engine.WeatherEvidenceEngine
import org.breezyweather.domain.multisource.location.LocationAuthority
import org.breezyweather.domain.multisource.location.LocationRole
import org.breezyweather.domain.multisource.location.WeatherCoordinateSerializer
import org.breezyweather.domain.multisource.model.ForecastEvidence
import org.breezyweather.domain.multisource.model.ForecastHorizonType
import org.breezyweather.domain.multisource.model.PrecipitationProbabilityValue
import org.breezyweather.domain.multisource.model.PrecipitationValue
import org.breezyweather.domain.multisource.model.SourceIdentity
import org.breezyweather.domain.multisource.model.UnderlyingModel
import org.breezyweather.domain.multisource.model.WeatherCondition
import org.breezyweather.domain.multisource.model.WeatherProvider
import org.breezyweather.domain.multisource.nowcast.BreezyChinaNowcastAdapter
import org.breezyweather.domain.multisource.nowcast.QWeatherNowcastAdapter
import org.junit.jupiter.api.Test

class LocationAuthorityTest {

    @Test
    fun `test coordinate serializer formats strictly and prevents lat lon order confusion`() {
        // Synthetic serializer fixtures, not configured orchard coordinates.
        val worksiteLat = 12.34
        val worksiteLon = 56.78

        // 1. Canonical ID (lat, lon) with fixed precision
        WeatherCoordinateSerializer.toCanonicalLocationId(worksiteLat, worksiteLon) shouldBe "coord:12.3400,56.7800"

        // 2. QWeather Hourly v1 path: strictly /{latitude}/{longitude}
        WeatherCoordinateSerializer.toQWeatherHourlyPath(worksiteLat, worksiteLon) shouldBe "12.34/56.78"

        // 3. QWeather Minutely v7 query param: strictly {longitude},{latitude}
        WeatherCoordinateSerializer.toQWeatherLocationParam(worksiteLat, worksiteLon) shouldBe "56.78,12.34"

        // 4. Open-Meteo query params: strictly latitude={latitude}&longitude={longitude}
        WeatherCoordinateSerializer.toOpenMeteoQueryParams(worksiteLat, worksiteLon) shouldBe "latitude=12.34&longitude=56.78"

        // 5. Breezy China / Caiyun query param: strictly {longitude},{latitude}
        WeatherCoordinateSerializer.toBreezyChinaLocationParam(worksiteLat, worksiteLon) shouldBe "56.78,12.34"

        // Verify county town coordinates
        val countyLat = 12.38558
        val countyLon = 56.7609
        WeatherCoordinateSerializer.toCanonicalLocationId(countyLat, countyLon) shouldBe "coord:12.3856,56.7609"
        WeatherCoordinateSerializer.toQWeatherHourlyPath(countyLat, countyLon) shouldBe "12.38558/56.7609"
        WeatherCoordinateSerializer.toQWeatherLocationParam(countyLat, countyLon) shouldBe "56.7609,12.38558"
    }

    @Test
    fun `test location authority assigns roles and guarantees cache and dedup isolation`() {
        val worksite = LocationAuthority.getAgriculturalPrimary()
        val county = LocationAuthority.getCountyReference()

        worksite.role shouldBe LocationRole.AGRICULTURAL_PRIMARY
        worksite.displayName shouldBe "工作地 / 果园"
        worksite.latitude.isFinite() shouldBe true
        worksite.longitude.isFinite() shouldBe true

        county.role shouldBe LocationRole.COUNTY_REFERENCE
        county.displayName.isNotBlank() shouldBe true
        county.latitude.isFinite() shouldBe true
        county.longitude.isFinite() shouldBe true

        // Strictly distinct canonical identities
        worksite.canonicalLocationId shouldNotBe county.canonicalLocationId
        worksite.canonicalLocationId shouldBe WeatherCoordinateSerializer.toCanonicalLocationId(worksite.latitude, worksite.longitude)
        county.canonicalLocationId shouldBe WeatherCoordinateSerializer.toCanonicalLocationId(county.latitude, county.longitude)

        // Deduplication test: Worksite and County evidences with identical timestamps must NEVER merge
        val now = 1726646400000L
        val worksiteEvidence = ForecastEvidence(
            canonicalLocationId = worksite.canonicalLocationId,
            sourceIdentity = SourceIdentity.resolve(WeatherProvider.OPEN_METEO, UnderlyingModel.ECMWF_IFS),
            issuedAtEpochMs = now,
            validFromEpochMs = now,
            validToEpochMs = now + 3600_000L,
            weatherCondition = WeatherCondition("小雨", isRainCondition = true),
            precipitationProbability = PrecipitationProbabilityValue.available(70),
            precipitationAmount = PrecipitationValue.available(2.0),
            precipitationIntensity = PrecipitationValue.available(2.0),
            freshnessSeconds = 0L,
            rawSource = "openmeteo",
            provenanceDetail = "ECMWF Worksite"
        )

        val countyEvidence = ForecastEvidence(
            canonicalLocationId = county.canonicalLocationId,
            sourceIdentity = SourceIdentity.resolve(WeatherProvider.OPEN_METEO, UnderlyingModel.ECMWF_IFS),
            issuedAtEpochMs = now,
            validFromEpochMs = now,
            validToEpochMs = now + 3600_000L,
            weatherCondition = WeatherCondition("多云", isRainCondition = false),
            precipitationProbability = PrecipitationProbabilityValue.available(0),
            precipitationAmount = PrecipitationValue.available(0.0),
            precipitationIntensity = PrecipitationValue.available(0.0),
            freshnessSeconds = 0L,
            rawSource = "openmeteo",
            provenanceDetail = "ECMWF County"
        )

        val deduped = WeatherEvidenceEngine.deduplicate(listOf(worksiteEvidence, countyEvidence))
        // Both records must be preserved!
        deduped.size shouldBe 2
        deduped.map { it.canonicalLocationId }.toSet() shouldBe setOf(
            worksite.canonicalLocationId,
            county.canonicalLocationId
        )
    }

    @Test
    fun `test QWeather and Breezy China nowcast adapters separate nowcast from tomorrow forecast`() {
        val qweatherAdapter = QWeatherNowcastAdapter()
        val breezyChinaAdapter = BreezyChinaNowcastAdapter()

        val sampleQWeatherJson = """
            {
              "code": "200",
              "updateTime": "2026-09-18T14:30+08:00",
              "summary": "未来两小时无明显降雨",
              "minutely": [
                { "fxTime": "2026-09-18T14:35+08:00", "precip": "0.0", "type": "rain" },
                { "fxTime": "2026-09-18T14:40+08:00", "precip": "0.1", "type": "rain" },
                { "fxTime": "2026-09-18T14:45+08:00", "precip": "0.4", "type": "rain" }
              ]
            }
        """.trimIndent()

        val worksiteId = WeatherCoordinateSerializer.toCanonicalLocationId(12.34, 56.78)
        val qNowcast = qweatherAdapter.parseMinutelyResponse(sampleQWeatherJson, worksiteId)

        qNowcast shouldNotBe null
        qNowcast!!.horizonType shouldBe ForecastHorizonType.NOWCAST
        qNowcast.summaryText shouldBe "未来两小时无明显降雨"
        qNowcast.intervals.size shouldBe 3
        qNowcast.maxPrecipitationMm shouldBe 0.4
        qNowcast.provider shouldBe WeatherProvider.QWEATHER

        // Breezy China nowcast
        val chinaNowcast = breezyChinaAdapter.adaptNowcast(
            latitude = 12.34,
            longitude = 56.78,
            summaryText = "未来两小时不会下雨",
            minuteSteps = listOf(Pair(1726646400000L, 0.0))
        )
        chinaNowcast.horizonType shouldBe ForecastHorizonType.NOWCAST
        chinaNowcast.summaryText shouldBe "未来两小时不会下雨"
        chinaNowcast.provider shouldBe WeatherProvider.BREEZY_CHINA

        // Invariant: Do not average them
        qNowcast.provider shouldNotBe chinaNowcast.provider
    }
}
