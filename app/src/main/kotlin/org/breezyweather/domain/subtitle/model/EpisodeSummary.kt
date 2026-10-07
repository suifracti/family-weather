/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.subtitle.model

import kotlinx.serialization.Serializable

/** Short summary bound to one exact video and subtitle artifact; its method declares provenance. */
@Serializable
data class EpisodeSummary(
    val program: String,
    val episodeDate: String,
    val sourceVideoUrl: String,
    val sourceVideoSha256: String,
    val subtitleVttSha256: String,
    val summaryMethod: String,
    val items: List<EpisodeSummaryItem>,
    /** Missing/ambiguous evidence is shown separately, never counted as a weather item. */
    val omissions: List<String> = emptyList(),
    /** Source-bound excerpts, never mixed with the dated main notes. */
    val reviewItems: List<EpisodeSummaryItem> = emptyList(),
    /** Display-only conversion; the literal relative time remains in each evidence field. */
    val dateContextVerified: Boolean = false
)

@Serializable
data class EpisodeSummaryItem(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val supportingCueIds: List<Int> = emptyList(),
    /** Keep the source text for integrity checks, but show a replay prompt when its boundary is unclear. */
    val replayOnly: Boolean = false,
    val sourceText: String? = null,
    /** Character offset in the complete concatenation of supporting cues. */
    val sourceOffset: Int = 0,
    val region: SummaryEvidenceField? = null,
    val time: SummaryEvidenceField? = null,
    val weather: SummaryEvidenceField? = null,
    val advice: SummaryEvidenceField? = null,
    /** Other regional assertions of this same event, with spans in this item’s sourceText. */
    val relatedForecasts: List<SummaryForecastEvidence> = emptyList(),
    val reviewReasons: List<String> = emptyList()
)

@Serializable
data class SummaryEvidenceField(val value: String, val start: Int, val end: Int)

/** Separate assertions prevent a regional severity from being flattened onto the whole event. */
@Serializable
data class SummaryForecastEvidence(
    val region: SummaryEvidenceField,
    val time: SummaryEvidenceField,
    val weather: SummaryEvidenceField,
    val advice: SummaryEvidenceField? = null
)
