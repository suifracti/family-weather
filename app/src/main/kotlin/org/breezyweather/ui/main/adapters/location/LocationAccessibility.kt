/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.ui.main.adapters.location

import breezyweather.domain.location.model.Location
import breezyweather.domain.location.model.OrchardLocationPolicy

object LocationAccessibility {
    fun deletionHint(location: Location, deletableHint: String): String =
        if (OrchardLocationPolicy.canDelete(location)) deletableHint else "工作地 / 果园，不可删除"
}
