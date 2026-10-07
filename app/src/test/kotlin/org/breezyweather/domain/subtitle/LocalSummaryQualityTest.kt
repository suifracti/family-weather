package org.breezyweather.domain.subtitle

import org.breezyweather.domain.subtitle.local.LocalSummaryQuality
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LocalSummaryQualityTest {
    private val source = "国庆假期冷空气活动频繁，各地气温多波动，早晚要注意保暖。预计十月一号二号南北方多地都会陆续经历今年下半年以来最冷的一个早晨。"

    @Test fun acceptsCondensedWeatherWithGroundedDates() {
        assertNull(LocalSummaryQuality.rejection("国庆冷空气频繁，南北方10月1日早晨偏冷，出行注意保暖。", source))
    }

    @Test fun permitsCorrectShortPhrasesFromLongerEvidence() {
        assertNull(LocalSummaryQuality.rejection("国庆假期冷空气活动频繁", source))
    }

    @Test fun rejectsChangingRainDurationToPeakTiming() {
        val rain = "南方的降水将会持续到十月四号其中三号前后贵州到江南雨势较强，出行留意路况，及时排水。"
        assertNotNull(LocalSummaryQuality.rejection("南方降水集中在十月四号前后，注意防洪排水。", rain))
        assertNull(LocalSummaryQuality.rejection("南方降水持续至10月4日，出行留意路况。", rain))
    }

    @Test fun rejectsCopiedEmptyAndDegenerateOutput() {
        assertNotNull(LocalSummaryQuality.rejection(source, source))
        assertNotNull(LocalSummaryQuality.rejection("", source))
        assertNotNull(LocalSummaryQuality.rejection("冷空气冷空气冷空气冷空气，气温波动", source))
    }

    @Test fun rejectsNarrowedTimeAndCertainDisaster() {
        val rain = "今晚到明天四川盆地有大到暴雨，局地大暴雨，需防范山洪和地质灾害。"
        assertNotNull(LocalSummaryQuality.rejection("四川盆地今夜至明晨强降雨，需警惕山洪地质灾害。", rain))
        assertNotNull(LocalSummaryQuality.rejection("四川盆地今夜至明天强降雨，并伴有山洪和地质灾害。", rain))
    }

    @Test fun rejectsInventedDatesRegionsAndRisks() {
        assertNotNull(LocalSummaryQuality.rejection("四川10月5日冷空气频繁，应防范山洪风险。", source))
        assertNotNull(LocalSummaryQuality.rejection("南北方10月5日将迎冷空气，请留意气温变化。", source))
        assertNotNull(LocalSummaryQuality.rejection("国庆冷空气活跃，需防范地质灾害和山洪。", source))
    }
}
