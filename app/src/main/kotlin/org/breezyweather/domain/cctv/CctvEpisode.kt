/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.cctv

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class CctvIdentityStatus {
    CONFIRMED_DATE,
    UNKNOWN,
    CONFLICT,
}

enum class CctvLookupStatus {
    SUCCESS,
    FAILED,
}

/**
 * A concrete weather programme item from its named official source.
 * Note on truth authority: [editorialBrief] represents the website's editorial snippet or metadata tag (PARTIAL),
 * not a speech-to-text transcript or official spoken summary.
 */
data class CctvEpisode(
    val id: String,
    val title: String,
    val url: String,
    val uploadTime: String,
    val coverUrl: String,
    val isToday: Boolean,
    val editorialBrief: String? = null,
    /** Episode/program date, never inferred from uploadTime. */
    val episodeDate: String? = null,
    val identityStatus: CctvIdentityStatus = if (episodeDate != null) {
        CctvIdentityStatus.CONFIRMED_DATE
    } else {
        CctvIdentityStatus.UNKNOWN
    },
    val officialEpisodeUrl: String? = null,
    val checkedAtEpochMs: Long = 0L,
    val lookupStatus: CctvLookupStatus = CctvLookupStatus.SUCCESS,
    val sourceId: String = "cctv_official",
    val program: String = "EVENING_WEATHER",
    /** Source catalogue/type, independent of the programme date. */
    val sourceType: String = "",
    val mediaUrl: String? = null,
) {
    val summary: String get() = editorialBrief.orEmpty()
    val sourceIdentityKey: String get() = "$sourceId|$program|$sourceType|$id"

    fun refreshedFor(nowEpochMs: Long): CctvEpisode {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(Date(nowEpochMs))
        return copy(
            isToday = identityStatus == CctvIdentityStatus.CONFIRMED_DATE && episodeDate == today
        )
    }
}
