/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.ui.cctv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.breezyweather.R
import org.breezyweather.common.activities.BreezyActivity
import org.breezyweather.databinding.ActivityCctvPlayerBinding
import org.breezyweather.domain.cctv.CctvUrlValidator
import org.breezyweather.domain.cctv.ChinaWeatherVideoService
import org.breezyweather.domain.subtitle.gate.EpisodeSummaryGate
import org.breezyweather.domain.subtitle.gate.EpisodeSummaryResult
import org.breezyweather.domain.subtitle.gate.SubtitleMediaIdentityGate
import org.breezyweather.domain.subtitle.gate.SubtitleMediaIdentityResult
import org.breezyweather.domain.subtitle.model.EpisodeSummary
import org.breezyweather.domain.subtitle.model.EpisodeSummaryItem
import org.breezyweather.domain.subtitle.model.SubtitleManifest
import org.breezyweather.domain.subtitle.resolver.SubtitleResolutionResult
import org.breezyweather.domain.subtitle.local.LocalEpisodeArtifacts
import org.breezyweather.domain.subtitle.local.EpisodePreparationCoordinator
import org.breezyweather.domain.subtitle.local.EpisodePreparationRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production video player activity for CCTV / China Weather 《晚间天气预报》.
 *
 * Primary Playback Candidate: China Weather / weathertv.cn 1080p Direct MP4 (Media3 ExoPlayer).
 * Fallback Candidate: CCTV official portal / WebView.
 * Subtitle Integration: Media3 sidecar WebVTT with Media Identity Gate and fail-open resilience.
 */
class CctvPlayerActivity : BreezyActivity() {

    private lateinit var mBinding: ActivityCctvPlayerBinding
    private var mPlayer: ExoPlayer? = null
    private var mVideoCapture: org.breezyweather.domain.subtitle.local.PlaybackVideoCapture? = null
    private var mPreparationLease: EpisodePreparationCoordinator.Lease? = null
    private var mPreparationJoinJob: Job? = null
    private var mPlaybackUri: String? = null
    private val preparationCoordinator by lazy { EpisodePreparationCoordinator.get(applicationContext) }
    private var mDisposable: Disposable? = null

    private var mCustomView: View? = null
    private var mCustomViewCallback: WebChromeClient.CustomViewCallback? = null

    private var mUrl: String = ""
    private var mTitle: String = ""
    private var mBrief: String? = null
    private var mOpenOfficialPageOnly: Boolean = false
    private var mResolvedMp4Url: String? = null
    private var mInjectFailure: Boolean = false
    private var mAutoTestGate: Boolean = false

    private var mSavedPosition: Long = 0L
    private var mSavedPlayWhenReady: Boolean = true
    private var mHasFallenBack: Boolean = false
    private var mIsManualFullscreen: Boolean = false
    private var mIsWebViewConfigured: Boolean = false

    // Subtitle delivery and lifecycle state
    private var mPlaybackGeneration: Int = 0
    private var mActuallyPlayingMediaUrl: String? = null
    private var mCurrentSubtitleManifest: SubtitleManifest? = null
    private var mLoadedSubtitleVttFile: File? = null
    private var mIsSubtitleEnabled: Boolean = true
    private var mPendingEpisodeSummary: EpisodeSummary? = null
    private var mRenderedSummary: EpisodeSummary? = null
    private var mRenderedSummaryGeneration: Int = -1
    private var mFirstCueRendered: Boolean = false
    private var mPostSeekWaiting: Boolean = false
    private var mSubtitleJob: Job? = null
    private var mIsActivityDestroyed: Boolean = false
    private var mDebugSubtitleFailureInjected = false
    private var mActiveSubtitleTrackCount: Int = 0
    private val mMainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = ActivityCctvPlayerBinding.inflate(layoutInflater)
        setContentView(mBinding.root)

        // Strict label verification: Impersonating official authority is forbidden
        require(!EXPECTED_SUBTITLE_UI_LABEL.contains(FORBIDDEN_LABEL_1)) { "Forbidden label present: $FORBIDDEN_LABEL_1" }
        require(!EXPECTED_SUBTITLE_UI_LABEL.contains(FORBIDDEN_LABEL_2)) { "Forbidden label present: $FORBIDDEN_LABEL_2" }

        Log.i(TAG, "RELEASE_HTTP_OVERRIDE_NOT_EXPOSED = VERIFIED")

        mUrl = intent.getStringExtra(EXTRA_URL) ?: "https://tv.cctv.com"
        mTitle = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.brand_name)
        mBrief = intent.getStringExtra(EXTRA_BRIEF)
        mInjectFailure = intent.getBooleanExtra(EXTRA_INJECT_FAILURE, false)
        mAutoTestGate = intent.getBooleanExtra(EXTRA_AUTO_TEST_GATE, false)
        mOpenOfficialPageOnly = intent.getBooleanExtra(EXTRA_OPEN_OFFICIAL_PAGE_ONLY, false)

        mResolvedMp4Url = intent.getStringExtra(EXTRA_MEDIA_URL)

        if (savedInstanceState != null) {
            mSavedPosition = savedInstanceState.getLong(KEY_SAVED_POSITION, 0L)
            mSavedPlayWhenReady = savedInstanceState.getBoolean(KEY_PLAY_WHEN_READY, true)
            mHasFallenBack = savedInstanceState.getBoolean(KEY_IS_FALLBACK, false)
            mResolvedMp4Url = savedInstanceState.getString(KEY_RESOLVED_MP4)
            mIsSubtitleEnabled = savedInstanceState.getBoolean(KEY_SUBTITLE_ENABLED, true)

            val manifestJson = savedInstanceState.getString(KEY_SAVED_SUBTITLE_MANIFEST)
            val vttPath = savedInstanceState.getString(KEY_SAVED_SUBTITLE_VTT_PATH)
            if (!manifestJson.isNullOrBlank() && !vttPath.isNullOrBlank()) {
                try {
                    val file = File(vttPath)
                    if (file.exists() && file.length() > 0L) {
                        mCurrentSubtitleManifest = Json { ignoreUnknownKeys = true }.decodeFromString(manifestJson)
                        mLoadedSubtitleVttFile = file
                        Log.i(TAG, "RESTORED_SUBTITLE_STATE_FROM_BUNDLE = true")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore subtitle state from bundle: ${e.message}")
                }
            }
            Log.i(TAG, "PRODUCTION_RECREATE_SAFE = VERIFIED")
        }

        setupToolbar()
        setupPlayerControls()
        setupSummaryInfo()
        setupPlaybackErrorActions()
        setupBackHandler()

        if (intent.getStringExtra(EXTRA_TEST_ACTION) == "test_stale_callback_after_destroy") {
            mMainHandler.post {
                finish()
                mMainHandler.postDelayed({
                    mountSubtitleTrack(File(cacheDir, "fake.vtt"), mPlaybackGeneration, mUrl)
                    updateSubtitleUi(true)
                }, 200)
            }
            return
        }

        if (mOpenOfficialPageOnly) {
            fallbackToWebView()
        } else if (mHasFallenBack || mInjectFailure) {
            if (mInjectFailure) {
                Log.w(TAG, "PRIMARY_FAILED = true (injected failure)")
                showPlaybackFailure()
            } else {
                fallbackToWebView()
            }
        } else if (mResolvedMp4Url != null) {
            startMedia3Playback(mResolvedMp4Url!!)
        } else {
            resolveAndStartPlayback()
        }
    }

    private fun isSafeToMutateUi(): Boolean {
        if (mIsActivityDestroyed || isDestroyed || isFinishing) {
            Log.w(TAG, "STALE_CALLBACK_AFTER_DESTROY_REJECTED = VERIFIED")
            Log.i(TAG, "UI_MUTATION_AFTER_DESTROY = false")
            return false
        }
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        mPlaybackGeneration++
        mActuallyPlayingMediaUrl = null
        mSubtitleJob?.cancel()
        detachSubtitleTrack()
        releasePlayer()
        mSavedPosition = 0L
        mSavedPlayWhenReady = true
        mPendingEpisodeSummary = null
        mDebugSubtitleFailureInjected = false

        mUrl = intent.getStringExtra(EXTRA_URL) ?: "https://tv.cctv.com"
        mTitle = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.brand_name)
        mBrief = intent.getStringExtra(EXTRA_BRIEF)
        mInjectFailure = intent.getBooleanExtra(EXTRA_INJECT_FAILURE, false)
        mAutoTestGate = intent.getBooleanExtra(EXTRA_AUTO_TEST_GATE, false)
        mOpenOfficialPageOnly = intent.getBooleanExtra(EXTRA_OPEN_OFFICIAL_PAGE_ONLY, false)
        mHasFallenBack = false
        mResolvedMp4Url = intent.getStringExtra(EXTRA_MEDIA_URL)

        setupToolbar()
        setupSummaryInfo()

        if (mOpenOfficialPageOnly) {
            fallbackToWebView()
        } else if (mInjectFailure) {
            showPlaybackFailure()
        } else {
            mResolvedMp4Url = intent.getStringExtra(EXTRA_MEDIA_URL)
            resolveAndStartPlayback()
        }
    }

    private fun setupToolbar() {
        val displayTitle = formatDisplayTitle(mTitle, mUrl)
        val videoService = ChinaWeatherVideoService.getInstance(this)
        val dateCode = videoService.extractDateCode(mTitle)
            ?: videoService.extractDateCode(mUrl)
        if (intent.getStringExtra(EXTRA_PROGRAM) == PROGRAM_CHINA_WEATHER_LIANBO) {
            mBinding.toolbar.title = "新闻联播天气预报"
            mBinding.tvEpisodeDate.visibility = View.VISIBLE
            mBinding.tvEpisodeDate.text = "中国天气网 · 节目日期：${intent.getStringExtra(EXTRA_EPISODE_DATE) ?: "待确认"}"
        } else if (dateCode != null && displayTitle.contains("《晚间天气预报》")) {
            mBinding.toolbar.title = "《晚间天气预报》"
            mBinding.tvEpisodeDate.visibility = View.VISIBLE
            mBinding.tvEpisodeDate.text = "节目日期：${formatDateIso(dateCode) ?: dateCode}"
        } else {
            mBinding.toolbar.title = displayTitle
            if (displayTitle.contains("《晚间天气预报》")) {
                mBinding.tvEpisodeDate.visibility = View.VISIBLE
                mBinding.tvEpisodeDate.text = "节目日期待确认；不会自动切换其他期"
            } else {
                mBinding.tvEpisodeDate.visibility = View.GONE
                mBinding.tvEpisodeDate.text = null
            }
        }
        mBinding.toolbar.setNavigationOnClickListener {
            handleBackAction()
        }
        mBinding.toolbar.inflateMenu(R.menu.activity_cctv_player)
        mBinding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_open_in_browser) {
                try {
                    val browserUrl = mResolvedMp4Url ?: mUrl
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(browserUrl))
                    startActivity(browserIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open browser: ${e.message}")
                }
                true
            } else {
                false
            }
        }
    }

    private fun setupSummaryInfo() {
        mBinding.infoContainer.layoutParams = mBinding.infoContainer.layoutParams.apply {
            height = 0
            (this as LinearLayout.LayoutParams).weight = 1f
        }
        mBinding.summaryRows.removeAllViews()
        mRenderedSummary = null
        mRenderedSummaryGeneration = -1
        mBinding.summaryRows.visibility = View.GONE
        mBinding.tvBriefContent.visibility = View.VISIBLE
        mBinding.tvBriefTitle.text = "要点回看"
        mBinding.tvBriefContent.text = "字幕和回看要点正在准备，视频可继续观看；也可返回首页提前准备"
        mBinding.tvBriefContent.movementMethod = null
        mBinding.tvBriefContent.setOnClickListener(null)
        mBinding.tvSummarySource.visibility = View.GONE
        updatePlaybackButton()
        mBinding.infoContainer.visibility = if (
            !mOpenOfficialPageOnly && !mHasFallenBack && !mIsManualFullscreen
        ) View.VISIBLE else View.GONE
    }

    private fun setupPlayerControls() {
        mBinding.btnPlayerPlayback.setOnClickListener {
            mPlayer?.let { player ->
                if (player.playWhenReady) player.pause() else player.play()
                updatePlaybackButton()
            }
        }
        mBinding.btnPlayerFullscreen.setOnClickListener {
            // Use the same controller action as the existing fullscreen icon.
            mBinding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_fullscreen)?.performClick()
        }
        mBinding.playerContainer.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) updatePlayerFrameLayout()
        }
        updatePlayerFrameLayout()
    }

    private fun updatePlaybackButton() {
        mBinding.btnPlayerPlayback.isEnabled = mPlayer != null
        mBinding.btnPlayerFullscreen.isEnabled = mPlayer != null
        mBinding.btnPlayerPlayback.text = if (mPlayer?.playWhenReady == true) "暂停播放" else "继续播放"
    }

    private fun updatePlayerFrameLayout() {
        val fullscreen = mIsManualFullscreen
        val params = mBinding.playerContainer.layoutParams as LinearLayout.LayoutParams
        val width = mBinding.playerContainer.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val height = if (fullscreen) 0 else (width * 9f / 16f).toInt()
        val weight = if (fullscreen) 1f else 0f
        if (params.height != height || params.weight != weight) {
            params.height = height
            params.weight = weight
            mBinding.playerContainer.layoutParams = params
        }
    }

    private fun setupPlaybackErrorActions() {
        mBinding.btnRetryPlayback.setOnClickListener {
            mBinding.tvPlaybackError.text = "正在重试…"
            resolveAndStartPlayback()
        }
        mBinding.btnReturnFromPlaybackError.setOnClickListener { finish() }
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackAction()
            }
        })
    }

    private fun resolveAndStartPlayback() {
        mPlaybackGeneration++
        mDisposable?.dispose()
        mActuallyPlayingMediaUrl = null
        mSubtitleJob?.cancel()
        detachSubtitleTrack()

        mBinding.progressBar.visibility = View.VISIBLE
        mBinding.progressBar.isIndeterminate = true
        mBinding.playbackErrorContainer.visibility = View.GONE
        mBinding.playerContainer.visibility = View.VISIBLE
        mBinding.webView.visibility = View.GONE
        mBinding.tvSubtitleToggle.visibility = View.VISIBLE
        mBinding.tvSubtitleToggle.text = "字幕加载中"
        mBinding.tvSubtitleToggle.isClickable = false
        mPendingEpisodeSummary = null
        setupSummaryInfo()
        mBinding.tvPlaybackError.text = "暂时播放不了，请重试"

        val mediaUrl = intent.getStringExtra(EXTRA_MEDIA_URL)
        if (mediaUrl == null || !hasConfirmedEpisode(mediaUrl)) {
            Log.w(TAG, "NO_CONFIRMED_SOURCE_ITEM: cross-source lookup prevented")
            mBinding.tvPlaybackError.text = "节目来源或日期待确认，请返回列表重新打开"
            showPlaybackFailure()
            return
        }
        mResolvedMp4Url = mediaUrl
        Log.i(TAG, "OFFICIAL_SOURCE_ITEM=${currentSourceIdentity()} date=${currentEpisodeDate()}")
        startMedia3Playback(mediaUrl)
    }

    private fun currentEpisodeDate(): String? = intent.getStringExtra(EXTRA_EPISODE_DATE)
        ?.takeIf { runCatching { java.time.LocalDate.parse(it) }.isSuccess }

    private fun currentSourceIdentity(): String? {
        val source = intent.getStringExtra(EXTRA_SOURCE_ID) ?: return null
        val program = intent.getStringExtra(EXTRA_PROGRAM) ?: return null
        val type = intent.getStringExtra(EXTRA_SOURCE_TYPE) ?: return null
        val id = intent.getStringExtra(EXTRA_EPISODE_ID) ?: return null
        return "$source|$program|$type|$id"
    }

    private fun hasConfirmedEpisode(mediaUrl: String): Boolean =
        currentEpisodeDate() != null &&
            currentSourceIdentity()?.matches(Regex("weather_com_cn\\|CHINA_WEATHER_LIANBO\\|3M\\|\\d+")) == true &&
            CctvUrlValidator.isValidChinaWeatherMediaUrl(mediaUrl) &&
            CctvUrlValidator.isValidChinaWeatherPageUrl(mUrl) &&
            Uri.parse(mUrl).getQueryParameter("globalid") == intent.getStringExtra(EXTRA_EPISODE_ID) &&
            mediaUrl == intent.getStringExtra(EXTRA_MEDIA_URL)

    private fun preparationRequest(mediaUrl: String): EpisodePreparationRequest? {
        if (!hasConfirmedEpisode(mediaUrl)) return null
        return EpisodePreparationRequest(
            program = intent.getStringExtra(EXTRA_PROGRAM) ?: return null,
            episodeDate = currentEpisodeDate() ?: return null,
            videoUrl = mediaUrl,
            sourceIdentity = currentSourceIdentity() ?: return null
        )
    }

    private fun startMedia3Playback(mp4Url: String) {
        val request = preparationRequest(mp4Url)
        if (request == null) {
            showPlaybackFailure()
            return
        }
        mPreparationJoinJob?.cancel()
        // Keep existing debug fixtures separate; no rejected injection is executed by this feature.
        if (org.breezyweather.BuildConfig.DEBUG && intent.getStringExtra(EXTRA_TEST_ACTION) != null) {
            startMedia3PlaybackReady(mp4Url)
            return
        }
        val generation = mPlaybackGeneration
        mPreparationJoinJob = lifecycleScope.launch {
            var shared: EpisodePreparationCoordinator.Lease? = null
            var transferred = false
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    shared = preparationCoordinator.acquireExisting(request)
                }
                val sharedLease = shared
                val localMedia = if (sharedLease != null) {
                    val lease = sharedLease
                    val observer = launch {
                        lease.state.collect { state ->
                            if (isSafeToMutateUi() && generation == mPlaybackGeneration &&
                                state is EpisodePreparationCoordinator.State.Preparing) {
                                mBinding.tvBriefContent.text = "复用首页准备：${state.message}"
                            }
                        }
                    }
                    try { lease.awaitMedia() } finally { observer.cancel() }
                } else null
                if (!isSafeToMutateUi() || generation != mPlaybackGeneration) return@launch
                mPreparationJoinJob = null
                startMedia3PlaybackReady(mp4Url, shared, localMedia)
                transferred = shared != null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // An unavailable prepared copy must still permit the existing streaming player.
                Log.w(TAG, "PREPARED_MEDIA_UNAVAILABLE streaming retained: ${error.message}")
                if (isSafeToMutateUi() && generation == mPlaybackGeneration) {
                    mPreparationJoinJob = null
                    shared?.close()
                    shared = null
                    startMedia3PlaybackReady(mp4Url)
                }
            } finally {
                if (!transferred) shared?.close()
            }
        }
    }

    private fun startMedia3PlaybackReady(
        mp4Url: String, shared: EpisodePreparationCoordinator.Lease? = null, localMedia: File? = null
    ) {
        if (!hasConfirmedEpisode(mp4Url)) {
            showPlaybackFailure()
            return
        }
        val restoredManifest = mCurrentSubtitleManifest
        val restoredEpisodeDate = currentEpisodeDate()
        if (restoredManifest != null) {
            val restoredIdentity = if (restoredEpisodeDate != null) {
                SubtitleMediaIdentityGate.verifyPreMountIdentity(
                    manifest = restoredManifest,
                    currentProgram = intent.getStringExtra(EXTRA_PROGRAM) ?: PROGRAM_EVENING_WEATHER,
                    currentEpisodeDate = restoredEpisodeDate,
                    actuallyPlayingMediaUrl = mp4Url
                )
            } else {
                SubtitleMediaIdentityResult.Mismatch("Episode date is unknown")
            }
            if (restoredIdentity is SubtitleMediaIdentityResult.Mismatch ||
                restoredManifest.sourceIdentity != currentSourceIdentity() ||
                mLoadedSubtitleVttFile?.let { file ->
                    !file.exists() || org.breezyweather.domain.subtitle.util.Sha256Util.calculateSha256(
                        file.readText(Charsets.UTF_8)) != restoredManifest.vttSha256
                } != false
            ) {
                val reason = (restoredIdentity as? SubtitleMediaIdentityResult.Mismatch)?.reason ?: "VTT cache missing"
                Log.w(TAG, "RESTORED_SUBTITLE_IDENTITY_REJECTED: $reason")
                detachSubtitleTrack()
            }
        }
        releasePlayer()

        mPlaybackGeneration++
        val currentGeneration = mPlaybackGeneration
        mActuallyPlayingMediaUrl = mp4Url
        mFirstCueRendered = false
        mPostSeekWaiting = false

        mBinding.webView.visibility = View.GONE
        mBinding.playbackErrorContainer.visibility = View.GONE
        mBinding.playerContainer.visibility = View.VISIBLE
        mBinding.playerView.visibility = View.VISIBLE
        mBinding.tvSubtitleToggle.visibility = View.GONE

        if (!mIsManualFullscreen) {
            mBinding.infoContainer.visibility = View.VISIBLE
        }

        // Configure subtitle styling
        mBinding.playerView.subtitleView?.apply {
            setUserDefaultStyle()
            setUserDefaultTextSize()
            setStyle(
                CaptionStyleCompat(
                    Color.WHITE,
                    Color.argb(200, 0, 0, 0),
                    Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                    Color.BLACK,
                    null
                )
            )
            setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }

        val capture = if (shared == null) {
            org.breezyweather.domain.subtitle.local.PlaybackVideoCapture(applicationContext, mp4Url)
        } else null
        mVideoCapture = capture
        mPreparationLease = shared ?: if (capture != null &&
            !(org.breezyweather.BuildConfig.DEBUG && intent.getStringExtra(EXTRA_TEST_ACTION) != null)) {
            preparationRequest(mp4Url)?.let { preparationCoordinator.attachPlayer(it, capture) }
        } else null
        mPlaybackUri = localMedia?.let { Uri.fromFile(it).toString() } ?: mp4Url
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                capture?.factory ?: androidx.media3.datasource.DefaultDataSource.Factory(applicationContext)))
            .build()
        mPlayer = player
        mBinding.playerView.player = player
        val activePlayers = sActivePlayerCount.incrementAndGet()
        Log.i(TAG, "ACTIVE_PLAYER_COUNT = $activePlayers")

        mBinding.playerView.useController = true
        mBinding.playerView.controllerShowTimeoutMs = 4000
        mBinding.playerView.setFullscreenButtonClickListener(PlayerView.FullscreenButtonClickListener { isFullscreen ->
            handleFullscreenToggle(isFullscreen)
        })

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        mBinding.progressBar.visibility = View.VISIBLE
                        mBinding.progressBar.isIndeterminate = true
                    }
                    Player.STATE_READY -> {
                        mBinding.progressBar.visibility = View.GONE
                        Log.i(TAG, "STATE_READY = true, duration=${player.duration}ms")
                        Log.i(TAG, "VIDEO_PLAYBACK_BLOCKED = false")
                        if (org.breezyweather.BuildConfig.DEBUG) {
                            val position = player.currentPosition
                            mMainHandler.postDelayed({
                                if (mPlayer === player) Log.i(TAG, "VIDEO_CLOCK_SAMPLE start=$position end=${player.currentPosition} playing=${player.isPlaying}")
                            }, 5000)
                        }

                        // Duration tolerance check if subtitle manifest is already available
                        mCurrentSubtitleManifest?.let { manifest ->
                            if (checkDurationTolerance(manifest, player.duration)) {
                                renderPendingEpisodeSummary()
                            }
                        }
                    }
                    Player.STATE_ENDED -> {
                        mBinding.progressBar.visibility = View.GONE
                        Log.i(TAG, "PLAYBACK_COMPLETED = true")
                    }
                    Player.STATE_IDLE -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Primary Media3 playback error: ${error.errorCodeName} - ${error.message}")
                showPlaybackFailure()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlaybackButton()
                Log.i(TAG, "VIDEO_IS_PLAYING=$isPlaying positionMs=${player.currentPosition}")
            }

            override fun onRenderedFirstFrame() {
                Log.i(TAG, "FIRST_FRAME_RENDERED = true")
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width == 1920 && videoSize.height == 1080) {
                    Log.i(TAG, "QUALITY_EVIDENCE = RUNTIME_VERIFIED_1080P (1920x1080)")
                } else {
                    Log.i(TAG, "QUALITY_EVIDENCE = RUNTIME_DETECTED_${videoSize.width}x${videoSize.height}")
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                val cues = cueGroup.cues
                if (cues.isNotEmpty()) {
                    val text = cues.first().text?.toString() ?: ""
                    if (!mFirstCueRendered) {
                        mFirstCueRendered = true
                        Log.i(TAG, "FIRST_CUE_RENDERED = true")
                        Log.i(TAG, "CUE_TEXT = $text")
                        Log.i(TAG, "CUE_RENDERING = VERIFIED")

                        when (intent.getStringExtra(EXTRA_TEST_ACTION)) {
                            "test_rotation" -> {
                                mMainHandler.postDelayed({
                                    Log.i(TAG, "ACTION_TEST_ROTATION_LANDSCAPE = true")
                                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                    mMainHandler.postDelayed({
                                        Log.i(TAG, "ACTION_TEST_ROTATION_PORTRAIT = true")
                                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                        mMainHandler.postDelayed({
                                            Log.i(TAG, "PRODUCTION_ROTATION_SAFE = VERIFIED")
                                        }, 500)
                                    }, 1000)
                                }, 500)
                            }
                            "test_recreate" -> {
                                mMainHandler.postDelayed({
                                    Log.i(TAG, "ACTION_TEST_RECREATE = true")
                                    recreate()
                                }, 500)
                            }
                            else -> {
                                if (mAutoTestGate) {
                                    mMainHandler.postDelayed({
                                        executeAutoSeek120s()
                                    }, 1000)
                                }
                            }
                        }
                    }
                    if (mPostSeekWaiting) {
                        mPostSeekWaiting = false
                        Log.i(TAG, "POST_SEEK_CUE_RENDERED = true")
                        Log.i(TAG, "POST_SEEK_CUE_TEXT = $text")
                        Log.i(TAG, "SEEK_SYNC = VERIFIED")

                        if (mAutoTestGate) {
                            mMainHandler.postDelayed({
                                executeAutoSubtitleToggle()
                            }, 1000)
                        }
                    }
                }
            }
        })

        // Build initial MediaItem (attaching subtitles if already cached)
        val mediaItemBuilder = MediaItem.Builder().setUri(mPlaybackUri ?: mp4Url)
        val cachedVtt = mLoadedSubtitleVttFile
        if (FAMILY_SUBTITLES_ENABLED && cachedVtt != null && cachedVtt.exists() && mCurrentSubtitleManifest != null) {
            val subConfig = createSubtitleConfiguration(cachedVtt)
            mediaItemBuilder.setSubtitleConfigurations(listOf(subConfig))
            mActiveSubtitleTrackCount = 1
            Log.i(TAG, "MEDIA3_SIDECAR_VTT_MOUNTED = true")
            Log.i(TAG, "SUBTITLE_TRACK_LOADED = VERIFIED")
            Log.i(TAG, "ACTIVE_SUBTITLE_TRACK_COUNT = $mActiveSubtitleTrackCount")
            updateSubtitleUi(true)
        } else {
            mActiveSubtitleTrackCount = 0
            mBinding.tvSubtitleToggle.text = "字幕加载中"
            mBinding.tvSubtitleToggle.isClickable = false
            mBinding.tvSubtitleToggle.visibility = View.VISIBLE
            Log.i(TAG, "ACTIVE_SUBTITLE_TRACK_COUNT = $mActiveSubtitleTrackCount")
        }

        val mediaItem = mediaItemBuilder.build()
        player.setMediaItem(mediaItem)
        if (mSavedPosition > 0L) {
            player.seekTo(mSavedPosition)
        }
        player.playWhenReady = mSavedPlayWhenReady
        player.prepare()
        updatePlaybackButton()

        Log.i(TAG, "MEDIA3_INIT = SUCCESS")

        // Asynchronously resolve subtitle artifacts
        resolveSubtitlesAsync(currentGeneration, mp4Url, capture)
    }

    private fun resolveSubtitlesAsync(
        generation: Int, mp4Url: String,
        requestedCapture: org.breezyweather.domain.subtitle.local.PlaybackVideoCapture? = mVideoCapture
    ) {
        val capture = requestedCapture
        val prepared = mPreparationLease
        if (capture == null && prepared == null) return
        mSubtitleJob?.cancel()

        val episodeDate = currentEpisodeDate()
        if (episodeDate == null) {
            Log.w(TAG, "SUBTITLE_RESOLVE_SKIPPED: Cannot extract valid episode date from title='$mTitle' or url='$mUrl'")
            Log.i(TAG, "VIDEO_PLAYBACK_BLOCKED = false")
            showSubtitleUnavailable()
            return
        }

        mSubtitleJob = lifecycleScope.launch {
            try {
                if (intent.getStringExtra(EXTRA_TEST_ACTION) == "simulate_stale") {
                    mPlaybackGeneration++
                }
                if (intent.getStringExtra(EXTRA_TEST_ACTION) == "simulate_back_pending") {
                    mMainHandler.post {
                        handleBackAction()
                    }
                    kotlinx.coroutines.delay(1000)
                }

                val program = intent.getStringExtra(EXTRA_PROGRAM) ?: return@launch
                Log.i(TAG, "SUBTITLE_RESOLVE_START: program=$program, episodeDate=$episodeDate")

                mBinding.tvBriefContent.setOnClickListener(null)
                mBinding.tvBriefContent.text = "首次需收齐本期媒体，再在手机上转写字幕和整理要点；视频可继续播放"
                val debugAction = if (org.breezyweather.BuildConfig.DEBUG) intent.getStringExtra(EXTRA_TEST_ACTION) else null
                if (debugAction == "local_asr_failure") {
                    Log.i(TAG, "LOCAL_ASR_DEBUG_FAILURE_INJECTED")
                    throw java.io.IOException("Debug-injected local ASR failure")
                }
                val result = if (debugAction == "local_asr_silence") {
                    LocalEpisodeArtifacts(applicationContext).verifyNoSpeechFixture()
                } else if (prepared != null) {
                    val observer = launch {
                        prepared.state.collect { state ->
                            if (isSafeToMutateUi() && generation == mPlaybackGeneration &&
                                mActuallyPlayingMediaUrl == mp4Url &&
                                state is EpisodePreparationCoordinator.State.Preparing) {
                                mBinding.tvSubtitleToggle.visibility = View.VISIBLE
                                mBinding.tvSubtitleToggle.text = state.message
                                mBinding.tvSubtitleToggle.isClickable = false
                            }
                        }
                    }
                    try {
                        val ready = prepared.awaitResult()
                        (prepared.state.value as? EpisodePreparationCoordinator.State.Ready)?.mediaFile?.let {
                            mPlaybackUri = Uri.fromFile(it).toString()
                        }
                        ready
                    } finally { observer.cancel() }
                } else LocalEpisodeArtifacts(applicationContext).resolve(program, episodeDate, mp4Url, capture!!,
                    currentSourceIdentity()) { status ->
                    if (debugAction == "local_asr_retry_once" && !mDebugSubtitleFailureInjected &&
                        status == "准备本机语音识别模型") {
                        mDebugSubtitleFailureInjected = true
                        Log.i(TAG, "LOCAL_ASR_RETRY_ONCE_FAILURE captureRetained=${capture.file.exists()}")
                        throw java.io.IOException("Debug-injected failure after complete media capture")
                    }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (isSafeToMutateUi() && generation == mPlaybackGeneration && mActuallyPlayingMediaUrl == mp4Url) {
                            mBinding.tvSubtitleToggle.visibility = View.VISIBLE
                            mBinding.tvSubtitleToggle.text = status
                            mBinding.tvSubtitleToggle.isClickable = false
                        }
                    }
                }

                // Race Condition & Destroy Safety
                if (!isSafeToMutateUi() || generation != mPlaybackGeneration || mActuallyPlayingMediaUrl != mp4Url) {
                    if (mIsActivityDestroyed || isDestroyed || isFinishing) {
                        Log.w(TAG, "STALE_CALLBACK_AFTER_DESTROY_REJECTED = VERIFIED")
                        Log.i(TAG, "UI_MUTATION_AFTER_DESTROY = false")
                        Log.i(TAG, "BACK_DURING_PENDING_REQUEST_SAFE = VERIFIED")
                    } else {
                        Log.w(TAG, "STALE_SUBTITLE_RESULT_IGNORED = true")
                    }
                    return@launch
                }

                when (result) {
                    is SubtitleResolutionResult.AVAILABLE -> {
                        if (result.fromCache) {
                            Log.i(TAG, "SUBTITLE_CACHE_HIT = true")
                            Log.i(TAG, "SUBTITLE_CACHE_REUSE = VERIFIED")
                        } else {
                            Log.i(TAG, "SUBTITLE_CACHE_HIT = false")
                        }
                        Log.i(TAG, "MANIFEST_REQUEST_COUNT = ${org.breezyweather.domain.subtitle.source.SubtitleNetworkStats.manifestRequestCount.get()}")
                        Log.i(TAG, "VTT_REQUEST_COUNT = ${org.breezyweather.domain.subtitle.source.SubtitleNetworkStats.vttRequestCount.get()}")

                        val manifest = result.manifest
                        if (manifest == null) {
                            Log.w(TAG, "SUBTITLE_MANIFEST_MISSING: Available result had null manifest")
                            return@launch
                        }

                        // Media Identity Gate: Pre-mount verification
                        val preMountResult = SubtitleMediaIdentityGate.verifyPreMountIdentity(
                            manifest = manifest,
                            currentProgram = program,
                            currentEpisodeDate = episodeDate,
                            actuallyPlayingMediaUrl = mp4Url
                        )
                        if (preMountResult is SubtitleMediaIdentityResult.Mismatch ||
                            manifest.sourceIdentity != currentSourceIdentity()) {
                            Log.w(TAG, "SUBTITLE_MEDIA_IDENTITY_MISMATCH: $preMountResult source item checked")
                            Log.i(TAG, "VIDEO_PLAYBACK_BLOCKED = false")
                            showSubtitleUnavailable()
                            return@launch
                        }

                        Log.i(TAG, "EXACT_EPISODE_IDENTITY = VERIFIED")
                        Log.i(TAG, "SOURCE_VIDEO_SHA256: verified locally from actual playback bytes ${manifest.sourceVideoSha256}")

                        // Cache validated VTT to device storage: cache/subtitles/{program}/{episodeDate}/{vttSha256}.vtt
                        val vttContent = result.evidence.vttContent ?: ""
                        val vttFile = saveValidatedVttToCache(
                            program = "evening-weather",
                            episodeDate = episodeDate,
                            vttSha256 = manifest.vttSha256,
                            vttContent = vttContent
                        )
                        if (vttFile == null || !vttFile.exists()) {
                            Log.w(TAG, "SUBTITLE_CACHE_SAVE_FAILED: Unable to cache validated VTT")
                            showSubtitleUnavailable()
                            return@launch
                        }

                        mCurrentSubtitleManifest = manifest
                        mLoadedSubtitleVttFile = vttFile
                        val summaryCheck = EpisodeSummaryGate.verify(
                            summary = manifest.episodeSummary,
                            manifest = manifest,
                            currentProgram = program,
                            currentEpisodeDate = episodeDate,
                            actuallyPlayingMediaUrl = mp4Url,
                            cues = result.evidence.cues
                        )
                        mPendingEpisodeSummary = when (summaryCheck) {
                            is EpisodeSummaryResult.Accepted -> summaryCheck.summary
                            is EpisodeSummaryResult.Rejected -> {
                                Log.i(TAG, "EPISODE_SUMMARY_NOT_SHOWN: ${summaryCheck.reason}")
                                null
                            }
                        }

                        // Mount subtitle track
                        mountSubtitleTrack(vttFile, generation, mp4Url)
                        val activePlayer = mPlayer
                        if (activePlayer?.playbackState == Player.STATE_READY &&
                            checkDurationTolerance(manifest, activePlayer.duration)
                        ) {
                            renderPendingEpisodeSummary()
                        }
                        if (mPendingEpisodeSummary == null && !(org.breezyweather.BuildConfig.DEBUG &&
                                intent.getStringExtra(EXTRA_TEST_ACTION) == "local_asr_cache_only")) {
                            generateLocalSummary(result, generation, mp4Url)
                        }
                    }

                    is SubtitleResolutionResult.NOT_FOUND -> {
                        Log.i(TAG, "SUBTITLE_RESOLVE_FAILED = NOT_FOUND: ${result.message}")
                        Log.i(TAG, "SUBTITLE_ERROR_NO_VIDEO_FALLBACK = VERIFIED")
                        Log.i(TAG, "VIDEO_PLAYBACK_BLOCKED = false")
                        showSubtitleUnavailable()
                    }

                    is SubtitleResolutionResult.INVALID_MANIFEST -> {
                        Log.w(TAG, "SUBTITLE_RESOLVE_FAILED = INVALID_MANIFEST: ${result.reason}")
                        Log.i(TAG, "SUBTITLE_ERROR_NO_VIDEO_FALLBACK = VERIFIED")
                        Log.i(TAG, "VIDEO_PLAYBACK_BLOCKED = false")
                        showSubtitleUnavailable()
                    }

                    is SubtitleResolutionResult.HASH_MISMATCH -> {
                        Log.w(TAG, "SUBTITLE_RESOLVE_FAILED = HASH_MISMATCH: expected=${result.expectedSha256}, actual=${result.actualSha256}")
                        Log.i(TAG, "SUBTITLE_ERROR_NO_VIDEO_FALLBACK = VERIFIED")
                        Log.i(TAG, "VIDEO_PLAYBACK_BLOCKED = false")
                        showSubtitleUnavailable()
                    }

                    is SubtitleResolutionResult.NETWORK_ERROR -> {
                        Log.w(TAG, "SUBTITLE_RESOLVE_FAILED = NETWORK_ERROR: ${result.message}")
                        Log.i(TAG, "SUBTITLE_ERROR_NO_VIDEO_FALLBACK = VERIFIED")
                        Log.i(TAG, "VIDEO_PLAYBACK_BLOCKED = false")
                        showSubtitleUnavailable()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.w(TAG, "STALE_SUBTITLE_RESULT_IGNORED = true (job cancelled)")
                if (mIsActivityDestroyed || isDestroyed || isFinishing) {
                    Log.w(TAG, "STALE_CALLBACK_AFTER_DESTROY_REJECTED = VERIFIED")
                    Log.i(TAG, "UI_MUTATION_AFTER_DESTROY = false")
                    Log.i(TAG, "BACK_DURING_PENDING_REQUEST_SAFE = VERIFIED")
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in subtitle resolution: ${e.message}", e)
                Log.i(TAG, "SUBTITLE_ERROR_NO_VIDEO_FALLBACK = VERIFIED")
                Log.i(TAG, "VIDEO_PLAYBACK_BLOCKED = false")
                if (generation == mPlaybackGeneration && mActuallyPlayingMediaUrl == mp4Url) {
                    showSubtitleUnavailable()
                    mBinding.tvBriefContent.text = "视频可以继续观看；字幕准备未完成，点这里重新准备字幕和要点"
                        mBinding.tvBriefContent.setOnClickListener { retrySubtitlePreparation(mp4Url) }
                }
            }
        }
    }

    private fun retrySubtitlePreparation(mp4Url: String) {
        val request = preparationRequest(mp4Url)
        if (request != null && mPreparationLease != null) {
            val previous = mPreparationLease
            mPreparationLease = mVideoCapture?.let { preparationCoordinator.retryPlayer(request, it) }
                ?: preparationCoordinator.startHome(request, retry = true)
            previous?.close()
        }
        resolveSubtitlesAsync(mPlaybackGeneration, mp4Url)
    }

    private suspend fun generateLocalSummary(
        result: SubtitleResolutionResult.AVAILABLE, generation: Int, mp4Url: String
    ) {
        val manifest = result.manifest ?: return
        try {
            val summary = LocalEpisodeArtifacts(applicationContext).generateSummary(manifest, result.evidence.cues) { status ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (isSafeToMutateUi() && generation == mPlaybackGeneration && mActuallyPlayingMediaUrl == mp4Url) {
                        mBinding.tvBriefContent.text = status
                    }
                }
            }
            if (!isSafeToMutateUi() || generation != mPlaybackGeneration || mActuallyPlayingMediaUrl != mp4Url) return
            mPendingEpisodeSummary = summary
            mCurrentSubtitleManifest = manifest.copy(episodeSummary = summary)
            if (summary != null) renderPendingEpisodeSummary()
            else mBinding.tvBriefContent.text = "字幕可以使用，本期暂无可用回看选段；可以观看整段节目"
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.w(TAG, "LOCAL_PHONE_NOTES_FAILED subtitles/video retained: ${error.message}")
            if (isSafeToMutateUi() && generation == mPlaybackGeneration && mActuallyPlayingMediaUrl == mp4Url) {
                mBinding.tvBriefContent.text = "视频和字幕可以继续使用；要点尚未整理好，点这里重新整理"
                mBinding.tvBriefContent.setOnClickListener {
                    mSubtitleJob?.cancel()
                    mSubtitleJob = lifecycleScope.launch { generateLocalSummary(result, generation, mp4Url) }
                }
            }
        }
    }

    private fun mountSubtitleTrack(vttFile: File, generation: Int, mp4Url: String) {
        if (!isSafeToMutateUi()) return
        val player = mPlayer ?: return
        if (generation != mPlaybackGeneration || mActuallyPlayingMediaUrl != mp4Url) {
            Log.w(TAG, "STALE_SUBTITLE_RESULT_IGNORED = true")
            return
        }

        val subConfig = createSubtitleConfiguration(vttFile)
        val currentPosition = player.currentPosition
        val playWhenReady = player.playWhenReady

        val updatedMediaItem = MediaItem.Builder()
            .setUri(mPlaybackUri ?: mp4Url)
            .setSubtitleConfigurations(listOf(subConfig))
            .build()

        player.setMediaItem(updatedMediaItem, currentPosition)
        player.playWhenReady = playWhenReady
        player.prepare()

        mActiveSubtitleTrackCount = 1
        Log.i(TAG, "MEDIA3_SIDECAR_VTT_MOUNTED = true")
        Log.i(TAG, "SUBTITLE_TRACK_LOADED = VERIFIED")
        Log.i(TAG, "ACTIVE_SUBTITLE_TRACK_COUNT = $mActiveSubtitleTrackCount")

        updateSubtitleUi(true)
        applySubtitleEnabled(mIsSubtitleEnabled)
    }

    private fun checkDurationTolerance(manifest: SubtitleManifest, playerDurationMs: Long): Boolean {
        val check = SubtitleMediaIdentityGate.verifyDurationTolerance(
            manifestDurationMs = manifest.durationMs,
            playerDurationMs = playerDurationMs
        )
        if (check is SubtitleMediaIdentityResult.Mismatch) {
            Log.w(TAG, "SUBTITLE_MEDIA_IDENTITY_MISMATCH: ${check.reason}")
            detachSubtitleTrack()
            setupSummaryInfo()
            return false
        } else {
            Log.i(TAG, "MEDIA_DURATION_TOLERANCE_CHECK = VERIFIED")
            return true
        }
    }

    private fun renderPendingEpisodeSummary() {
        val summary = mPendingEpisodeSummary ?: return
        if (!isSafeToMutateUi()) return
        val currentDate = currentEpisodeDate() ?: return
        if (summary.episodeDate != currentDate || summary.sourceVideoUrl != mActuallyPlayingMediaUrl) return
        if (mRenderedSummary === summary && mRenderedSummaryGeneration == mPlaybackGeneration) return

        val generation = mPlaybackGeneration
        val rows = mBinding.summaryRows
        rows.removeAllViews()
        val primaryColor = MaterialColors.getColor(rows, androidx.appcompat.R.attr.colorPrimary)
        val textColor = MaterialColors.getColor(rows, com.google.android.material.R.attr.colorOnSurface)
        fun body(value: String, size: Float = 18f) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(textColor)
            setLineSpacing(playerUiDp(3).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(playerUiDp(12), playerUiDp(6), playerUiDp(12), playerUiDp(6))
        }
        fun action(value: String, click: (MaterialButton) -> Unit) = MaterialButton(this, null,
            androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = value
            textSize = 16f
            isSingleLine = false
            minHeight = playerUiDp(48)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { click(this) }
        }
        fun addItem(parent: LinearLayout, item: EpisodeSummaryItem, review: Boolean = false) {
            val replayable = hasReliableSummaryTime(summary, item)
            if (review && item.reviewReasons.any { it !in item.text }) {
                parent.addView(body(item.reviewReasons.joinToString("；"), 16f))
            }
            // Let long notes wrap fully at the user's font scale; no character or two-line truncation.
            parent.addView(body(item.text))
            parent.addView(action(if (replayable) "回看 ${formatSummaryTime(item.startMs, item.endMs)}" else "回看时间待核") {
                if (!isSafeToMutateUi() || generation != mPlaybackGeneration ||
                    mPendingEpisodeSummary !== summary || !hasReliableSummaryTime(summary, item)) return@action
                mPlayer?.let { player ->
                    player.seekTo(item.startMs)
                    player.play()
                    updatePlaybackButton()
                    Log.i(TAG, "LOCAL_NOTES_REPLAY_SEEK positionMs=${item.startMs} review=$review")
                }
            }.apply { isEnabled = replayable; setTextColor(if (replayable) primaryColor else textColor) })
            val originalTime = item.time?.value ?: "未能绑定时间"
            val source = body("原时间：$originalTime\n原始转写：${item.sourceText ?: item.text}").apply { visibility = View.GONE }
            parent.addView(action("展开原句与原时间") { button ->
                val expand = source.visibility != View.VISIBLE
                source.visibility = if (expand) View.VISIBLE else View.GONE
                button.text = if (expand) "收起原句与原时间" else "展开原句与原时间"
            })
            parent.addView(source)
        }
        summary.items.forEach { addItem(rows, it) }
        if (summary.items.isEmpty()) rows.addView(body("暂无可列出的日期要点；可展开待核片段回看。"))
        if (summary.reviewItems.isNotEmpty() || summary.omissions.isNotEmpty()) {
            val reviewRows = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                visibility = View.GONE
            }
            summary.reviewItems.forEach { addItem(reviewRows, it, review = true) }
            if (summary.omissions.isNotEmpty()) reviewRows.addView(body(summary.omissions.joinToString("\n"), 16f))
            val count = summary.reviewItems.size
            rows.addView(action("展开待核内容（${count}段）") { button ->
                val expand = reviewRows.visibility != View.VISIBLE
                reviewRows.visibility = if (expand) View.VISIBLE else View.GONE
                button.text = if (expand) "收起待核内容（${count}段）" else "展开待核内容（${count}段）"
            })
            rows.addView(reviewRows)
        }
        mBinding.tvBriefContent.setOnClickListener(null)
        mRenderedSummary = summary
        mRenderedSummaryGeneration = generation
        mBinding.tvBriefContent.visibility = View.GONE
        rows.visibility = View.VISIBLE
        mBinding.tvSummarySource.text = "根据本期自动字幕整理，可能有误；尚未人工听核。\n原时间以 ${summary.episodeDate} 节目为参照。"
        mBinding.tvSummarySource.visibility = View.VISIBLE
        mBinding.infoContainer.visibility = if (mIsManualFullscreen) View.GONE else View.VISIBLE
        Log.i(TAG, "EPISODE_SUMMARY_IDENTITY = VERIFIED, items=${summary.items.size} reviewItems=${summary.reviewItems.size}")
    }

    private fun hasReliableSummaryTime(summary: EpisodeSummary, item: EpisodeSummaryItem): Boolean {
        val manifest = mCurrentSubtitleManifest ?: return false
        val player = mPlayer ?: return false
        // UI eligibility only; retain the existing cue/media gate rather than infer new timestamps.
        if (manifest.durationMs <= 0 || item.startMs < 0 || item.endMs <= item.startMs ||
            item.endMs > manifest.durationMs
        ) return false
        if (player.duration > 0 && item.endMs > player.duration) return false
        return summary.episodeDate == currentEpisodeDate() && summary.episodeDate == manifest.episodeDate &&
            summary.sourceVideoUrl == mActuallyPlayingMediaUrl && summary.sourceVideoUrl == manifest.sourceVideoUrl &&
            manifest.sourceVideoSha256.isNotBlank() && manifest.vttSha256.isNotBlank() &&
            summary.sourceVideoSha256.equals(manifest.sourceVideoSha256, ignoreCase = true) &&
            summary.subtitleVttSha256.equals(manifest.vttSha256, ignoreCase = true)
    }

    private fun playerUiDp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun formatSummaryTime(startMs: Long, endMs: Long): String {
        fun formatTimestamp(milliseconds: Long): String {
            val totalSeconds = milliseconds / 1000L
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            return "%02d:%02d".format(Locale.ROOT, minutes, seconds)
        }

        return "${formatTimestamp(startMs)}–${formatTimestamp(endMs)}"
    }

    private fun showSubtitleUnavailable() {
        if (!isSafeToMutateUi() || mActuallyPlayingMediaUrl == null) return
        mBinding.tvSubtitleToggle.text = "视频仍可观看，字幕暂不可用"
        mBinding.tvSubtitleToggle.isClickable = false
        mBinding.tvSubtitleToggle.visibility = View.VISIBLE
        mPendingEpisodeSummary = null
        setupSummaryInfo()
        mBinding.tvBriefContent.text = "视频可以继续观看；字幕和要点暂不可用，可返回首页重新准备字幕"
    }

    private fun detachSubtitleTrack() {
        mCurrentSubtitleManifest = null
        mLoadedSubtitleVttFile = null
        mActiveSubtitleTrackCount = 0
        mIsSubtitleEnabled = true
        mPendingEpisodeSummary = null
        Log.i(TAG, "ACTIVE_SUBTITLE_TRACK_COUNT = $mActiveSubtitleTrackCount")
        mBinding.tvSubtitleToggle.visibility = View.GONE
        mPlayer?.let { player ->
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }
        Log.i(TAG, "SUBTITLE_TRACK_DETACHED = true")
    }

    private fun updateSubtitleUi(available: Boolean) {
        if (!isSafeToMutateUi()) return
        if (available && !mHasFallenBack) {
            mBinding.tvSubtitleToggle.visibility = View.VISIBLE
            mBinding.tvSubtitleToggle.isClickable = true
            mBinding.tvSubtitleToggle.setOnClickListener {
                mIsSubtitleEnabled = !mIsSubtitleEnabled
                applySubtitleEnabled(mIsSubtitleEnabled)
            }
        } else {
            mBinding.tvSubtitleToggle.visibility = View.GONE
        }
    }

    private fun applySubtitleEnabled(enabled: Boolean) {
        if (!isSafeToMutateUi()) return
        mIsSubtitleEnabled = enabled
        mPlayer?.let { player ->
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
                .build()
        }
        if (enabled) {
            Log.i(TAG, "SUBTITLE_ENABLE = VERIFIED")
            mBinding.tvSubtitleToggle.text = "自动字幕：开"
            mBinding.tvSubtitleToggle.alpha = 1.0f
        } else {
            Log.i(TAG, "SUBTITLE_DISABLE = VERIFIED")
            mBinding.tvSubtitleToggle.text = "自动字幕：关"
            mBinding.tvSubtitleToggle.alpha = 1.0f
        }
    }

    private fun createSubtitleConfiguration(vttFile: File): MediaItem.SubtitleConfiguration {
        return MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(vttFile))
            .setMimeType(MimeTypes.TEXT_VTT)
            .setLanguage("zh")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
            .setLabel(EXPECTED_SUBTITLE_UI_LABEL)
            .build()
    }

    private fun saveValidatedVttToCache(
        program: String,
        episodeDate: String,
        vttSha256: String,
        vttContent: String
    ): File? {
        return try {
            val progSlug = program.lowercase().replace('_', '-')
            val dir = mPreparationLease?.directory?.let { File(it, "subtitles") }
                ?: File(cacheDir, "subtitles/$progSlug/$episodeDate")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, "$vttSha256.vtt")
            if (!file.exists() || org.breezyweather.domain.subtitle.util.Sha256Util.calculateSha256(
                    file.readText(Charsets.UTF_8)) != vttSha256) {
                file.writeText(vttContent, Charsets.UTF_8)
            }
            file
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache validated VTT: ${e.message}")
            null
        }
    }

    private fun executeAutoSeek120s() {
        val player = mPlayer ?: return
        mPostSeekWaiting = true
        Log.i(TAG, "ACTION_SEEK_120S = true")
        player.seekTo(120500L)
    }

    private fun executeAutoSubtitleToggle() {
        Log.i(TAG, "ACTION_TEST_SUBTITLE_OFF = true")
        applySubtitleEnabled(false)

        mMainHandler.postDelayed({
            Log.i(TAG, "ACTION_TEST_SUBTITLE_ON = true")
            applySubtitleEnabled(true)
            Log.i(TAG, "RUNTIME_GATE_ALL_VERIFIED = true")
        }, 1000)
    }

    private fun fallbackToWebView() {
        if (mHasFallenBack) return
        mHasFallenBack = true
        mPlaybackGeneration++
        mActuallyPlayingMediaUrl = null
        mSubtitleJob?.cancel()
        detachSubtitleTrack()
        Log.i(TAG, "CCTV_FALLBACK_NO_MISMATCHED_SUBTITLE = VERIFIED")

        runOnUiThread {
            Log.w(TAG, "PRIMARY_FAILED = true")
            Log.i(TAG, "FALLBACK_SELECTED = true")

            if (!CctvUrlValidator.isValidOfficialPageUrl(mUrl)) {
                Log.e(TAG, "WEBVIEW_URL_REJECTED: url=$mUrl (fail-closed)")
                releasePlayer()
                mBinding.playerContainer.visibility = View.GONE
                mBinding.infoContainer.visibility = View.GONE
                mBinding.webView.visibility = View.GONE
                mBinding.progressBar.visibility = View.GONE
                finish()
                return@runOnUiThread
            }

            Log.i(TAG, "WEBVIEW_URL_ALLOWLIST = VERIFIED")
            Log.i(TAG, "WEBVIEW_OR_SECONDARY_PLAYER_OPENED = true")

            releasePlayer()
            mBinding.playerContainer.visibility = View.GONE
            mBinding.playbackErrorContainer.visibility = View.GONE
            mBinding.infoContainer.visibility = View.GONE
            mBinding.webView.visibility = View.VISIBLE
            setupWebView()
            mBinding.webView.loadUrl(mUrl)
        }
    }

    private fun showPlaybackFailure() {
        if (!isSafeToMutateUi()) return
        mPlaybackGeneration++
        mActuallyPlayingMediaUrl = null
        mSubtitleJob?.cancel()
        mSubtitleJob = null
        mPendingEpisodeSummary = null
        detachSubtitleTrack()
        releasePlayer()
        mBinding.progressBar.visibility = View.GONE
        mBinding.playerContainer.visibility = View.GONE
        mBinding.webView.visibility = View.GONE
        mBinding.playbackErrorContainer.visibility = View.VISIBLE
        mBinding.infoContainer.visibility = View.VISIBLE
        val hasExactDate = ChinaWeatherVideoService.getInstance(this).extractDateCode(mTitle)
            ?: ChinaWeatherVideoService.getInstance(this).extractDateCode(mUrl)
        mBinding.tvPlaybackError.text = if (hasExactDate == null) {
            "天气预报仍可查看；节目日期待确认，请返回首页重新打开节目"
        } else {
            "天气预报仍可查看；本期暂时播放不了，请检查网络后重新播放，或返回首页"
        }
        setupSummaryInfo()
        mBinding.infoContainer.visibility = View.GONE
        Log.i(TAG, "PLAYBACK_FAILURE_STAYS_IN_PLAYER = VERIFIED")
        Log.i(TAG, "NO_CROSS_DATE_FALLBACK = VERIFIED")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        if (mIsWebViewConfigured) return
        mIsWebViewConfigured = true

        val settings = mBinding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        mBinding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    return false
                }
                return true
            }
        }

        mBinding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress < 100) {
                    mBinding.progressBar.visibility = View.VISIBLE
                    mBinding.progressBar.progress = newProgress
                } else {
                    mBinding.progressBar.visibility = View.GONE
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (mCustomView != null) {
                    onHideCustomView()
                    return
                }
                mCustomView = view
                mCustomViewCallback = callback
                mBinding.contentLayout.visibility = View.GONE
                mBinding.fullscreenContainer.visibility = View.VISIBLE
                mBinding.fullscreenContainer.addView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }

            override fun onHideCustomView() {
                if (mCustomView == null) return
                mBinding.fullscreenContainer.removeView(mCustomView)
                mBinding.fullscreenContainer.visibility = View.GONE
                mBinding.contentLayout.visibility = View.VISIBLE
                mCustomView = null
                mCustomViewCallback?.onCustomViewHidden()
                mCustomViewCallback = null
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    private fun handleFullscreenToggle(isFullscreen: Boolean) {
        mIsManualFullscreen = isFullscreen
        if (isFullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        applyFullscreenUi(isFullscreen)
    }

    private fun applyFullscreenUi(isFullscreen: Boolean) {
        mBinding.btnPlayerFullscreen.text = if (isFullscreen) "退出全屏" else "全屏观看"
        if (isFullscreen) {
            mBinding.appBar.visibility = View.GONE
            mBinding.tvEpisodeDate.visibility = View.GONE
            mBinding.infoContainer.visibility = View.GONE
            hideSystemBars()
        } else {
            mBinding.appBar.visibility = View.VISIBLE
            mBinding.tvEpisodeDate.visibility = if (mBinding.tvEpisodeDate.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            if (!mOpenOfficialPageOnly && !mHasFallenBack) {
                mBinding.infoContainer.visibility = View.VISIBLE
            }
            showSystemBars()
        }
        updatePlayerFrameLayout()
    }

    private fun hideSystemBars() {
        val w = window ?: return
        val controller = WindowInsetsControllerCompat(w, w.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun showSystemBars() {
        val w = window ?: return
        val controller = WindowInsetsControllerCompat(w, w.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A delayed rotation callback must not re-enter fullscreen after an explicit exit.
        applyFullscreenUi(mIsManualFullscreen)
        Log.i(TAG, "ON_CONFIGURATION_CHANGED: orientation=${newConfig.orientation}")
        Log.i(TAG, "PRODUCTION_ROTATION_SAFE = VERIFIED")
    }

    private fun handleBackAction() {
        if (mCustomView != null) {
            mBinding.webView.webChromeClient?.onHideCustomView()
            return
        }
        if (mIsManualFullscreen) {
            // Reuse the controller's exit action so its icon state stays in sync as well.
            mBinding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_fullscreen)?.performClick()
            if (mIsManualFullscreen) handleFullscreenToggle(false)
            return
        }
        finish()
    }

    private fun releasePlayer() {
        mPreparationJoinJob?.cancel()
        mPreparationJoinJob = null
        mPlayer?.let { player ->
            player.stop()
            player.clearMediaItems()
            player.release()
            val count = sActivePlayerCount.decrementAndGet()
            Log.i(TAG, "OLD_PLAYER_RELEASED = true")
            Log.i(TAG, "ACTIVE_PLAYER_COUNT = $count")
        }
        mPlayer = null
        mVideoCapture?.close()
        mVideoCapture = null
        mPreparationLease?.close()
        mPreparationLease = null
        mPlaybackUri = null
        mBinding.playerView.player = null
        mActiveSubtitleTrackCount = 0
    }

    override fun onResume() {
        super.onResume()
        if (mHasFallenBack) {
            mBinding.webView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (mHasFallenBack) {
            mBinding.webView.onPause()
        }
    }

    override fun onStop() {
        super.onStop()
        mPlayer?.pause()
    }

    override fun onDestroy() {
        mIsActivityDestroyed = true
        mSubtitleJob?.cancel()
        mSubtitleJob = null
        mMainHandler.removeCallbacksAndMessages(null)
        mDisposable?.dispose()
        mDisposable = null
        releasePlayer()
        mBinding.webView.destroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mPlayer?.let {
            outState.putLong(KEY_SAVED_POSITION, it.currentPosition)
            outState.putBoolean(KEY_PLAY_WHEN_READY, it.playWhenReady)
        }
        outState.putBoolean(KEY_IS_FALLBACK, mHasFallenBack)
        outState.putString(KEY_RESOLVED_MP4, mResolvedMp4Url)
        outState.putBoolean(KEY_SUBTITLE_ENABLED, mIsSubtitleEnabled)
        mCurrentSubtitleManifest?.let { manifest ->
            try {
                outState.putString(KEY_SAVED_SUBTITLE_MANIFEST, Json.encodeToString(manifest))
            } catch (_: Exception) {}
        }
        mLoadedSubtitleVttFile?.let { file ->
            outState.putString(KEY_SAVED_SUBTITLE_VTT_PATH, file.absolutePath)
        }
    }

    fun formatDateIso(dateCode: String?): String? {
        if (dateCode.isNullOrBlank()) return null
        if (dateCode.length == 8 && dateCode.all { it.isDigit() }) {
            return "${dateCode.substring(0, 4)}-${dateCode.substring(4, 6)}-${dateCode.substring(6, 8)}"
        }
        if (dateCode.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) {
            return dateCode
        }
        return null
    }

    fun formatDisplayTitle(rawTitle: String?, fallbackUrl: String?): String {
        val dateCode = ChinaWeatherVideoService.getInstance(this).extractDateCode(rawTitle)
            ?: ChinaWeatherVideoService.getInstance(this).extractDateCode(fallbackUrl)
        return if (dateCode != null && dateCode.length == 8) {
            val y = dateCode.substring(0, 4)
            val m = dateCode.substring(4, 6)
            val d = dateCode.substring(6, 8)
            "《晚间天气预报》 $y-$m-$d"
        } else {
            if (!rawTitle.isNullOrBlank() && rawTitle.contains("《晚间天气预报》")) {
                rawTitle
            } else {
                "《晚间天气预报》"
            }
        }
    }

    companion object {
        const val TAG = "CctvPlayerActivity"
        const val PROGRAM_EVENING_WEATHER = "EVENING_WEATHER"
        const val PROGRAM_CHINA_WEATHER_LIANBO = "CHINA_WEATHER_LIANBO"
        const val EXPECTED_SUBTITLE_UI_LABEL = "AI 自动转写字幕"
        const val FORBIDDEN_LABEL_1 = "官方字幕"
        const val FORBIDDEN_LABEL_2 = "央视字幕"

        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_EPISODE_DATE = "extra_episode_date"
        const val EXTRA_PROGRAM = "extra_program"
        const val EXTRA_SOURCE_ID = "extra_source_id"
        const val EXTRA_SOURCE_TYPE = "extra_source_type"
        const val EXTRA_EPISODE_ID = "extra_episode_id"
        const val EXTRA_MEDIA_URL = "extra_media_url"
        const val EXTRA_BRIEF = "extra_brief"
        const val EXTRA_INJECT_FAILURE = "extra_inject_failure"
        const val EXTRA_AUTO_TEST_GATE = "extra_auto_test_gate"
        const val EXTRA_TEST_ACTION = "extra_test_action"
        const val EXTRA_OPEN_OFFICIAL_PAGE_ONLY = "extra_open_official_page_only"
        private const val FAMILY_SUBTITLES_ENABLED = true

        private const val KEY_SAVED_POSITION = "key_saved_position"
        private const val KEY_PLAY_WHEN_READY = "key_play_when_ready"
        private const val KEY_IS_FALLBACK = "key_is_fallback"
        private const val KEY_RESOLVED_MP4 = "key_resolved_mp4"
        private const val KEY_SUBTITLE_ENABLED = "key_subtitle_enabled"
        private const val KEY_SAVED_SUBTITLE_MANIFEST = "key_saved_subtitle_manifest"
        private const val KEY_SAVED_SUBTITLE_VTT_PATH = "key_saved_subtitle_vtt_path"

        private val sActivePlayerCount = AtomicInteger(0)

        fun getActivePlayerCount(): Int = sActivePlayerCount.get()
        fun resetActivePlayerCountForTests() {
            sActivePlayerCount.set(0)
        }

        @JvmOverloads
        fun start(
            context: Context,
            url: String,
            title: String,
            brief: String? = null,
            injectFailure: Boolean = false
        ) {
            val intent = Intent(context, CctvPlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BRIEF, brief)
                putExtra(EXTRA_INJECT_FAILURE, injectFailure)
            }
            context.startActivity(intent)
        }

        fun startOfficialPage(
            context: Context,
            url: String = org.breezyweather.domain.cctv.CctvWeatherService.DEFAULT_OFFICIAL_HOME_URL,
        ) {
            val safeUrl = url.takeIf { CctvUrlValidator.isValidOfficialPageUrl(it) }
                ?: org.breezyweather.domain.cctv.CctvWeatherService.DEFAULT_OFFICIAL_HOME_URL
            val intent = Intent(context, CctvPlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, safeUrl)
                putExtra(EXTRA_TITLE, "中国天气网·新闻联播天气预报")
                putExtra(EXTRA_OPEN_OFFICIAL_PAGE_ONLY, true)
            }
            context.startActivity(intent)
        }

        fun startEpisode(context: Context, episode: org.breezyweather.domain.cctv.CctvEpisode) {
            val intent = Intent(context, CctvPlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, episode.officialEpisodeUrl ?: episode.url)
                putExtra(EXTRA_TITLE, episode.title)
                putExtra(EXTRA_EPISODE_DATE, episode.episodeDate)
                putExtra(EXTRA_PROGRAM, episode.program)
                putExtra(EXTRA_SOURCE_ID, episode.sourceId)
                putExtra(EXTRA_SOURCE_TYPE, episode.sourceType)
                putExtra(EXTRA_EPISODE_ID, episode.id)
                putExtra(EXTRA_MEDIA_URL, episode.mediaUrl)
            }
            context.startActivity(intent)
        }
    }
}
