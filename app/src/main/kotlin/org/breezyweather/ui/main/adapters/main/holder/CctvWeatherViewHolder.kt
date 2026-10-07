/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.ui.main.adapters.main.holder

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.button.MaterialButton
import breezyweather.domain.location.model.Location
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import org.breezyweather.R
import org.breezyweather.common.activities.BreezyActivity
import org.breezyweather.domain.cctv.CctvEpisode
import org.breezyweather.domain.cctv.CctvIdentityStatus
import org.breezyweather.domain.cctv.CctvLookupStatus
import org.breezyweather.domain.cctv.CctvWeatherService
import org.breezyweather.domain.subtitle.local.EpisodePreparationCoordinator
import org.breezyweather.domain.subtitle.local.EpisodePreparationRequest
import org.breezyweather.ui.cctv.CctvPlayerActivity
import org.breezyweather.ui.theme.resource.providers.ResourceProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class CctvWeatherViewHolder(parent: ViewGroup) : AbstractMainCardViewHolder(
    LayoutInflater.from(parent.context).inflate(R.layout.container_main_cctv_weather_card, parent, false)
) {
    private val cctvStatusBadge: TextView = itemView.findViewById(R.id.cctv_status_badge)

    // Un-updated state views
    private val unupdatedContainer: View = itemView.findViewById(R.id.cctv_unupdated_container)
    private val cctvCheckTimestamp: TextView = itemView.findViewById(R.id.cctv_check_timestamp)
    private val cctvHistoryStat: TextView = itemView.findViewById(R.id.cctv_history_stat)
    private val btnPlayPrevious: MaterialButton = itemView.findViewById(R.id.btn_play_previous)

    // Updated state views
    private val updatedContainer: View = itemView.findViewById(R.id.cctv_updated_container)
    private val cctvUpdateTime: TextView = itemView.findViewById(R.id.cctv_update_time)
    private val cctvSummaryHeadline: TextView = itemView.findViewById(R.id.cctv_summary_headline)
    private val btnPlayCctv: MaterialButton = itemView.findViewById(R.id.btn_play_cctv)

    private var mDisposable: Disposable? = null
    private val preparationContainer: View = itemView.findViewById(R.id.cctv_preparation_container)
    private val preparationStatus: TextView = itemView.findViewById(R.id.cctv_preparation_status)
    private val preparationDetail: TextView = itemView.findViewById(R.id.cctv_preparation_detail)
    private val prepareButton: MaterialButton = itemView.findViewById(R.id.btn_prepare_cctv)
    private val cancelPreparationButton: MaterialButton = itemView.findViewById(R.id.btn_cancel_prepare_cctv)
    private val fallbackButton = MaterialButton(itemView.context).apply {
        text = "改用旧版 Vosk 字幕（可能错词）"
        visibility = View.GONE
        (preparationContainer as android.widget.LinearLayout).addView(this)
    }
    private var preparationLease: EpisodePreparationCoordinator.Lease? = null
    private var preparationObserver: Job? = null
    private var preparationLookup: Job? = null
    private var preparationRequest: EpisodePreparationRequest? = null

    @SuppressLint("SetTextI18n")
    override fun onBindView(
        activity: BreezyActivity,
        location: Location,
        provider: ResourceProvider,
        listAnimationEnabled: Boolean,
        itemAnimationEnabled: Boolean
    ) {
        super.onBindView(activity, location, provider, listAnimationEnabled, itemAnimationEnabled)

        mDisposable?.dispose()
        clearPreparationBinding()
        val cctvService = CctvWeatherService.getInstance(activity)

        // Initial default state before network resolves
        cctvStatusBadge.text = "正在查看最新节目"
        cctvStatusBadge.background = null
        cctvStatusBadge.setTextColor(Color.parseColor("#0284C7"))
        unupdatedContainer.visibility = View.VISIBLE
        updatedContainer.visibility = View.GONE
        cctvCheckTimestamp.text = "可先打开中国天气网节目主页查看"
        cctvHistoryStat.text = "正在确认节目日期"
        btnPlayPrevious.text = "打开节目主页"
        btnPlayPrevious.setOnClickListener {
            CctvPlayerActivity.startOfficialPage(activity, CctvWeatherService.DEFAULT_OFFICIAL_HOME_URL)
        }
        btnPlayCctv.setOnClickListener(null)

        mDisposable = cctvService.getLatestEpisode()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ episode: CctvEpisode ->
                bindEpisode(activity, episode)
            }, { _ ->
                bindErrorState(activity)
            })
    }

    @SuppressLint("SetTextI18n")
    private fun bindEpisode(activity: BreezyActivity, episode: CctvEpisode) {
        if (episode.lookupStatus == CctvLookupStatus.FAILED) {
            bindErrorState(activity, episode)
            return
        }
        if (episode.identityStatus != CctvIdentityStatus.CONFIRMED_DATE || episode.episodeDate == null) {
            bindUnconfirmedState(activity, episode)
            return
        }
        bindPreparation(activity, episode)
        val pinnedAcceptance = org.breezyweather.BuildConfig.DEBUG &&
            java.io.File(activity.filesDir, "sensevoice-acceptance-catalogue.jsonp").isFile
        val shanghaiTz = TimeZone.getTimeZone("Asia/Shanghai")
        val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA).apply { timeZone = shanghaiTz }
        val checkTimeStr = timeFmt.format(Date(episode.checkedAtEpochMs.takeIf { it > 0 } ?: System.currentTimeMillis()))

        if (episode.isToday) {
            // Case 1: Updated today
            cctvStatusBadge.text = "今日节目已更新"
            cctvStatusBadge.background = null
            cctvStatusBadge.setTextColor(Color.parseColor("#4CAF50"))

            unupdatedContainer.visibility = View.GONE
            updatedContainer.visibility = View.VISIBLE

            cctvUpdateTime.text = "节目日期：${episodeDateLabel(episode)}"
            val brief = episode.editorialBrief
            if (!brief.isNullOrBlank()) {
                cctvSummaryHeadline.text = "中国天气网简介：$brief"
            } else {
                cctvSummaryHeadline.text = "来源：中国天气网。可先观看视频，也可提前准备字幕。"
            }

            btnPlayCctv.text = "观看${episodeDateLabel(episode)}节目"
            btnPlayCctv.setOnClickListener {
                CctvPlayerActivity.startEpisode(activity, episode)
            }
        } else {
            // Case 2: Not updated yet
            cctvStatusBadge.text = "今天的节目尚未更新"
            if (pinnedAcceptance) cctvStatusBadge.text = "验收封存节目（非最新）"
            cctvStatusBadge.background = null
            cctvStatusBadge.setTextColor(Color.parseColor("#0284C7"))

            unupdatedContainer.visibility = View.VISIBLE
            updatedContainer.visibility = View.GONE

            cctvCheckTimestamp.text = "来源：中国天气网 · 检查于 $checkTimeStr"
            cctvHistoryStat.text = "节目日期：${episodeDateLabel(episode)}"

            btnPlayPrevious.text = "观看${episodeDateLabel(episode)}节目"
            btnPlayPrevious.setOnClickListener {
                CctvPlayerActivity.startEpisode(activity, episode)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun bindUnconfirmedState(activity: BreezyActivity, episode: CctvEpisode) {
        clearPreparationBinding()
        cctvStatusBadge.text = if (episode.identityStatus == CctvIdentityStatus.CONFLICT) {
            "日期冲突"
        } else {
            "日期待确认"
        }
        cctvStatusBadge.background = null
        cctvStatusBadge.setTextColor(Color.parseColor("#0284C7"))
        unupdatedContainer.visibility = View.VISIBLE
        updatedContainer.visibility = View.GONE
        cctvCheckTimestamp.text = "可打开中国天气网节目主页查看"
        cctvHistoryStat.text = "节目日期尚未确认"
        btnPlayPrevious.text = "打开节目主页"
        btnPlayPrevious.setOnClickListener {
            CctvPlayerActivity.startOfficialPage(
                activity,
                episode.officialEpisodeUrl ?: CctvWeatherService.DEFAULT_OFFICIAL_HOME_URL
            )
        }
    }

    @SuppressLint("SetTextI18n")
    private fun bindErrorState(activity: BreezyActivity, cachedEpisode: CctvEpisode? = null) {
        clearPreparationBinding()
        val shanghaiTz = TimeZone.getTimeZone("Asia/Shanghai")
        val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA).apply { timeZone = shanghaiTz }
        val checkTimeStr = timeFmt.format(Date())

        cctvStatusBadge.text = "暂时无法确认最新节目"
        cctvStatusBadge.background = null
        cctvStatusBadge.setTextColor(Color.parseColor("#0284C7"))

        unupdatedContainer.visibility = View.VISIBLE
        updatedContainer.visibility = View.GONE

        cctvCheckTimestamp.text = "可打开中国天气网节目主页 · 检查于 $checkTimeStr"
        cctvHistoryStat.text = cachedEpisode?.takeIf { it.episodeDate != null }?.let {
            "此前节目日期：${episodeDateLabel(it)}（本次未确认更新）"
        } ?: "本次未取得已确认日期的节目"

        btnPlayPrevious.text = "打开节目主页"
        btnPlayPrevious.setOnClickListener {
            CctvPlayerActivity.startOfficialPage(
                activity,
                cachedEpisode?.officialEpisodeUrl ?: CctvWeatherService.DEFAULT_OFFICIAL_HOME_URL
            )
        }
    }

    private fun episodeDateLabel(episode: CctvEpisode): String {
        val episodeDate = episode.episodeDate ?: return "日期待确认"
        val shanghaiTz = TimeZone.getTimeZone("Asia/Shanghai")
        val date = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
                timeZone = shanghaiTz
                isLenient = false
            }.parse(episodeDate)
        }.getOrNull() ?: return episodeDate
        return SimpleDateFormat("yyyy年M月d日", Locale.CHINA).apply {
            timeZone = shanghaiTz
        }.format(date)
    }

    @SuppressLint("SetTextI18n")
    private fun bindPreparation(activity: BreezyActivity, episode: CctvEpisode) {
        clearPreparationBinding()
        val url = episode.mediaUrl ?: return
        if (episode.program != "CHINA_WEATHER_LIANBO") return
        val request = EpisodePreparationRequest(episode.program, episode.episodeDate ?: return,
            url, episode.sourceIdentityKey)
        val coordinator = EpisodePreparationCoordinator.get(activity)
        preparationRequest = request
        preparationContainer.visibility = View.VISIBLE
        renderPreparation(EpisodePreparationCoordinator.State.Idle)
        fun observe(lease: EpisodePreparationCoordinator.Lease) {
            preparationObserver?.cancel()
            preparationLease?.close()
            preparationLease = lease
            // Observing a card is not reading media; only the task/player protects its files.
            lease.close()
            preparationObserver = activity.lifecycleScope.launch {
                lease.state.collect { state ->
                    if (preparationRequest == request) renderPreparation(state)
                }
            }
        }
        prepareButton.setOnClickListener {
            preparationLookup?.cancel()
            try {
                observe(coordinator.startHome(request, retry = true))
            } catch (error: Exception) {
                preparationStatus.text = "字幕准备失败，可先观看视频"
                preparationDetail.text = "原因：${error.message ?: "本机暂时无法准备本期字幕"}"
                prepareButton.text = "重试准备字幕"
                prepareButton.isEnabled = true
                cancelPreparationButton.visibility = View.GONE
            }
        }
        cancelPreparationButton.setOnClickListener { coordinator.cancelHome(request) }
        fallbackButton.setOnClickListener {
            try { observe(coordinator.startVoskFallback(request)) }
            catch (error: Exception) { preparationDetail.text = error.message }
        }
        preparationLookup = activity.lifecycleScope.launch {
            var existing: EpisodePreparationCoordinator.Lease? = null
            var transferred = false
            try {
                withContext(Dispatchers.IO) { existing = coordinator.observeExisting(request) }
                if (preparationRequest == request) existing?.let { observe(it); transferred = true }
            } finally { if (!transferred) existing?.close() }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderPreparation(state: EpisodePreparationCoordinator.State) {
        val preparing = state is EpisodePreparationCoordinator.State.Preparing || state is EpisodePreparationCoordinator.State.Cancelling
        fallbackButton.visibility = if (state is EpisodePreparationCoordinator.State.Failed) View.VISIBLE else View.GONE
        cancelPreparationButton.visibility = if (preparing) View.VISIBLE else View.GONE
        cancelPreparationButton.isEnabled = state !is EpisodePreparationCoordinator.State.Cancelling
        prepareButton.isEnabled = !preparing && state !is EpisodePreparationCoordinator.State.Ready
        prepareButton.text = when (state) {
            is EpisodePreparationCoordinator.State.Failed -> "重试准备字幕"
            is EpisodePreparationCoordinator.State.Cancelled -> "重新准备字幕"
            is EpisodePreparationCoordinator.State.Cancelling -> "正在安全结束，稍后可重新准备"
            is EpisodePreparationCoordinator.State.Ready -> "字幕已准备好"
            else -> "提前准备字幕"
        }
        preparationStatus.text = when (state) {
            is EpisodePreparationCoordinator.State.Preparing -> when (state.stage) {
                EpisodePreparationCoordinator.Stage.DOWNLOADING -> "字幕准备中：正在获取节目视频"
                EpisodePreparationCoordinator.Stage.TRANSCRIBING -> "字幕准备中：正在识别语音"
                EpisodePreparationCoordinator.Stage.NOTES -> "字幕已生成，正在提取天气要点"
            }
            is EpisodePreparationCoordinator.State.Ready -> if (
                state.result.manifest?.episodeSummary?.items?.isNotEmpty() == true
            ) "字幕已准备好，可查看天气要点和原句"
            else "字幕已准备好，本期未生成天气要点"
            is EpisodePreparationCoordinator.State.Failed -> "字幕准备失败，可先观看视频"
            is EpisodePreparationCoordinator.State.Cancelled -> "字幕准备已取消"
            is EpisodePreparationCoordinator.State.Cancelling -> "已取消；等待本次识别安全结束"
            EpisodePreparationCoordinator.State.Idle -> "字幕尚未准备，可先观看视频"
        }
        val preparedOn = when (state) {
            is EpisodePreparationCoordinator.State.Preparing -> state.preparedOn
            is EpisodePreparationCoordinator.State.Ready -> state.preparedOn
            is EpisodePreparationCoordinator.State.Failed -> state.preparedOn
            is EpisodePreparationCoordinator.State.Cancelled -> state.preparedOn
            is EpisodePreparationCoordinator.State.Cancelling -> state.preparedOn
            EpisodePreparationCoordinator.State.Idle -> null
        }
        val progressDetail = when (state) {
            is EpisodePreparationCoordinator.State.Preparing -> state.message
                .replace("等待播放器取得本期媒体信息", "获取本期媒体信息")
                .replace("从正在播放的本期视频采集音频", "准备本期媒体")
            is EpisodePreparationCoordinator.State.Failed -> "原因：${state.reason}"
            is EpisodePreparationCoordinator.State.Cancelling -> "已禁止结果写回；结束前不会再加载模型"
            is EpisodePreparationCoordinator.State.Ready -> if (state.result.manifest?.asrModel == org.breezyweather.domain.subtitle.local.AsrBackend.SENSEVOICE.model)
                "当前字幕：SenseVoice 手机识别" else "当前字幕：旧版 Vosk（手动回退）"
            else -> null
        }
        val retentionDetail = preparedOn?.let {
            "本机字幕准备日期：$it；隔天清理（正在使用的缓存稍后清理）"
        } ?: "提前准备时没有声音；完成后可用字幕和回看选段。缓存隔天清理。"
        preparationDetail.text = listOfNotNull(progressDetail, retentionDetail).joinToString("\n")
    }

    private fun clearPreparationBinding() {
        preparationLookup?.cancel()
        preparationLookup = null
        preparationObserver?.cancel()
        preparationObserver = null
        preparationLease?.close()
        preparationLease = null
        preparationRequest = null
        preparationContainer.visibility = View.GONE
        prepareButton.setOnClickListener(null)
        cancelPreparationButton.setOnClickListener(null)
        fallbackButton.setOnClickListener(null)
    }

    override fun onRecycleView() {
        super.onRecycleView()
        mDisposable?.dispose()
        mDisposable = null
        clearPreparationBinding()
    }
}
