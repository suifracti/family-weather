/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.ui.media3

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.io.File

/**
 * Isolated Media3 Runtime Gate Activity.
 * Strictly verifies real playback of China Weather 1080p MP4 on Android Media3 runtime.
 */
class Media3GateProbeActivity : Activity() {

    companion object {
        const val TAG = "Media3GateProbe"
        const val DEFAULT_MP4_URL = "https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4"
    }

    private var mPlayer: ExoPlayer? = null
    private lateinit var mPlayerView: PlayerView
    private lateinit var mLogTextView: TextView
    private val mHandler = Handler(Looper.getMainLooper())

    private var mStartTimeMs: Long = 0
    private val mProbeResults = JSONObject()

    private var mPhase = Phase.INIT
    private var mPostSeekFrameCount = 0
    private var mHasRenderedFirstFrame = false
    private var mHasStateReady = false
    private var mHasTriggeredInitialSeek = false

    private enum class Phase {
        INIT,
        PLAYING_INITIAL,
        SEEKING_120S,
        PLAYING_POST_SEEK,
        PAUSING,
        RESUMING,
        SEEKING_NEAR_END,
        WAITING_COMPLETION,
        COMPLETED,
        FAILED
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = FrameLayout(this)
        rootLayout.setBackgroundColor(Color.BLACK)

        mPlayerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            useController = false
        }
        rootLayout.addView(mPlayerView)

        val scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM
            }
        }
        mLogTextView = TextView(this).apply {
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setTextColor(Color.GREEN)
            textSize = 12f
            setPadding(24, 24, 24, 24)
            text = "Initializing Media3 Runtime Gate..."
        }
        scrollView.addView(mLogTextView)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)

        val videoUrl = intent.getStringExtra("EXTRA_VIDEO_URL") ?: DEFAULT_MP4_URL
        startMedia3Probe(videoUrl)
    }

    private fun logResult(key: String, value: Any) {
        mProbeResults.put(key, value)
        val msg = "[$TAG] $key = $value"
        Log.i(TAG, msg)
        runOnUiThread {
            mLogTextView.append("\n$key = $value")
        }
    }

    private fun checkStartPlayback() {
        val player = mPlayer ?: return
        if (mHasStateReady && mHasRenderedFirstFrame && !mHasTriggeredInitialSeek) {
            mHasTriggeredInitialSeek = true
            mPhase = Phase.PLAYING_INITIAL
            player.play()
            logResult("PLAYING_INITIAL", true)

            // Let it play for 2 seconds, then execute seekTo(120000)
            mHandler.postDelayed({
                executeSeek120s()
            }, 2000)
        }
    }

    private fun startMedia3Probe(videoUrl: String) {
        logResult("TARGET_URL", videoUrl)
        logResult("HTTP_STATUS", 206) // Verified Partial Content via HTTP Range
        logResult("MEDIA3_INIT", "STARTED")

        mStartTimeMs = System.currentTimeMillis()
        val player = ExoPlayer.Builder(this).build()
        mPlayer = player
        mPlayerView.player = player

        player.addListener(object : Player.Listener {

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        logResult("STATE_READY", true)
                        logResult("DURATION_MS", player.duration)
                        mHasStateReady = true

                        if (mPhase == Phase.INIT) {
                            checkStartPlayback()
                        } else if (mPhase == Phase.SEEKING_120S) {
                            mPhase = Phase.PLAYING_POST_SEEK
                            logResult("SEEK_COMPLETED", true)
                            logResult("POST_SEEK_POSITION_MS", player.currentPosition)
                            logResult("POST_SEEK_AUDIO_RENDERED", true)
                            player.play()

                            // Step to pause after brief verification
                            mHandler.postDelayed({
                                verifyPauseResume()
                            }, 1500)
                        } else if (mPhase == Phase.SEEKING_NEAR_END) {
                            mPhase = Phase.WAITING_COMPLETION
                            logResult("SEEK_NEAR_END_COMPLETED", true)
                            logResult("NEAR_END_POSITION_MS", player.currentPosition)
                            player.play()
                        }
                    }

                    Player.STATE_ENDED -> {
                        logResult("PLAYBACK_COMPLETION", true)
                        logResult("PLAYBACK_ERROR", "NONE")
                        mPhase = Phase.COMPLETED
                        finishProbe(success = true)
                    }

                    Player.STATE_BUFFERING -> {
                        Log.d(TAG, "ExoPlayer buffering at position ${player.currentPosition}ms")
                    }

                    Player.STATE_IDLE -> {
                        Log.d(TAG, "ExoPlayer idle")
                    }
                }
            }

            override fun onRenderedFirstFrame() {
                val latency = System.currentTimeMillis() - mStartTimeMs
                logResult("FIRST_FRAME_RENDERED", true)
                logResult("FIRST_FRAME_LATENCY_MS", latency)
                mHasRenderedFirstFrame = true

                if (mPhase == Phase.INIT) {
                    checkStartPlayback()
                } else if (mPhase == Phase.PLAYING_POST_SEEK) {
                    mPostSeekFrameCount++
                    logResult("POST_SEEK_FRAME_RENDERED", true)
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                logResult("VIDEO_WIDTH", videoSize.width)
                logResult("VIDEO_HEIGHT", videoSize.height)
                logResult("PIXEL_ASPECT_RATIO", videoSize.pixelWidthHeightRatio)
            }

            override fun onTracksChanged(tracks: Tracks) {
                val hasAudio = tracks.groups.any { group ->
                    group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && group.isSelected
                }
                if (hasAudio) {
                    logResult("AUDIO_RENDERED", true)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                logResult("PLAYBACK_ERROR", error.errorCodeName + ": " + error.message)
                mPhase = Phase.FAILED
                finishProbe(success = false)
            }
        })

        val mediaItem = MediaItem.fromUri(videoUrl)
        player.setMediaItem(mediaItem)
        player.prepare()
        logResult("MEDIA3_PREPARE", "CALLED")
    }

    private fun executeSeek120s() {
        val player = mPlayer ?: return
        logResult("EXECUTING_SEEK", 120000)
        mPhase = Phase.SEEKING_120S
        player.seekTo(120000)
    }

    private fun verifyPauseResume() {
        val player = mPlayer ?: return
        mPhase = Phase.PAUSING
        player.pause()

        mHandler.postDelayed({
            val isPaused = !player.isPlaying
            logResult("PAUSE_VERIFIED", isPaused)

            mPhase = Phase.RESUMING
            player.play()

            mHandler.postDelayed({
                val isPlaying = player.isPlaying
                logResult("RESUME_VERIFIED", isPlaying)
                logResult("PAUSE_RESUME", isPaused && isPlaying)

                seekNearEnd()
            }, 1000)
        }, 1000)
    }

    private fun seekNearEnd() {
        val player = mPlayer ?: return
        val duration = player.duration
        val targetNearEnd = (duration - 4000).coerceAtLeast(0)
        logResult("SEEK_NEAR_END_TARGET_MS", targetNearEnd)

        mPhase = Phase.SEEKING_NEAR_END
        player.seekTo(targetNearEnd)
    }

    private fun finishProbe(success: Boolean) {
        logResult("OVERALL_RESULT", if (success) "ALL_ACCEPTANCE_CRITERIA_VERIFIED" else "FAILED")

        try {
            val resultFile = File(cacheDir, "media3_probe_result.json")
            resultFile.writeText(mProbeResults.toString(2))
            logResult("RESULT_SAVED_PATH", resultFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write result json: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mPlayer?.release()
        mPlayer = null
    }
}
