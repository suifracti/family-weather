/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.subtitle.gate

import org.breezyweather.domain.subtitle.model.SubtitleManifest
import kotlin.math.abs

/**
 * Result outcomes of Media Identity Gate evaluations.
 */
sealed interface SubtitleMediaIdentityResult {
    data object Verified : SubtitleMediaIdentityResult
    data class Mismatch(val reason: String) : SubtitleMediaIdentityResult
}

/**
 * Gatekeeper enforcing exact episode identity and media duration tolerance before mounting subtitles.
 *
 * Invariants:
 * 1. Exact program identity (case- and slug-insensitive, e.g. EVENING_WEATHER vs evening-weather).
 * 2. Exact episode date identity (e.g. 2026-09-17).
 * 3. Exact source video URL match with currently playing media URL.
 * 4. Duration tolerance check (abs(player.duration - manifest.durationMs) <= 1000ms).
 * 5. Publisher provenance for video hash: mobile client verifies at publishing time and does NOT
 *    download 50MB video to rehash on-device.
 */
object SubtitleMediaIdentityGate {

    /**
     * Tolerance window between player duration and manifest declared duration.
     */
    const val MEDIA_DURATION_TOLERANCE_MS: Long = 1000L

    /**
     * Source video SHA256 is publisher provenance only.
     * Re-downloading and rehashing 50MB video on mobile devices is strictly avoided.
     */
    const val PUBLISHER_PROVENANCE_ONLY: Boolean = true

    /**
     * Normalizes program strings for comparison.
     */
    fun normalizeProgram(program: String): String {
        return program.trim().uppercase().replace('-', '_')
    }

    /**
     * Pre-mount verification of program, episode date, and source video URL.
     */
    fun verifyPreMountIdentity(
        manifest: SubtitleManifest,
        currentProgram: String,
        currentEpisodeDate: String,
        actuallyPlayingMediaUrl: String
    ): SubtitleMediaIdentityResult {
        val normManifestProg = normalizeProgram(manifest.program)
        val normCurrentProg = normalizeProgram(currentProgram)

        if (normManifestProg != normCurrentProg) {
            return SubtitleMediaIdentityResult.Mismatch(
                "Program mismatch: expected '$normCurrentProg', got '$normManifestProg'"
            )
        }

        if (manifest.episodeDate != currentEpisodeDate) {
            return SubtitleMediaIdentityResult.Mismatch(
                "Episode date mismatch: expected '$currentEpisodeDate', got '${manifest.episodeDate}'"
            )
        }

        if (actuallyPlayingMediaUrl.isNotBlank() && manifest.sourceVideoUrl != actuallyPlayingMediaUrl) {
            return SubtitleMediaIdentityResult.Mismatch(
                "Source video URL mismatch: expected '$actuallyPlayingMediaUrl', got '${manifest.sourceVideoUrl}'"
            )
        }

        return SubtitleMediaIdentityResult.Verified
    }

    /**
     * Post-STATE_READY verification of duration tolerance.
     * Boundary rule: abs(playerDuration - manifestDuration) <= 1000ms.
     * 999ms -> Pass
     * 1000ms -> Pass
     * 1001ms -> Reject
     */
    fun verifyDurationTolerance(
        manifestDurationMs: Long,
        playerDurationMs: Long,
        toleranceMs: Long = MEDIA_DURATION_TOLERANCE_MS
    ): SubtitleMediaIdentityResult {
        if (manifestDurationMs <= 0L || playerDurationMs <= 0L) {
            // Indeterminate duration should not cause false-positive rejection
            return SubtitleMediaIdentityResult.Verified
        }

        val diff = abs(playerDurationMs - manifestDurationMs)
        if (diff > toleranceMs) {
            return SubtitleMediaIdentityResult.Mismatch(
                "Duration mismatch: player duration ($playerDurationMs ms) vs manifest duration ($manifestDurationMs ms), diff=$diff ms exceeds tolerance=$toleranceMs ms"
            )
        }

        return SubtitleMediaIdentityResult.Verified
    }
}
