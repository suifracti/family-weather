/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.ui.main

import android.content.Context
import org.breezyweather.domain.multisource.hourly.presentation.TomorrowRainSnapshot
import org.breezyweather.domain.multisource.hourly.service.TomorrowRainSnapshotStore
import org.breezyweather.domain.multisource.location.LocationAuthority

/**
 * Short-lived hand-off from the home rain card to the source comparison page.
 * The page must render the same immutable snapshot that produced the home
 * values; it must not issue a second weather request or recompute a metric.
 */
object TomorrowRainEvidenceSession {
    @Volatile
    var snapshot: TomorrowRainSnapshot? = null

    @Volatile
    var selectedSlotIndex: Int = 0

    fun open(snapshot: TomorrowRainSnapshot, selectedSlotIndex: Int) {
        this.snapshot = snapshot
        this.selectedSlotIndex = selectedSlotIndex
    }

    fun resolve(
        context: Context,
        expectedLocationId: String?,
        expectedTargetDate: String?,
    ): TomorrowRainSnapshot? {
        if (expectedLocationId.isNullOrBlank() || expectedTargetDate.isNullOrBlank()) return null
        snapshot?.takeIf {
            it.targetLocation.canonicalLocationId == expectedLocationId &&
                it.targetDateLocal == expectedTargetDate
        }?.let { return it }

        val orchard = LocationAuthority.getAgriculturalPrimary()
        if (orchard.canonicalLocationId != expectedLocationId) return null
        return TomorrowRainSnapshotStore.from(context)
            .load(orchard, expectedTargetDate)
            ?.also { snapshot = it }
    }
}
