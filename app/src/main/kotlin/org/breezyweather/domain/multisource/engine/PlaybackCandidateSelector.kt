/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource.engine

import org.breezyweather.domain.multisource.model.PlaybackCandidate
import org.breezyweather.domain.multisource.model.WeatherVideoEpisode

/**
 * Robust candidate selector that dynamically handles playback failures
 * and gracefully falls back to secondary sources (e.g. CCTV HLS or WebView).
 */
object PlaybackCandidateSelector {

    /**
     * Selects the highest-ranked playback candidate that has not been marked as failed.
     *
     * @param candidates available playback candidates
     * @param failedCandidateIds candidate IDs that encountered runtime errors or timeouts
     * @return the best remaining candidate, or null if all candidates failed
     */
    fun selectCandidate(
        candidates: List<PlaybackCandidate>,
        failedCandidateIds: Set<String> = emptySet()
    ): PlaybackCandidate? {
        val ranked = VideoEvidenceEngine.rankPlaybackCandidates(candidates)
        return ranked.firstOrNull { it.candidateId !in failedCandidateIds }
    }

    /**
     * Convenience method to select the best candidate from a canonical episode.
     */
    fun selectCandidate(
        episode: WeatherVideoEpisode,
        failedCandidateIds: Set<String> = emptySet()
    ): PlaybackCandidate? {
        return selectCandidate(episode.candidates, failedCandidateIds)
    }
}
