/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.subtitle

import android.content.Intent
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.breezyweather.domain.subtitle.config.SubtitleConfig
import org.junit.jupiter.api.Test

class SubtitleConfigTest {

    @Test
    fun `test release empty base URL returns empty string fail-open`() {
        val result = SubtitleConfig.resolveBaseUrlInternal(
            isDebug = false,
            configUrl = "",
            intent = null
        )
        result shouldBe ""
    }

    @Test
    fun `test release non-HTTPS URL returns empty string fail-closed`() {
        val result = SubtitleConfig.resolveBaseUrlInternal(
            isDebug = false,
            configUrl = "http://subtitles.example.com/api",
            intent = null
        )
        result shouldBe ""
    }

    @Test
    fun `test release valid HTTPS URL returns trimmed base URL`() {
        val result = SubtitleConfig.resolveBaseUrlInternal(
            isDebug = false,
            configUrl = "https://subtitles.example.com/api/",
            intent = null
        )
        result shouldBe "https://subtitles.example.com/api"
    }

    @Test
    fun `test release ignores debug intent override completely`() {
        val mockIntent = mockk<Intent>()
        every { mockIntent.getStringExtra(SubtitleConfig.EXTRA_DEBUG_SUBTITLE_BASE_URL) } returns "http://127.0.0.1:8888"

        // Release build must NOT expose debug localhost override
        val resultEmptyConfig = SubtitleConfig.resolveBaseUrlInternal(
            isDebug = false,
            configUrl = "",
            intent = mockIntent
        )
        resultEmptyConfig shouldBe ""

        // Release build keeps HTTPS production URL even if debug intent is passed
        val resultHttpsConfig = SubtitleConfig.resolveBaseUrlInternal(
            isDebug = false,
            configUrl = "https://subtitles.example.com/api",
            intent = mockIntent
        )
        resultHttpsConfig shouldBe "https://subtitles.example.com/api"
    }

    @Test
    fun `test debug allows localhost HTTP intent override`() {
        val mockIntent = mockk<Intent>()
        every { mockIntent.getStringExtra(SubtitleConfig.EXTRA_DEBUG_SUBTITLE_BASE_URL) } returns "http://127.0.0.1:8888/"

        val result = SubtitleConfig.resolveBaseUrlInternal(
            isDebug = true,
            configUrl = "",
            intent = mockIntent
        )
        result shouldBe "http://127.0.0.1:8888"
    }
}
