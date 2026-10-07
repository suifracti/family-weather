/*
 * This file is part of Breezy Weather.
 *
 * Breezy Weather is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, version 3 of the License.
 *
 * Breezy Weather is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Breezy Weather. If not, see <https://www.gnu.org/licenses/>.
 */

package org.breezyweather.ui.details

import android.os.Bundle
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.AndroidEntryPoint
import org.breezyweather.common.activities.BreezyActivity
import org.breezyweather.ui.main.adapters.main.OrchardTemperaturePresentation

val LocalOrchardTemperaturePresentation = staticCompositionLocalOf { false }

@AndroidEntryPoint
class DetailsActivity : BreezyActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Only the orchard card opts in; global language and units are never written.
        val orchardPresentation = intent.getBooleanExtra(KEY_ORCHARD_TEMPERATURE_PRESENTATION, false)
        val displayContext = if (orchardPresentation) {
            val configuration = Configuration(resources.configuration).apply {
                setLocale(OrchardTemperaturePresentation.locale)
            }
            ContextThemeWrapper(this, theme).apply { applyOverrideConfiguration(configuration) }
        } else {
            this
        }
        setContent {
            CompositionLocalProvider(
                LocalContext provides displayContext,
                LocalConfiguration provides displayContext.resources.configuration,
                LocalOrchardTemperaturePresentation provides orchardPresentation,
            ) {
                DailyWeatherScreen(onBackPressed = { finish() })
            }
        }
    }

    companion object {
        const val KEY_FORMATTED_LOCATION_ID = "FORMATTED_LOCATION_ID"
        const val KEY_CURRENT_DAILY_INDEX = "CURRENT_DAILY_INDEX"
        const val KEY_CURRENT_PAGE = "CURRENT_PAGE"
        const val KEY_ORCHARD_TEMPERATURE_PRESENTATION = "ORCHARD_TEMPERATURE_PRESENTATION"
    }
}
