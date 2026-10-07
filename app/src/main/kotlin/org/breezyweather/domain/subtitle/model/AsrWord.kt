package org.breezyweather.domain.subtitle.model

import kotlinx.serialization.Serializable

/** Uncorrected recognizer output. Confidence indicates risk, never verified accuracy. */
@Serializable
data class AsrWord(val word: String, val startMs: Long, val endMs: Long, val confidence: Double?,
                   val endEstimatedFromNextToken: Boolean = false)
