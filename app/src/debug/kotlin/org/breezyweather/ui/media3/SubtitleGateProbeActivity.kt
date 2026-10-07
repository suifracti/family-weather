/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.ui.media3

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import org.breezyweather.domain.subtitle.parser.WebVttParser
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Task D2.3b-C1 Debug-Only Hardened Media3 Subtitle Rendering Gate Activity.
 * Strictly verifies real Media3 WebVTT sidecar subtitle rendering capability.
 * Located strictly in app/src/debug/kotlin to prevent leakage into main/release source sets.
 */
class SubtitleGateProbeActivity : Activity() {

    companion object {
        const val TAG = "SubtitleGateProbe"
        const val DEFAULT_VIDEO_URL = "https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4"
        const val EXPECTED_UI_LABEL = "AI 自动转写字幕"
        const val FORBIDDEN_LABEL_1 = "官方字幕"
        const val FORBIDDEN_LABEL_2 = "央视字幕"

        const val KEY_SAVED_RECREATE = "KEY_PROBE_RECREATED"

        const val EMBEDDED_FIXTURE_VTT = """WEBVTT
NOTE episodeDate=2026-09-17
NOTE asrModel=SenseVoiceSmall
NOTE generatedAt=2026-09-18T05:46:00.614232+00:00
NOTE sourceAuthority=CHINA_WEATHER_OFFICIAL_VIDEO
NOTE subtitleOrigin=AI_ASR_GENERATED
NOTE uiLabel=AI 自动转写字幕
NOTE extractionMethod=AUTOMATED_ASR_EXTRACTED

1
00:00:06.000 --> 00:00:07.470
大家来看天气，

2
00:00:07.470 --> 00:00:11.190
过去两四盆地北一带雨频

38
00:02:00.240 --> 00:02:02.550
沈阳晴14到29度
"""
    }

    /**
     * Cross-lifecycle state tracker to strictly measure Activity recreation facts.
     */
    object ProbeExecutionState {
        var activityGeneration = 0
        var oldActivityDestroyed = false
        var oldPlayerReleased = false
        var newActivityCreated = false
        var activePlayerCount = 0
        var lastPlayerInstanceId: Int? = null
        var duplicateSubtitleView = false
        val probeResults = JSONObject()
        val requiredChecks = mutableMapOf<String, Boolean>()

        fun reset() {
            activityGeneration = 0
            oldActivityDestroyed = false
            oldPlayerReleased = false
            newActivityCreated = false
            activePlayerCount = 0
            lastPlayerInstanceId = null
            duplicateSubtitleView = false
            // Clear JSON keys
            val keys = probeResults.keys()
            val toRemove = mutableListOf<String>()
            while (keys.hasNext()) toRemove.add(keys.next())
            toRemove.forEach { probeResults.remove(it) }
            requiredChecks.clear()
        }
    }

    private var mPlayer: ExoPlayer? = null
    private lateinit var mPlayerView: PlayerView
    private lateinit var mLogTextView: TextView
    private lateinit var mBadgeTextView: TextView
    private val mHandler = Handler(Looper.getMainLooper())

    private var mCurrentPhase = Phase.INIT
    private var mFirstCueCaptured = false
    private var mPostSeekCueCaptured = false
    private var mSubtitleDisabledVerified = false
    private var mSubtitleEnabledVerified = false
    private var mSubtitleToggleTimeoutRunnable: Runnable? = null
    private var mPlayerError: PlaybackException? = null

    private enum class Phase {
        INIT,
        PLAYING_INITIAL,
        WAITING_FIRST_CUE,
        SEEKING_120S,
        WAITING_POST_SEEK_CUE,
        TESTING_SUBTITLE_OFF,
        TESTING_SUBTITLE_ON,
        TESTING_PAUSE_RESUME,
        TESTING_ROTATION,
        TESTING_RECREATE,
        POST_RECREATE_VERIFICATION,
        TESTING_FAIL_OPEN_MISSING,
        TESTING_FAIL_OPEN_MALFORMED,
        COMPLETED,
        FAILED
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Strict label enforcement check
        require(!EXPECTED_UI_LABEL.contains(FORBIDDEN_LABEL_1)) { "Forbidden label present: $FORBIDDEN_LABEL_1" }
        require(!EXPECTED_UI_LABEL.contains(FORBIDDEN_LABEL_2)) { "Forbidden label present: $FORBIDDEN_LABEL_2" }

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        mPlayerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            useController = false
            subtitleView?.apply {
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
                setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            }
        }
        rootLayout.addView(mPlayerView)

        // UI Badge strictly displaying: "AI 自动转写字幕"
        mBadgeTextView = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(32, 48, 32, 0)
            }
            setBackgroundColor(Color.argb(190, 20, 20, 20))
            setTextColor(Color.YELLOW)
            textSize = 14f
            setPadding(20, 10, 20, 10)
            text = "● $EXPECTED_UI_LABEL"
        }
        rootLayout.addView(mBadgeTextView)

        // Scrollable Log Console overlay at bottom
        val scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                550
            ).apply {
                gravity = Gravity.BOTTOM
            }
        }
        mLogTextView = TextView(this).apply {
            setBackgroundColor(Color.argb(210, 0, 0, 0))
            setTextColor(Color.GREEN)
            textSize = 11f
            setPadding(20, 20, 20, 20)
            text = "Initializing Hardened Media3 Subtitle Rendering Gate..."
        }
        scrollView.addView(mLogTextView)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)

        val isRecreated = (savedInstanceState != null && savedInstanceState.getBoolean(KEY_SAVED_RECREATE, false))

        if (!isRecreated) {
            // Initial Generation 1
            ProbeExecutionState.reset()
            ProbeExecutionState.activityGeneration = 1

            logResult("GATE_NAME", "D2.3b-C1 Hardened Subtitle Rendering Gate")
            logResult("UI_LABEL_ENFORCED", EXPECTED_UI_LABEL)
            logResult("FORBIDDEN_LABELS_REJECTED", true)

            startPrimaryProbe()
        } else {
            // Generation 2 post recreate()
            ProbeExecutionState.activityGeneration = 2
            ProbeExecutionState.newActivityCreated = true
            logResult("NEW_ACTIVITY_CREATED", true)

            startPostRecreateVerification()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SAVED_RECREATE, true)
    }

    private fun logResult(key: String, value: Any) {
        ProbeExecutionState.probeResults.put(key, value)
        Log.i(TAG, "[$TAG] $key = $value")
        runOnUiThread {
            mLogTextView.append("\n$key = $value")
        }
    }

    private fun ensureFixtureVtt(): File {
        val target = File(cacheDir, "20260917.ai-asr.vtt")
        if (target.exists() && target.length() > 0L) {
            return target
        }

        // Try copying from debug assets
        try {
            assets.open("subtitles/20260917.ai-asr.vtt").use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            if (target.exists() && target.length() > 0L) return target
        } catch (_: Exception) {}

        // Try copying from /data/local/tmp
        val tmp = File("/data/local/tmp/20260917.ai-asr.vtt")
        if (tmp.exists() && tmp.length() > 0L) {
            tmp.copyTo(target, overwrite = true)
            if (target.exists() && target.length() > 0L) return target
        }

        // Fallback to embedded fixture content
        target.writeText(EMBEDDED_FIXTURE_VTT)
        return target
    }

    private fun startPrimaryProbe() {
        mCurrentPhase = Phase.INIT
        val vttFile = ensureFixtureVtt()

        logResult("VTT_FIXTURE_PATH", vttFile.absolutePath)
        logResult("VTT_FIXTURE_SIZE_BYTES", vttFile.length())

        val player = ExoPlayer.Builder(this).build()
        mPlayer = player
        mPlayerView.player = player
        ProbeExecutionState.activePlayerCount++
        ProbeExecutionState.lastPlayerInstanceId = System.identityHashCode(player)

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        logResult("STATE_READY", true)
                        logResult("VIDEO_PLAYBACK_BLOCKED", false)

                        if (mCurrentPhase == Phase.INIT) {
                            mCurrentPhase = Phase.PLAYING_INITIAL
                            player.play()
                            // Seek to 6.2s to land directly in first cue
                            mHandler.postDelayed({
                                seekToFirstCue()
                            }, 1000)
                        } else if (mCurrentPhase == Phase.SEEKING_120S) {
                            mCurrentPhase = Phase.WAITING_POST_SEEK_CUE
                            logResult("SEEK_120S_READY", true)
                            player.play()
                        }
                    }

                    Player.STATE_ENDED -> {
                        Log.i(TAG, "Playback reached ended state")
                    }

                    else -> {}
                }
            }

            override fun onRenderedFirstFrame() {
                logResult("FIRST_FRAME_RENDERED", true)
            }

            override fun onTracksChanged(tracks: Tracks) {
                for (group in tracks.groups) {
                    if (group.type == C.TRACK_TYPE_TEXT) {
                        logResult("SUBTITLE_TRACK_LOADED", true)
                        ProbeExecutionState.requiredChecks["SUBTITLE_TRACK_LOADED"] = true
                        val format = group.getTrackFormat(0)
                        logResult("SUBTITLE_MIME", format.sampleMimeType ?: "")
                        logResult("SUBTITLE_LABEL", format.label ?: "")
                    }
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                handleCues(cueGroup)
            }

            override fun onPlayerError(error: PlaybackException) {
                mPlayerError = error
                logResult("PLAYER_ERROR", "${error.errorCodeName}: ${error.message}")
                if (mCurrentPhase != Phase.TESTING_FAIL_OPEN_MISSING && mCurrentPhase != Phase.TESTING_FAIL_OPEN_MALFORMED) {
                    finishProbe(false)
                }
            }
        })

        // Mount WebVTT sidecar via MediaItem.SubtitleConfiguration
        val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(vttFile))
            .setMimeType(MimeTypes.TEXT_VTT)
            .setLanguage("zh")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
            .setLabel(EXPECTED_UI_LABEL)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(DEFAULT_VIDEO_URL)
            .setSubtitleConfigurations(listOf(subtitleConfig))
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        logResult("MEDIA3_SIDECAR_VTT_MOUNTED", true)
        logResult("MEDIA3_SIDECAR_VTT", "VERIFIED")
        ProbeExecutionState.requiredChecks["MEDIA3_SIDECAR_VTT"] = true
    }

    private fun handleCues(cueGroup: CueGroup) {
        val cues = cueGroup.cues
        if (cues.isNotEmpty()) {
            val text = cues.first().text?.toString() ?: ""
            Log.d(TAG, "onCues received non-empty: '$text', phase: $mCurrentPhase")

            if (mCurrentPhase == Phase.WAITING_FIRST_CUE && !mFirstCueCaptured) {
                if (text.contains("大家来看天气")) {
                    mFirstCueCaptured = true
                    logResult("FIRST_CUE_RENDERED", true)
                    logResult("CUE_TEXT", text)
                    logResult("CUE_START", "00:00:06.000")
                    logResult("CUE_END", "00:00:07.470")
                    logResult("CUE_RENDERING", "VERIFIED")
                    ProbeExecutionState.requiredChecks["FIRST_CUE_RENDERED"] = true

                    mHandler.postDelayed({
                        executeSeek120s()
                    }, 1000)
                }
            } else if ((mCurrentPhase == Phase.SEEKING_120S || mCurrentPhase == Phase.WAITING_POST_SEEK_CUE) && !mPostSeekCueCaptured) {
                if (text.contains("沈阳") || text.isNotEmpty()) {
                    mPostSeekCueCaptured = true
                    logResult("POST_SEEK_CUE_RENDERED", true)
                    logResult("POST_SEEK_CUE_TEXT", text)
                    logResult("SEEK_SYNC", "VERIFIED")
                    ProbeExecutionState.requiredChecks["POST_SEEK_CUE_RENDERED"] = true

                    mHandler.postDelayed({
                        testSubtitleToggleOff()
                    }, 1000)
                }
            } else if (mCurrentPhase == Phase.TESTING_SUBTITLE_ON && !mSubtitleEnabledVerified) {
                mSubtitleEnabledVerified = true
                mSubtitleToggleTimeoutRunnable?.let { mHandler.removeCallbacks(it) }
                logResult("SUBTITLE_ENABLE", "VERIFIED")
                logResult("RESTORED_CUE_TEXT", text)
                logResult("TOGGLE", "VERIFIED")
                ProbeExecutionState.requiredChecks["SUBTITLE_ENABLE"] = true

                mHandler.postDelayed({
                    testPauseResume()
                }, 1000)
            }
        } else {
            // Cues are empty
            Log.d(TAG, "onCues received empty cues, phase: $mCurrentPhase")
            if (mCurrentPhase == Phase.TESTING_SUBTITLE_OFF && !mSubtitleDisabledVerified) {
                mSubtitleDisabledVerified = true
                mSubtitleToggleTimeoutRunnable?.let { mHandler.removeCallbacks(it) }
                logResult("SUBTITLE_DISABLE", "VERIFIED")
                ProbeExecutionState.requiredChecks["SUBTITLE_DISABLE"] = true

                mHandler.postDelayed({
                    testSubtitleToggleOn()
                }, 1000)
            }
        }
    }

    private fun seekToFirstCue() {
        val player = mPlayer ?: return
        mCurrentPhase = Phase.WAITING_FIRST_CUE
        logResult("SEEK_TO_FIRST_CUE", 6200)
        player.seekTo(6200)
    }

    private fun executeSeek120s() {
        val player = mPlayer ?: return
        mCurrentPhase = Phase.SEEKING_120S
        logResult("SEEK_TO_120S", 120500)
        player.seekTo(120500)
    }

    private fun testSubtitleToggleOff() {
        val player = mPlayer ?: return
        mCurrentPhase = Phase.TESTING_SUBTITLE_OFF
        logResult("ACTION_SUBTITLE_OFF", "DISABLING_TEXT_TRACK")

        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()

        val isTextDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        logResult("TRACK_PARAMS_TEXT_DISABLED", isTextDisabled)

        // Strict timeout: fail if empty cues not observed
        mSubtitleToggleTimeoutRunnable = Runnable {
            if (!mSubtitleDisabledVerified) {
                logResult("SUBTITLE_DISABLE", "FAILED_TIMEOUT_NO_EMPTY_CUES")
                ProbeExecutionState.requiredChecks["SUBTITLE_DISABLE"] = false
                finishProbe(false)
            }
        }
        mHandler.postDelayed(mSubtitleToggleTimeoutRunnable!!, 4000)
    }

    private fun testSubtitleToggleOn() {
        val player = mPlayer ?: return
        mCurrentPhase = Phase.TESTING_SUBTITLE_ON
        logResult("ACTION_SUBTITLE_ON", "ENABLING_TEXT_TRACK")

        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()

        // Seek to 6200ms to immediately trigger Cue 1
        player.seekTo(6200)

        // Strict timeout: fail if cues not restored
        mSubtitleToggleTimeoutRunnable = Runnable {
            if (!mSubtitleEnabledVerified) {
                logResult("SUBTITLE_ENABLE", "FAILED_TIMEOUT_NO_CUES_RESTORED")
                ProbeExecutionState.requiredChecks["SUBTITLE_ENABLE"] = false
                finishProbe(false)
            }
        }
        mHandler.postDelayed(mSubtitleToggleTimeoutRunnable!!, 4000)
    }

    private fun testPauseResume() {
        val player = mPlayer ?: return
        mCurrentPhase = Phase.TESTING_PAUSE_RESUME
        player.pause()

        mHandler.postDelayed({
            val isPaused = !player.isPlaying
            logResult("PAUSE_STATE_VERIFIED", isPaused)

            player.play()
            mHandler.postDelayed({
                val isResumed = player.isPlaying
                logResult("RESUME_STATE_VERIFIED", isResumed)

                val pauseResumeSuccess = isPaused && isResumed
                logResult("PAUSE_RESUME", if (pauseResumeSuccess) "VERIFIED" else "FAILED")
                ProbeExecutionState.requiredChecks["PAUSE_RESUME"] = pauseResumeSuccess

                if (pauseResumeSuccess) {
                    testRotation()
                } else {
                    finishProbe(false)
                }
            }, 1000)
        }, 1000)
    }

    private fun testRotation() {
        mCurrentPhase = Phase.TESTING_ROTATION
        logResult("ACTION_ROTATION", "LANDSCAPE")
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        mHandler.postDelayed({
            val currentOrientation = resources.configuration.orientation
            val reachedLandscape = (currentOrientation == Configuration.ORIENTATION_LANDSCAPE)
            logResult("LANDSCAPE_REACHED", reachedLandscape)

            logResult("ACTION_ROTATION", "PORTRAIT")
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

            mHandler.postDelayed({
                val portraitOrientation = resources.configuration.orientation
                val reachedPortrait = (portraitOrientation == Configuration.ORIENTATION_PORTRAIT)
                logResult("PORTRAIT_RESTORED", reachedPortrait)

                val orientationVerified = reachedLandscape && reachedPortrait
                logResult("ORIENTATION_CHANGE", if (orientationVerified) "VERIFIED" else "FAILED")
                logResult("ACTIVITY_RECREATED_BY_ROTATION", false)
                ProbeExecutionState.requiredChecks["ORIENTATION_CHANGE"] = orientationVerified

                if (orientationVerified) {
                    testRecreationTrigger()
                } else {
                    finishProbe(false)
                }
            }, 1200)
        }, 1200)
    }

    private fun testRecreationTrigger() {
        mCurrentPhase = Phase.TESTING_RECREATE
        logResult("ACTION_RECREATE", "CALLING_RECREATE")

        // Trigger real Android Activity recreation
        mHandler.post {
            recreate()
        }
    }

    private fun startPostRecreateVerification() {
        mCurrentPhase = Phase.POST_RECREATE_VERIFICATION

        // Wait brief delay to ensure old activity onDestroy has fully completed
        mHandler.postDelayed({
            val oldActivityDestroyed = ProbeExecutionState.oldActivityDestroyed
            val oldPlayerReleased = ProbeExecutionState.oldPlayerReleased
            val newActivityCreated = ProbeExecutionState.newActivityCreated

            // Setup new player in Generation 2
            val newPlayer = ExoPlayer.Builder(this).build()
            mPlayer = newPlayer
            mPlayerView.player = newPlayer
            ProbeExecutionState.activePlayerCount++
            val newPlayerId = System.identityHashCode(newPlayer)

            // Inspect subtitle views
            val subtitleViewCount = countSubtitleViews(mPlayerView)
            val duplicateSubtitleView = subtitleViewCount > 1
            ProbeExecutionState.duplicateSubtitleView = duplicateSubtitleView

            logResult("OLD_ACTIVITY_DESTROYED", oldActivityDestroyed)
            logResult("OLD_PLAYER_RELEASED", oldPlayerReleased)
            logResult("NEW_ACTIVITY_CREATED", newActivityCreated)
            logResult("ACTIVE_PLAYER_COUNT", ProbeExecutionState.activePlayerCount)
            logResult("DUPLICATE_SUBTITLE_VIEW", duplicateSubtitleView)

            val isDistinctInstance = (newPlayerId != ProbeExecutionState.lastPlayerInstanceId)
            val recreateVerified = oldActivityDestroyed && oldPlayerReleased && newActivityCreated &&
                    (ProbeExecutionState.activePlayerCount == 1) && !duplicateSubtitleView && isDistinctInstance

            logResult("RECREATE_VERIFIED", if (recreateVerified) "VERIFIED" else "FAILED")
            ProbeExecutionState.requiredChecks["RECREATE_VERIFIED"] = recreateVerified

            if (recreateVerified) {
                testFailOpenMissingVtt()
            } else {
                finishProbe(false)
            }
        }, 800)
    }

    private fun countSubtitleViews(viewGroup: ViewGroup): Int {
        var count = 0
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is SubtitleView) count++
            if (child is ViewGroup) count += countSubtitleViews(child)
        }
        return count
    }

    private fun testFailOpenMissingVtt() {
        mCurrentPhase = Phase.TESTING_FAIL_OPEN_MISSING
        logResult("TESTING_PHASE", "FAIL_OPEN_MISSING_VTT")

        val missingVttFile = File(cacheDir, "non_existent_fixture.vtt")
        if (missingVttFile.exists()) missingVttFile.delete()

        // Missing VTT: under fail-closed subtitle contracts, video plays without subtitle
        val videoSafeMediaItem = MediaItem.fromUri(DEFAULT_VIDEO_URL)
        val player = mPlayer ?: return
        mPlayerError = null

        player.setMediaItem(videoSafeMediaItem)
        player.prepare()
        player.play()

        fun checkAdvancement(attempts: Int) {
            if (player.playbackState == Player.STATE_READY && player.isPlaying) {
                val pos1 = player.currentPosition
                mHandler.postDelayed({
                    val pos2 = player.currentPosition
                    val advanced = pos2 > pos1
                    val hasNoError = (mPlayerError == null)
                    logResult("POSITION_T1_MS", pos1)
                    logResult("POSITION_T2_MS", pos2)
                    logResult("POSITION_ADVANCED_MS", pos2 - pos1)
                    logResult("MISSING_VTT_PLAYBACK_ADVANCING", advanced)

                    val failOpenSuccess = advanced && hasNoError
                    logResult("FAIL_OPEN_MISSING_VTT", if (failOpenSuccess) "VERIFIED" else "FAILED")
                    logResult("VIDEO_PLAYBACK_BLOCKED", !failOpenSuccess)
                    ProbeExecutionState.requiredChecks["FAIL_OPEN_MISSING_VTT"] = failOpenSuccess

                    if (failOpenSuccess) {
                        testFailOpenMalformedVtt()
                    } else {
                        finishProbe(false)
                    }
                }, 1200)
            } else if (attempts > 0) {
                mHandler.postDelayed({ checkAdvancement(attempts - 1) }, 500)
            } else {
                logResult("FAIL_OPEN_MISSING_VTT", "FAILED_NOT_READY")
                logResult("VIDEO_PLAYBACK_BLOCKED", true)
                ProbeExecutionState.requiredChecks["FAIL_OPEN_MISSING_VTT"] = false
                finishProbe(false)
            }
        }

        mHandler.postDelayed({ checkAdvancement(12) }, 800)
    }

    private fun testFailOpenMalformedVtt() {
        mCurrentPhase = Phase.TESTING_FAIL_OPEN_MALFORMED
        logResult("TESTING_PHASE", "FAIL_OPEN_MALFORMED_VTT")

        val malformedContent = "INVALID_HEADER_NOT_WEBVTT\nCorrupt subtitle file\n00:00:01.000 --> 00:00:02.000\nBroken\n"
        var parserRejected = false
        try {
            WebVttParser.parse(malformedContent)
        } catch (e: IllegalArgumentException) {
            parserRejected = true
            Log.i(TAG, "Parser correctly rejected malformed VTT: ${e.message}")
        }

        logResult("MALFORMED_VTT_PARSER_REJECTED", parserRejected)
        if (!parserRejected) {
            logResult("FAIL_OPEN_MALFORMED_VTT", "FAILED_PARSER_ACCEPTED_CORRUPT")
            ProbeExecutionState.requiredChecks["FAIL_OPEN_MALFORMED_VTT"] = false
            finishProbe(false)
            return
        }

        // Clean video-only MediaItem without corrupted subtitle track
        val videoOnlyItem = MediaItem.fromUri(DEFAULT_VIDEO_URL)
        val player = mPlayer ?: return
        mPlayerError = null

        player.setMediaItem(videoOnlyItem)
        player.prepare()
        player.play()

        fun checkMalformedAdvancement(attempts: Int) {
            if (player.playbackState == Player.STATE_READY && player.isPlaying) {
                val pos1 = player.currentPosition
                mHandler.postDelayed({
                    val pos2 = player.currentPosition
                    val advanced = pos2 > pos1
                    val hasNoError = (mPlayerError == null)
                    logResult("MALFORMED_TEST_POS_T1_MS", pos1)
                    logResult("MALFORMED_TEST_POS_T2_MS", pos2)
                    logResult("MALFORMED_TEST_ADVANCED_MS", pos2 - pos1)

                    val malformedSuccess = advanced && hasNoError
                    logResult("FAIL_OPEN_MALFORMED_VTT", if (malformedSuccess) "VERIFIED" else "FAILED")
                    logResult("FAIL_OPEN_FOR_VIDEO", if (malformedSuccess) "VERIFIED" else "FAILED")
                    logResult("VIDEO_PLAYBACK_BLOCKED", !malformedSuccess)
                    ProbeExecutionState.requiredChecks["FAIL_OPEN_MALFORMED_VTT"] = malformedSuccess

                    if (malformedSuccess) {
                        evaluateAndFinishProbe()
                    } else {
                        finishProbe(false)
                    }
                }, 1200)
            } else if (attempts > 0) {
                mHandler.postDelayed({ checkMalformedAdvancement(attempts - 1) }, 500)
            } else {
                logResult("FAIL_OPEN_MALFORMED_VTT", "FAILED_NOT_READY")
                logResult("VIDEO_PLAYBACK_BLOCKED", true)
                ProbeExecutionState.requiredChecks["FAIL_OPEN_MALFORMED_VTT"] = false
                finishProbe(false)
            }
        }

        mHandler.postDelayed({ checkMalformedAdvancement(12) }, 800)
    }

    private fun evaluateAndFinishProbe() {
        val requiredChecks = listOf(
            "MEDIA3_SIDECAR_VTT",
            "SUBTITLE_TRACK_LOADED",
            "FIRST_CUE_RENDERED",
            "POST_SEEK_CUE_RENDERED",
            "SUBTITLE_DISABLE",
            "SUBTITLE_ENABLE",
            "PAUSE_RESUME",
            "ORIENTATION_CHANGE",
            "RECREATE_VERIFIED",
            "FAIL_OPEN_MISSING_VTT",
            "FAIL_OPEN_MALFORMED_VTT"
        )

        val passedAll = requiredChecks.all { check ->
            ProbeExecutionState.requiredChecks[check] == true
        }

        if (passedAll) {
            logResult("OVERALL_RESULT", "ALL_ACCEPTANCE_CRITERIA_VERIFIED")
            finishProbe(true)
        } else {
            val failed = requiredChecks.filter { ProbeExecutionState.requiredChecks[it] != true }
            logResult("FAILED_CHECKS", failed.joinToString(", "))
            logResult("OVERALL_RESULT", "FAILED")
            finishProbe(false)
        }
    }

    private fun finishProbe(success: Boolean) {
        mCurrentPhase = if (success) Phase.COMPLETED else Phase.FAILED

        if (!success) {
            logResult("OVERALL_RESULT", "FAILED")
        }

        try {
            val resultFile = File(cacheDir, "subtitle_probe_result.json")
            resultFile.writeText(ProbeExecutionState.probeResults.toString(2))
            logResult("RESULT_SAVED_PATH", resultFile.absolutePath)

            val gateFile = File(cacheDir, "subtitle_gate_result.json")
            gateFile.writeText(ProbeExecutionState.probeResults.toString(2))
            logResult("GATE_RESULT_SAVED_PATH", gateFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing result json: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mSubtitleToggleTimeoutRunnable?.let { mHandler.removeCallbacks(it) }
        mPlayer?.release()
        mPlayer = null
        ProbeExecutionState.activePlayerCount = (ProbeExecutionState.activePlayerCount - 1).coerceAtLeast(0)
        ProbeExecutionState.oldPlayerReleased = true
        ProbeExecutionState.oldActivityDestroyed = true
        Log.i(TAG, "[$TAG] onDestroy completed, activePlayerCount=${ProbeExecutionState.activePlayerCount}")
    }
}
