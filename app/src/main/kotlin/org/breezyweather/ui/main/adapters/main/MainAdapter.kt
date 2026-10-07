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

package org.breezyweather.ui.main.adapters.main

import org.breezyweather.domain.weather.model.alertsFromAvailableSource

import android.animation.Animator
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import breezyweather.domain.location.model.Location
import org.breezyweather.common.extensions.toCalendarWithTimeZone
import org.breezyweather.common.options.appearance.CardDisplay
import org.breezyweather.domain.settings.SettingsManager
import breezyweather.domain.location.model.OrchardLocationPolicy
import org.breezyweather.domain.weather.model.hasMinutelyPrecipitation
import org.breezyweather.domain.weather.model.isIndexValid
import org.breezyweather.domain.weather.model.validAirQuality
import org.breezyweather.ui.main.MainActivity
import org.breezyweather.ui.main.adapters.main.holder.AbstractMainCardViewHolder
import org.breezyweather.ui.main.adapters.main.holder.AbstractMainViewHolder
import org.breezyweather.ui.main.adapters.main.holder.AirQualityViewHolder
import org.breezyweather.ui.main.adapters.main.holder.AlertViewHolder
import org.breezyweather.ui.main.adapters.main.holder.CctvWeatherViewHolder
import org.breezyweather.ui.main.adapters.main.holder.TomorrowRainViewHolder
import org.breezyweather.ui.main.adapters.main.holder.MoreWeatherViewHolder
import org.breezyweather.ui.main.adapters.main.holder.OrchardTemperatureViewHolder
import org.breezyweather.ui.main.adapters.main.holder.ClockViewHolder
import org.breezyweather.ui.main.adapters.main.holder.DailyViewHolder
import org.breezyweather.ui.main.adapters.main.holder.FooterViewHolder
import org.breezyweather.ui.main.adapters.main.holder.HeaderViewHolder
import org.breezyweather.ui.main.adapters.main.holder.HourlyViewHolder
import org.breezyweather.ui.main.adapters.main.holder.HumidityViewHolder
import org.breezyweather.ui.main.adapters.main.holder.MoonViewHolder
import org.breezyweather.ui.main.adapters.main.holder.PollenViewHolder
import org.breezyweather.ui.main.adapters.main.holder.PrecipitationNowcastViewHolder
import org.breezyweather.ui.main.adapters.main.holder.PrecipitationViewHolder
import org.breezyweather.ui.main.adapters.main.holder.PressureViewHolder
import org.breezyweather.ui.main.adapters.main.holder.SunViewHolder
import org.breezyweather.ui.main.adapters.main.holder.UvViewHolder
import org.breezyweather.ui.main.adapters.main.holder.VisibilityViewHolder
import org.breezyweather.ui.main.adapters.main.holder.WindViewHolder
import org.breezyweather.ui.theme.resource.providers.ResourceProvider
import org.breezyweather.ui.theme.weatherView.WeatherView
import java.util.Calendar
import java.util.Collections
import java.util.Date

class MainAdapter(
    activity: MainActivity,
    host: RecyclerView,
    weatherView: WeatherView,
    location: Location?,
    provider: ResourceProvider,
    listAnimationEnabled: Boolean,
    itemAnimationEnabled: Boolean,
) : RecyclerView.Adapter<AbstractMainViewHolder?>() {
    private lateinit var mActivity: MainActivity
    private var mHost: RecyclerView? = null
    private var mWeatherView: WeatherView? = null
    private var mLocation: Location? = null
    private var mProvider: ResourceProvider? = null
    private val mViewTypeList: MutableList<Int> = mutableListOf()
    private val mViewHasAnimatedList: MutableList<Boolean> = mutableListOf()
    private var mPendingAnimatorList: MutableList<Animator>? = null
    private var mHeaderCurrentTemperatureTextHeight = 0
    private var mListAnimationEnabled = false
    private var mItemAnimationEnabled = false
    private var selectedDailyTab: String? = null
    private var selectedHourlyTab: String? = null

    init {
        update(activity, host, weatherView, location, provider, listAnimationEnabled, itemAnimationEnabled)
    }

    fun update(
        activity: MainActivity,
        host: RecyclerView,
        weatherView: WeatherView,
        location: Location?,
        provider: ResourceProvider,
        listAnimationEnabled: Boolean,
        itemAnimationEnabled: Boolean,
    ) {
        mActivity = activity
        mHost = host
        mWeatherView = weatherView
        mLocation = location
        mProvider = provider
        mPendingAnimatorList = mutableListOf()
        mHeaderCurrentTemperatureTextHeight = -1
        mListAnimationEnabled = listAnimationEnabled
        mItemAnimationEnabled = itemAnimationEnabled
        populateViewTypes()
    }

    var isMoreWeatherExpanded: Boolean = false

    private fun populateViewTypes() {
        mViewTypeList.clear()
        val location = mLocation ?: return
        val weather = location.weather
        if (location.alertsFromAvailableSource().any { it.endDate == null || it.endDate!!.time > Date().time } == true) {
            mViewTypeList.add(ViewType.ALERT)
        }
        // The programme brief is global. Orchard rain and temperature cards are
        // shown only while the orchard is the selected location.
        mViewTypeList.add(ViewType.CCTV_WEATHER)
        val isSelectedOrchard = OrchardLocationPolicy.shouldShowOrchardCards(location)
        if (isSelectedOrchard) {
            mViewTypeList.add(ViewType.TOMORROW_RAIN)
            mViewTypeList.add(ViewType.ORCHARD_TEMPERATURE)
        }

        if (weather != null) {
            mViewTypeList.add(ViewType.MORE_WEATHER_ENTRY)
        }
        if (isMoreWeatherExpanded && weather != null) {
            mViewTypeList.add(ViewType.HEADER)
            if (weather?.nextHourlyForecast?.isNotEmpty() == true) {
                mViewTypeList.add(ViewType.HOURLY)
            }
            if (weather?.dailyForecast?.isNotEmpty() == true) {
                mViewTypeList.add(ViewType.DAILY)
            }
        }
        mViewTypeList.add(ViewType.FOOTER)
        mViewHasAnimatedList.clear()
        for (i in 0..mViewTypeList.size) {
            mViewHasAnimatedList.add(false)
        }
    }

    fun setNullWeather() {
        mViewHasAnimatedList.clear()
        mViewTypeList.clear()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AbstractMainViewHolder = when (viewType) {
        ViewType.HEADER -> HeaderViewHolder(parent)
        ViewType.ALERT -> AlertViewHolder(parent)
        ViewType.PRECIPITATION_NOWCAST -> PrecipitationNowcastViewHolder(parent)
        ViewType.DAILY -> DailyViewHolder(parent)
        ViewType.HOURLY -> HourlyViewHolder(parent)
        ViewType.PRECIPITATION -> PrecipitationViewHolder(parent)
        ViewType.WIND -> WindViewHolder(parent)
        ViewType.HUMIDITY -> HumidityViewHolder(parent)
        ViewType.UV -> UvViewHolder(parent)
        ViewType.AIR_QUALITY -> AirQualityViewHolder(parent)
        ViewType.POLLEN -> PollenViewHolder(parent)
        ViewType.VISIBILITY -> VisibilityViewHolder(parent)
        ViewType.PRESSURE -> PressureViewHolder(parent)
        ViewType.SUN -> SunViewHolder(parent)
        ViewType.MOON -> MoonViewHolder(parent)
        ViewType.CLOCK -> ClockViewHolder(parent)
        ViewType.TOMORROW_RAIN -> TomorrowRainViewHolder(parent)
        ViewType.CCTV_WEATHER -> CctvWeatherViewHolder(parent)
        ViewType.ORCHARD_TEMPERATURE -> OrchardTemperatureViewHolder(parent)
        ViewType.MORE_WEATHER_ENTRY -> MoreWeatherViewHolder(parent) {
            isMoreWeatherExpanded = !isMoreWeatherExpanded
            populateViewTypes()
            notifyDataSetChanged()
        }
        ViewType.FOOTER -> FooterViewHolder(ComposeView(parent.context))
        else -> FooterViewHolder(ComposeView(parent.context))
    }

    override fun onBindViewHolder(holder: AbstractMainViewHolder, position: Int) {
        if (holder is MoreWeatherViewHolder) {
            val location = mLocation
            holder.bind(
                isExpanded = isMoreWeatherExpanded
            )
            return
        }
        mLocation?.let {
            if (holder is AbstractMainCardViewHolder) {
                if (holder is DailyViewHolder || holder is HourlyViewHolder) {
                    holder.onBindView(
                        mActivity,
                        mLocation!!,
                        mProvider!!,
                        mListAnimationEnabled && mViewHasAnimatedList.getOrElse(position) { null } != true,
                        mItemAnimationEnabled && mViewHasAnimatedList.getOrElse(position) { null } != true,
                        if (holder is HourlyViewHolder) selectedHourlyTab else selectedDailyTab,
                        if (holder is HourlyViewHolder) {
                            { tab -> selectedHourlyTab = tab }
                        } else {
                            { tab -> selectedDailyTab = tab }
                        }
                    )
                } else {
                    holder.onBindView(
                        mActivity,
                        mLocation!!,
                        mProvider!!,
                        mListAnimationEnabled && mViewHasAnimatedList.getOrElse(position) { null } != true,
                        mItemAnimationEnabled && mViewHasAnimatedList.getOrElse(position) { null } != true
                    )
                }
            } else {
                holder.onBindView(
                    mActivity,
                    mLocation!!,
                    mProvider!!,
                    mListAnimationEnabled && mViewHasAnimatedList.getOrElse(position) { null } != true,
                    mItemAnimationEnabled && mViewHasAnimatedList.getOrElse(position) { null } != true
                )
            }
            mHost!!.post {
                holder.checkEnterScreen(
                    mHost!!,
                    mPendingAnimatorList ?: ArrayList(),
                    mListAnimationEnabled && mViewHasAnimatedList.getOrElse(position) { null } != true
                )
            }
        }
    }

    override fun onViewRecycled(holder: AbstractMainViewHolder) {
        holder.onRecycleView()
    }

    override fun getItemCount() = mViewTypeList.size

    override fun getItemViewType(position: Int) = mViewTypeList[position]

    fun onScroll() {
        for (i in 0 until itemCount) {
            val holder = mHost!!.findViewHolderForAdapterPosition(i) as AbstractMainViewHolder?
            holder?.checkEnterScreen(
                mHost!!,
                mPendingAnimatorList ?: ArrayList(),
                mListAnimationEnabled && mViewHasAnimatedList.getOrElse(i) { null } != true
            )?.let { hasAnimated ->
                if (hasAnimated) {
                    mViewHasAnimatedList[i] = true
                }
            }
        }
    }

    fun isDraggable(position: Int): Boolean {
        return mViewTypeList[position] !in arrayOf(
            ViewType.HEADER,
            ViewType.ALERT,
            ViewType.FOOTER,

            // TODO: #1972 - Remove below when we have figured out how to avoid the touch conflict with the chart
            ViewType.DAILY,
            ViewType.HOURLY,
            ViewType.PRECIPITATION_NOWCAST,
            ViewType.TOMORROW_RAIN,
            ViewType.ORCHARD_TEMPERATURE,
            ViewType.CCTV_WEATHER,
            ViewType.MORE_WEATHER_ENTRY
        )
    }

    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        val fromCard = getCardDisplay(getItemViewType(fromPosition))
        val toCard = getCardDisplay(getItemViewType(toPosition))

        if (fromCard == null || toCard == null) {
            // LogHelper.log(msg = "[Main screen Drag & Drop] No matching cards")
            return false
        }

        val cardDisplayList = SettingsManager.getInstance(mActivity).cardDisplayList
        val fromCardPosition = cardDisplayList.indexOf(fromCard)
        val toCardPosition = cardDisplayList.indexOf(toCard)

        if (fromCardPosition == -1 || toCardPosition == -1) {
            // LogHelper.log(msg = "[Main screen Drag & Drop] Can’t find one of the two cards positions")
            return false
        }

        Collections.swap(mViewTypeList, fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)

        // LogHelper.log(msg = "[Main screen Drag & Drop] Before: ${CardDisplay.toValue(cardDisplayList)}")
        Collections.swap(cardDisplayList, fromCardPosition, toCardPosition)
        // LogHelper.log(msg = "[Main screen Drag & Drop] After: ${CardDisplay.toValue(cardDisplayList)}")
        SettingsManager.getInstance(mActivity).cardDisplayList = cardDisplayList

        return true
    }

    val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            return makeMovementFlags(
                if (isDraggable(viewHolder.layoutPosition)) {
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                } else {
                    0
                },
                0
            )
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            if (!isDraggable(target.layoutPosition)) {
                return false
            }

            return onItemMove(viewHolder.layoutPosition, target.layoutPosition)
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
    })

    companion object {
        private fun getViewType(cardDisplay: CardDisplay): Int = when (cardDisplay) {
            CardDisplay.CARD_NOWCAST -> ViewType.PRECIPITATION_NOWCAST
            CardDisplay.CARD_DAILY_FORECAST -> ViewType.DAILY
            CardDisplay.CARD_HOURLY_FORECAST -> ViewType.HOURLY
            CardDisplay.CARD_PRECIPITATION -> ViewType.PRECIPITATION
            CardDisplay.CARD_WIND -> ViewType.WIND
            CardDisplay.CARD_AIR_QUALITY -> ViewType.AIR_QUALITY
            CardDisplay.CARD_POLLEN -> ViewType.POLLEN
            CardDisplay.CARD_HUMIDITY -> ViewType.HUMIDITY
            CardDisplay.CARD_UV -> ViewType.UV
            CardDisplay.CARD_VISIBILITY -> ViewType.VISIBILITY
            CardDisplay.CARD_PRESSURE -> ViewType.PRESSURE
            CardDisplay.CARD_SUN -> ViewType.SUN
            CardDisplay.CARD_MOON -> ViewType.MOON
            CardDisplay.CARD_CLOCK -> ViewType.CLOCK
            CardDisplay.CARD_TOMORROW_RAIN -> ViewType.TOMORROW_RAIN
            CardDisplay.CARD_CCTV_WEATHER -> ViewType.CCTV_WEATHER
        }

        private fun getCardDisplay(viewType: Int): CardDisplay? = when (viewType) {
            ViewType.PRECIPITATION_NOWCAST -> CardDisplay.CARD_NOWCAST
            ViewType.DAILY -> CardDisplay.CARD_DAILY_FORECAST
            ViewType.HOURLY -> CardDisplay.CARD_HOURLY_FORECAST
            ViewType.PRECIPITATION -> CardDisplay.CARD_PRECIPITATION
            ViewType.WIND -> CardDisplay.CARD_WIND
            ViewType.AIR_QUALITY -> CardDisplay.CARD_AIR_QUALITY
            ViewType.POLLEN -> CardDisplay.CARD_POLLEN
            ViewType.HUMIDITY -> CardDisplay.CARD_HUMIDITY
            ViewType.UV -> CardDisplay.CARD_UV
            ViewType.VISIBILITY -> CardDisplay.CARD_VISIBILITY
            ViewType.PRESSURE -> CardDisplay.CARD_PRESSURE
            ViewType.SUN -> CardDisplay.CARD_SUN
            ViewType.MOON -> CardDisplay.CARD_MOON
            ViewType.CLOCK -> CardDisplay.CARD_CLOCK
            ViewType.TOMORROW_RAIN -> CardDisplay.CARD_TOMORROW_RAIN
            ViewType.CCTV_WEATHER -> CardDisplay.CARD_CCTV_WEATHER
            else -> null
        }
    }
}
