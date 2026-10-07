package org.breezyweather.ui.main.adapters.main.holder

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import breezyweather.domain.location.model.Location
import org.breezyweather.R
import org.breezyweather.ui.theme.resource.providers.ResourceProvider

class MoreWeatherViewHolder(parent: ViewGroup, private val onToggleClick: () -> Unit) :
    AbstractMainViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.container_main_more_weather_entry, parent, false)
    ) {

    private val titleText: TextView = itemView.findViewById(R.id.more_weather_title)
    private val subtitleText: TextView = itemView.findViewById(R.id.more_weather_subtitle)
    private val arrowIcon: ImageView = itemView.findViewById(R.id.more_weather_arrow)

    fun bind(isExpanded: Boolean, isAvailable: Boolean = true) {
        if (!isAvailable) {
            titleText.text = "普通天气数据暂不可用"
            subtitleText.text = "天气加载后可查看逐小时与逐日预报"
            arrowIcon.setImageResource(R.drawable.ic_arrow_downward_alt)
            itemView.setOnClickListener(null)
            itemView.isClickable = false
            return
        }
        if (isExpanded) {
            titleText.text = "收起更多天气"
            subtitleText.text = "收起当前气温、逐小时与未来7天折线"
            arrowIcon.setImageResource(R.drawable.ic_arrow_upward_alt)
        } else {
            titleText.text = "查看更多天气"
            subtitleText.text = "当前气温、24小时折线、未来7天预报"
            arrowIcon.setImageResource(R.drawable.ic_arrow_downward_alt)
        }
        itemView.setOnClickListener {
            onToggleClick()
        }
        itemView.isClickable = true
    }

    override fun onBindView(
        context: Context,
        location: Location,
        provider: ResourceProvider,
        listAnimationEnabled: Boolean,
        itemAnimationEnabled: Boolean,
    ) {
        super.onBindView(context, location, provider, listAnimationEnabled, itemAnimationEnabled)
    }
}
