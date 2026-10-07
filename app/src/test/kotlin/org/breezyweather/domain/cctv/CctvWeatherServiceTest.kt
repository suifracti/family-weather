/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.cctv

import io.kotest.matchers.shouldBe
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import org.junit.jupiter.api.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class CctvWeatherServiceTest {

    private val service = CctvWeatherService(OkHttpClient())

    private val officialLianboSample = """
        getLbDatas({"status":200,"data":[
          {"id":99999,"type":"3M","title":"午间天气预报20261002","pubDate":"2026-10-02 22:00:00","url":"https://vod.weathertv.cn/video/2026/10/2/noon.mp4"},
          {"id":24209,"type":"3M","title":"新闻联播天气预报20261001","pubDate":"2026-10-02 21:00:00","url":"https://vod.weathertv.cn/video/2026/10/1/202610011790861189733.mp4"},
          {"id":24222,"type":"3M","title":"新闻联播天气预报20261002","pubDate":"2026-10-02 20:26:30","summary":"新闻联播天气预报 20261002","url":"https://vod.weathertv.cn/video/2026/10/2/202610021790943989883.mp4"}
        ]})
    """.trimIndent()

    @Test
    fun `official lianbo latest keeps catalogue identity and actual programme date`() {
        val episode = service.parseLatestResponse(officialLianboSample, epoch("2026-10-02 21:00"))

        episode.episodeDate shouldBe "2026-10-02"
        episode.isToday shouldBe true
        episode.sourceIdentityKey shouldBe "weather_com_cn|CHINA_WEATHER_LIANBO|3M|24222"
        episode.title shouldBe "新闻联播天气预报20261002"
        episode.url shouldBe "https://www.weather.com.cn/video/yubao.shtml?globalid=24222"
        episode.mediaUrl shouldBe "https://vod.weathertv.cn/video/2026/10/2/202610021790943989883.mp4"
        episode.editorialBrief shouldBe null
        episode.refreshedFor(epoch("2026-10-03 00:05")).isToday shouldBe false
    }

    @Test
    fun `lianbo publication time cannot establish a missing programme date`() {
        val episode = service.parseLatestResponse(
            """getLbDatas({"data":[{"id":24222,"type":"3M","title":"新闻联播天气预报","pubDate":"2026-10-02 20:26:30","url":"https://vod.weathertv.cn/video/2026/10/2/202610021790943989883.mp4"}]})""",
            epoch("2026-10-02 21:00")
        )

        episode.identityStatus shouldBe CctvIdentityStatus.UNKNOWN
        episode.episodeDate shouldBe null
        episode.isToday shouldBe false
    }

    @Test
    fun `latest lianbo title and media date conflict remains visible instead of selecting an older item`() {
        val episode = service.parseLatestResponse(
            officialLianboSample.replace(
                "https://vod.weathertv.cn/video/2026/10/2/202610021790943989883.mp4",
                "https://vod.weathertv.cn/video/2026/10/1/202610011790861189733.mp4"
            ),
            epoch("2026-10-02 21:00")
        )

        episode.id shouldBe "24222"
        episode.identityStatus shouldBe CctvIdentityStatus.CONFLICT
        episode.episodeDate shouldBe null
        episode.isToday shouldBe false
    }

    @Test
    fun `another column or type never becomes the default lianbo episode`() {
        val episode = service.parseLatestResponse(
            """getLbDatas({"data":[{"id":99999,"type":"3M","title":"午间天气预报20261002","url":"https://vod.weathertv.cn/video/2026/10/2/noon.mp4"},{"id":24222,"type":"OTHER","title":"新闻联播天气预报20261002","url":"https://vod.weathertv.cn/video/2026/10/2/other.mp4"}]})""",
            epoch("2026-10-02 21:00")
        )

        episode.identityStatus shouldBe CctvIdentityStatus.UNKNOWN
        episode.id shouldBe ""
        episode.mediaUrl shouldBe null
        episode.url shouldBe CctvWeatherService.DEFAULT_OFFICIAL_HOME_URL
    }

    @Test
    fun `default discovers the official list and labels a failed refresh as failed even with a cache`() {
        var fail = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.request().url.toString() shouldBe ChinaWeatherVideoService.LIANBO_JSONP_URL
            if (fail) throw IOException("Connection unavailable")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(officialLianboSample.toResponseBody()).build()
        }.build()
        val liveService = CctvWeatherService(client)
        val first = liveService.getLatestEpisode().blockingFirst()
        first.id shouldBe "24222"
        first.lookupStatus shouldBe CctvLookupStatus.SUCCESS
        fail = true
        val failed = liveService.getLatestEpisode(forceRefresh = true).blockingFirst()
        failed.id shouldBe "24222"
        failed.lookupStatus shouldBe CctvLookupStatus.FAILED
    }

    @Test
    fun `search parser selects a matching dated program instead of the first result`() {
        val now = epoch("2026-09-22 21:00")
        val episode = service.parseSearchResponse(
            """
            {"list":[
              {"all_title":"普通新闻", "urllink":"https://tv.cctv.com/news.shtml", "uploadtime":"2026-09-22 20:30"},
              {"all_title":"《晚间天气预报》 20260921", "urllink":"https://tv.cctv.com/2026/09/21/VID20260921.shtml", "uploadtime":"2026-09-22 20:45"},
              {"all_title":"《晚间天气预报》 20260922", "urllink":"https://tv.cctv.com/2026/09/22/VID20260922.shtml", "uploadtime":"2026-09-22 20:50"}
            ]}
            """.trimIndent(),
            now
        )

        episode.identityStatus shouldBe CctvIdentityStatus.CONFIRMED_DATE
        episode.episodeDate shouldBe "2026-09-22"
        episode.isToday shouldBe true
        episode.url shouldBe "https://tv.cctv.com/2026/09/22/VID20260922.shtml"
    }

    @Test
    fun `upload time does not masquerade as episode date`() {
        val episode = service.parseSearchResponse(
            """
            {"list":[{"all_title":"《晚间天气预报》 20260921", "urllink":"https://tv.cctv.com/2026/09/21/VID20260921.shtml", "uploadtime":"2026-09-22 20:50"}]}
            """.trimIndent(),
            epoch("2026-09-22 21:00")
        )

        episode.episodeDate shouldBe "2026-09-21"
        episode.isToday shouldBe false
    }

    @Test
    fun `conflicting title and page dates fail closed to official page identity`() {
        val episode = service.parseSearchResponse(
            """
            {"list":[{"all_title":"《晚间天气预报》 20260922", "urllink":"https://tv.cctv.com/2026/09/21/VID20260921.shtml", "uploadtime":"2026-09-22 20:50"}]}
            """.trimIndent(),
            epoch("2026-09-22 21:00")
        )

        episode.identityStatus shouldBe CctvIdentityStatus.CONFLICT
        episode.episodeDate shouldBe null
        episode.url shouldBe "https://tv.cctv.com/2026/09/21/VID20260921.shtml"
    }

    @Test
    fun `no matching program never uses an unrelated first result`() {
        val episode = service.parseSearchResponse(
            """{"list":[{"all_title":"普通新闻", "urllink":"https://tv.cctv.com/news.shtml"}]}""",
            epoch("2026-09-22 21:00")
        )

        episode.identityStatus shouldBe CctvIdentityStatus.UNKNOWN
        episode.episodeDate shouldBe null
        episode.url shouldBe CctvWeatherService.OFFICIAL_HOME_URL
    }

    @Test
    fun `cached identity is recalculated after midnight`() {
        val cached = CctvEpisode(
            id = "episode-20260922",
            title = "《晚间天气预报》 20260922",
            url = "https://tv.cctv.com/2026/09/22/VID20260922.shtml",
            uploadTime = "2026-09-22 20:50",
            coverUrl = "",
            isToday = true,
            episodeDate = "2026-09-22",
            identityStatus = CctvIdentityStatus.CONFIRMED_DATE,
            officialEpisodeUrl = "https://tv.cctv.com/2026/09/22/VID20260922.shtml"
        )

        cached.refreshedFor(epoch("2026-09-23 00:05")).isToday shouldBe false
    }

    private fun epoch(value: String): Long = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }.parse(value)!!.time
}
