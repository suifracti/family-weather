/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource.engine

import org.breezyweather.domain.multisource.model.ExtractionMethod
import org.breezyweather.domain.multisource.model.OriginAuthority
import org.breezyweather.domain.multisource.model.PlaybackCandidate
import org.breezyweather.domain.multisource.model.SummaryMetadata
import org.breezyweather.domain.multisource.model.WeatherVideoEpisode

object VideoEvidenceEngine {

    /**
     * Candidate comparator based on objective institutional role, verified resolution, and measurable stream health:
     * 1. Institutional source role (Origin Producer / National Broadcaster > Aggregator)
     * 2. Resolution tier (1080p > 720p > 480p > 270p)
     * 3. Verified playability & stream health
     * 4. Bitrate (kbps)
     * 5. Official subtitle encapsulation
     */
    val candidateComparator: Comparator<PlaybackCandidate> = Comparator { a, b ->
        // 1. Institutional source role authority
        val roleComp = b.sourceRole.authorityRank.compareTo(a.sourceRole.authorityRank)
        if (roleComp != 0) return@Comparator roleComp

        // 2. Verified resolution rank
        val resComp = b.resolution.rank.compareTo(a.resolution.rank)
        if (resComp != 0) return@Comparator resComp

        // 3. Playable health status
        val playA = if (a.healthProfile.isVerifiedPlayable) 1 else 0
        val playB = if (b.healthProfile.isVerifiedPlayable) 1 else 0
        val playComp = playB.compareTo(playA)
        if (playComp != 0) return@Comparator playComp

        // 4. Bitrate (if available)
        val bitA = a.bitrateKbps ?: 0
        val bitB = b.bitrateKbps ?: 0
        val bitComp = bitB.compareTo(bitA)
        if (bitComp != 0) return@Comparator bitComp

        // 5. Official subtitle support
        val subA = if (a.subtitleType.isOfficial) 1 else 0
        val subB = if (b.subtitleType.isOfficial) 1 else 0
        subB.compareTo(subA)
    }

    /**
     * Computes the deduplication key for a video episode:
     * - Daily single-episode programs (e.g. 《晚间天气预报》, 《午间天气预报》): program + episodeDate
     * - Multi-release programs (e.g. 预警视频, 天气速递, 专家解读): program + episodeDate + (guid/publishTime/fingerprint)
     */
    fun computeEpisodeKey(episode: WeatherVideoEpisode): String {
        return if (episode.program.isDailySingleEpisode) {
            "${episode.program.id}:${episode.episodeDate}"
        } else {
            val differentiator = episode.guidOrPid
                ?: episode.titleFingerprint
                ?: episode.publishTime
            "${episode.program.id}:${episode.episodeDate}:${differentiator}"
        }
    }

    /**
     * Deduplicates and merges video episodes.
     * Episodes matching the deduplication key are merged into a single canonical episode,
     * aggregating all playback candidate streams and retaining the most authoritative summary metadata.
     */
    fun deduplicateAndMergeEpisodes(episodes: List<WeatherVideoEpisode>): List<WeatherVideoEpisode> {
        return episodes
            .groupBy { computeEpisodeKey(it) }
            .values
            .map { duplicateGroup ->
                val primary = duplicateGroup.maxByOrNull { it.publishTime } ?: duplicateGroup.first()
                val allCandidates = duplicateGroup.flatMap { it.candidates }.distinctBy { it.playbackUrl }
                val sortedCandidates = allCandidates.sortedWith(candidateComparator)

                // Select the most authoritative summary available in the group
                val bestSummary = duplicateGroup
                    .mapNotNull { it.summaryMetadata }
                    .maxByOrNull { summaryRank(it) }

                WeatherVideoEpisode(
                    episodeId = computeEpisodeKey(primary),
                    program = primary.program,
                    episodeDate = primary.episodeDate,
                    publishTime = primary.publishTime,
                    durationSeconds = primary.durationSeconds,
                    guidOrPid = primary.guidOrPid ?: duplicateGroup.firstNotNullOfOrNull { it.guidOrPid },
                    titleFingerprint = primary.titleFingerprint ?: duplicateGroup.firstNotNullOfOrNull { it.titleFingerprint },
                    sourcePageUrl = primary.sourcePageUrl,
                    coverImageUrl = primary.coverImageUrl ?: duplicateGroup.firstNotNullOfOrNull { it.coverImageUrl },
                    summaryMetadata = bestSummary,
                    candidates = sortedCandidates,
                    bestPlaybackCandidate = sortedCandidates.firstOrNull()
                )
            }
            .sortedWith(compareByDescending<WeatherVideoEpisode> { it.episodeDate }.thenByDescending { it.publishTime })
    }

    /**
     * Ranks candidate playback streams.
     */
    fun rankPlaybackCandidates(candidates: List<PlaybackCandidate>): List<PlaybackCandidate> {
        return candidates.sortedWith(candidateComparator)
    }

    /**
     * Determines summary authority rank based on decoupled origin authority and extraction method.
     */
    private fun summaryRank(meta: SummaryMetadata): Int {
        val originScore = when (meta.originAuthority) {
            OriginAuthority.BROADCAST_AUDIO_PRIMARY -> 30
            OriginAuthority.OFFICIAL_PORTAL_EDITORIAL -> 20
            OriginAuthority.THIRD_PARTY_SYNDICATION -> 10
            OriginAuthority.NONE -> 0
        }
        val methodScore = when (meta.extractionMethod) {
            ExtractionMethod.VERBATIM_OFFICIAL_TRANSCRIPT -> 30
            ExtractionMethod.HUMAN_EDITORIAL_SUMMARY -> 20
            ExtractionMethod.AUTOMATED_ASR_EXTRACTED -> 15
            ExtractionMethod.NONE -> 0
        }
        return originScore + methodScore
    }
}
