/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.subtitle.config

import android.content.Intent
import org.breezyweather.BuildConfig

/**
 * Configuration holder and resolver for ASR Subtitle delivery.
 *
 * Base URL is injected from BuildConfig.SUBTITLE_BASE_URL (or local configuration).
 * An empty or blank URL represents SUBTITLE_SERVICE_NOT_CONFIGURED.
 *
 * Security policy:
 * - Release builds strictly require HTTPS URLs and ignore debug intent overrides.
 * - Debug builds allow intent overrides (e.g. EXTRA_DEBUG_SUBTITLE_BASE_URL) and HTTP local test servers.
 */
object SubtitleConfig {

    const val EXTRA_DEBUG_SUBTITLE_BASE_URL = "extra_debug_subtitle_base_url"

    /**
     * Resolves the effective base URL.
     * Returns an empty string if unconfigured or rejected by security policy.
     */
    fun resolveBaseUrl(intent: Intent? = null): String {
        return resolveBaseUrlInternal(
            isDebug = BuildConfig.DEBUG,
            configUrl = BuildConfig.SUBTITLE_BASE_URL,
            intent = intent
        )
    }

    internal fun resolveBaseUrlInternal(
        isDebug: Boolean,
        configUrl: String,
        intent: Intent? = null
    ): String {
        val debugOverride = if (isDebug) {
            intent?.getStringExtra(EXTRA_DEBUG_SUBTITLE_BASE_URL)?.trim()
        } else {
            null
        }

        val rawUrl = if (!debugOverride.isNullOrBlank()) {
            debugOverride
        } else {
            configUrl.trim()
        }

        if (rawUrl.isBlank()) {
            return ""
        }

        // Release security policy: enforce HTTPS
        if (!isDebug && !rawUrl.startsWith("https://", ignoreCase = true)) {
            return ""
        }

        return rawUrl.trimEnd('/')
    }

    /**
     * Returns true if the service is configured with a valid base URL.
     */
    fun isConfigured(intent: Intent? = null): Boolean {
        return resolveBaseUrl(intent).isNotBlank()
    }
}
