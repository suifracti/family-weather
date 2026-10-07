package org.breezyweather.sources.openmeteo

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OpenMeteoModelMigrationTest {
    @Test fun `equivalent old choices resolve to actual new request ids`() {
        val aliases = mapOf("gfs_seamless" to "ncep_gfs_seamless", "gfs_global" to "ncep_gfs_global",
            "gfs_hrrr" to "ncep_hrrr_conus", "icon_global" to "dwd_icon_global",
            "icon_seamless" to "dwd_icon_seamless", "icon_eu" to "dwd_icon_eu", "icon_d2" to "dwd_icon_d2",
            "gem_global" to "cmc_gem_gdps", "gem_seamless" to "cmc_gem_seamless",
            "gem_regional" to "cmc_gem_rdps", "gem_hrdps_continental" to "cmc_gem_hrdps")
        aliases.forEach { (old, actual) -> assertEquals(actual, OpenMeteoWeatherModel.getInstance(old)?.id) }
    }
    @Test fun `GraphCast and unknown ids cannot silently select another model`() {
        assertNull(OpenMeteoWeatherModel.getInstance("gfs_graphcast025"))
        assertNull(OpenMeteoWeatherModel.getInstance("unrecognized_future_model"))
        assertEquals("best_match", OpenMeteoWeatherModel.getInstance("best_match")?.id)
    }
}
