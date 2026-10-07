/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.cctv

import java.net.URI

/**
 * Strict fail-closed URL validator for CCTV official web playback fallback.
 * Prevents arbitrary intent extra URLs from loading into WebView.
 */
object CctvUrlValidator {

    fun isValidOfficialPageUrl(url: String?): Boolean =
        isValidOfficialCctvUrl(url) || isValidChinaWeatherPageUrl(url)

    fun isValidChinaWeatherPageUrl(url: String?): Boolean =
        officialHttpsUri(url)?.host?.lowercase() == "www.weather.com.cn"

    /** The named official catalogue currently supplies this media host only. */
    fun isValidChinaWeatherMediaUrl(url: String?): Boolean {
        val uri = officialHttpsUri(url) ?: return false
        return uri.host.lowercase() == "vod.weathertv.cn" &&
            uri.path.orEmpty().endsWith(".mp4", ignoreCase = true)
    }

    private fun officialHttpsUri(url: String?): URI? {
        if (url.isNullOrBlank()) return null
        val uri = try { URI(url.trim()) } catch (_: Exception) { return null }
        return uri.takeIf {
            it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank() &&
                it.userInfo == null && it.fragment == null && (it.port == -1 || it.port == 443)
        }
    }

    private val ALLOWED_EXACT_HOSTS = setOf(
        "tv.cctv.com",
        "api.cntv.cn",
        "vdn.apps.cntv.cn",
        "weather.cctv.com",
        "news.cctv.com",
        "m.cctv.com"
    )

    private val ALLOWED_DOMAIN_SUFFIXES = listOf(
        ".cctv.com",
        ".cntv.cn"
    )

    /**
     * Validates that the fallback URL is safe, uses HTTPS, and strictly belongs to CCTV official domains.
     *
     * @param url Candidate URL from Intent extras or episode metadata
     * @return true if valid official CCTV HTTPS URL, false otherwise (fail-closed)
     */
    fun isValidOfficialCctvUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()

        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            return false
        }

        // Scheme MUST be strictly https
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "https") return false

        // Host validation
        val host = uri.host?.lowercase() ?: return false
        if (host.isBlank()) return false

        // Prevent username/password injection or domain spoofing (e.g. cctv.com@evil.com)
        if (uri.userInfo != null || host.contains("@")) return false

        if (host in ALLOWED_EXACT_HOSTS) return true

        return ALLOWED_DOMAIN_SUFFIXES.any { suffix ->
            host.endsWith(suffix) && host.length > suffix.length
        }
    }
}
