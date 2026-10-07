package org.breezyweather.sources.climweb

import android.content.Context
import android.content.SharedPreferences
import breezyweather.domain.location.model.Location
import breezyweather.domain.source.SourceContinent
import breezyweather.domain.source.SourceFeature
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.reactivex.rxjava3.core.Observable
import org.breezyweather.common.exceptions.InvalidLocationException
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import retrofit2.Retrofit

class ClimWebServiceLocationIdTest {
    @Test
    fun `blank city id fails normals instead of making an invalid request`() {
        val context = mockk<Context>()
        val preferences = mockk<SharedPreferences>()
        every { context.getSharedPreferences(any(), any()) } returns preferences
        every { preferences.getString("instance", null) } returns "https://example.test/"

        val api = mockk<ClimWebApi>()
        every { api.getNormals(any(), any()) } returns Observable.just(emptyList())
        val retrofit = mockk<Retrofit>()
        every { retrofit.create(ClimWebApi::class.java) } returns api
        val retrofitBuilder = mockk<Retrofit.Builder>()
        every { retrofitBuilder.baseUrl(any<String>()) } returns retrofitBuilder
        every { retrofitBuilder.build() } returns retrofit

        val service = TestClimWebService(context, retrofitBuilder)
        val location = Location(
            countryCode = "XX",
            normalsSource = service.id,
            parameters = mapOf(service.id to mapOf("cityId" to ""))
        )

        val result = service.requestWeather(context, location, listOf(SourceFeature.NORMALS)).blockingFirst()

        assertInstanceOf(InvalidLocationException::class.java, result.failedFeatures?.get(SourceFeature.NORMALS))
        verify(exactly = 0) { api.getNormals(any(), any()) }
    }

    private class TestClimWebService(
        override val context: Context,
        override val jsonClient: Retrofit.Builder,
    ) : ClimWebService() {
        override val id = "testclimweb"
        override val name = "Test ClimWeb"
        override val baseUrl = "https://example.test/"
        override val countryCode = "XX"
        override val cityClimatePageId = "42"
        override val instancePreference = 0
        override val weatherAttribution = "Test weather"
        override val alertAttribution = "Test alerts"
        override val normalsAttribution = "Test normals"
        override val privacyPolicyUrl = baseUrl
        override val continent = SourceContinent.AFRICA
        override val isConfigured = true
        override val isRestricted = false
    }
}
