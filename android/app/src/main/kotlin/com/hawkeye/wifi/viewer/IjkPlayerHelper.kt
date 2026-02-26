package com.hawkeye.wifi.viewer

import android.content.Context
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import tv.danmaku.ijk.media.player.IjkMediaPlayer

/**
 * IJKPlayer-based RTSP player for Q2 borescope cameras.
 * Replaces NativeMediaCodecHelper — connects directly to rtsp://...
 * using FFmpeg+MediaCodec via IJKPlayer (same as Xplor viewer).
 *
 * Implements the same VlcEventCallback interface for Flutter MethodChannel compatibility.
 */
class IjkPlayerHelper(private val context: Context) {

    companion object {
        private const val TAG = "IjkPlayerHelper"
    }

    var eventCallback: VlcEventCallback? = null
    private var surfaceView: SurfaceView? = null
    private var ijkPlayer: IjkMediaPlayer? = null
    private var isCurrentlyPlaying = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var surfaceReady = false
    private var pendingUrl: String? = null
    private var parentLayoutListener: View.OnLayoutChangeListener? = null

    // Reserved insets (pixels) — space reserved for Flutter UI toolbar
    private var reservedLeft = 0
    private var reservedTop = 0
    private var reservedRight = 0
    private var reservedBottom = 0

    /**
     * Create and return a plain SurfaceView for IJKPlayer rendering.
     * Caller adds this to the FrameLayout at index 0, behind Flutter.
     */
    fun createSurfaceView(): SurfaceView {
        val sv = SurfaceView(context)
        surfaceView = sv

        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG, "Surface created")
                surfaceReady = true
                // If play() was called before surface was ready, start now
                pendingUrl?.let { url ->
                    pendingUrl = null
                    startPlayer(url)
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d(TAG, "Surface changed: ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "Surface destroyed")
                surfaceReady = false
                releasePlayer()
            }
        })

        Log.d(TAG, "SurfaceView created")
        return sv
    }

    /**
     * Call after adding the SurfaceView to its parent FrameLayout.
     * Sets up a layout listener to recalculate aspect ratio on rotation.
     */
    fun setupParentLayoutListener() {
        val sv = surfaceView ?: return
        val parent = sv.parent as? ViewGroup ?: return

        parentLayoutListener = View.OnLayoutChangeListener {
                _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val newW = right - left
            val newH = bottom - top
            val oldW = oldRight - oldLeft
            val oldH = oldBottom - oldTop
            if (newW != oldW || newH != oldH) {
                updateViewAspectRatio()
            }
        }
        parent.addOnLayoutChangeListener(parentLayoutListener)
    }

    /**
     * Set reserved insets (in pixels) for Flutter UI elements.
     * The video will be sized and centered within the remaining area.
     */
    fun setReservedInsets(left: Int, top: Int, right: Int, bottom: Int) {
        reservedLeft = left
        reservedTop = top
        reservedRight = right
        reservedBottom = bottom
        updateViewAspectRatio()
    }

    private fun updateViewAspectRatio() {
        val sv = surfaceView ?: return
        if (videoWidth <= 0 || videoHeight <= 0) return
        val parent = sv.parent as? ViewGroup ?: return
        val parentW = parent.width
        val parentH = parent.height
        if (parentW <= 0 || parentH <= 0) return

        // Available area after reserving space for UI
        val availW = parentW - reservedLeft - reservedRight
        val availH = parentH - reservedTop - reservedBottom
        if (availW <= 0 || availH <= 0) return

        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val availAspect = availW.toFloat() / availH.toFloat()

        val viewW: Int
        val viewH: Int
        if (videoAspect > availAspect) {
            // Video wider than available — fit width, letterbox top/bottom
            viewW = availW
            viewH = (availW / videoAspect).toInt()
        } else {
            // Video taller than available — fit height, pillarbox left/right
            viewW = (availH * videoAspect).toInt()
            viewH = availH
        }

        // Center within the available area (offset by reserved insets)
        val marginLeft = reservedLeft + (availW - viewW) / 2
        val marginTop = reservedTop + (availH - viewH) / 2
        val marginRight = parentW - marginLeft - viewW
        val marginBottom = parentH - marginTop - viewH

        sv.layoutParams = FrameLayout.LayoutParams(viewW, viewH).apply {
            setMargins(marginLeft, marginTop, marginRight, marginBottom)
        }

        Log.d(TAG, "Aspect ratio: ${videoWidth}x${videoHeight} -> view ${viewW}x${viewH} in ${parentW}x${parentH} (insets L=$reservedLeft T=$reservedTop R=$reservedRight B=$reservedBottom)")
    }

    fun play(rtspUrl: String) {
        // Release any existing player first
        releasePlayer()

        if (!surfaceReady) {
            Log.d(TAG, "Surface not ready, deferring play: $rtspUrl")
            pendingUrl = rtspUrl
            return
        }

        startPlayer(rtspUrl)
    }

    private fun startPlayer(rtspUrl: String) {
        if (!surfaceReady) {
            Log.w(TAG, "Surface not ready, can't start IJKPlayer")
            eventCallback?.onError()
            return
        }

        try {
            val player = IjkMediaPlayer()

            // Match DVRunning2's IjkVideoView.createPlayer() options exactly
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 1L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", "fcc-_es2")
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 5L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 2097152L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_frame", 16L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 16L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 60L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1048576L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 5000L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "sync", "ext")
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1L)
            // Real-time streaming — low-latency settings
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "nobuffer")
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "find_stream_info", 0L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport", "udp")
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "infbuf", 1L)

            // Set listeners
            player.setOnPreparedListener {
                Log.d(TAG, "IJKPlayer prepared — playback starting")
                player.start()
                isCurrentlyPlaying = true
                surfaceView?.keepScreenOn = true
                eventCallback?.onPlaying()
            }

            player.setOnVideoSizeChangedListener { _, width, height, _, _ ->
                if (width > 0 && height > 0 && (width != videoWidth || height != videoHeight)) {
                    videoWidth = width
                    videoHeight = height
                    Log.d(TAG, "IJKPlayer video size: ${width}x${height}")
                    updateViewAspectRatio()
                    eventCallback?.onVideoSizeChanged(width, height)
                }
            }

            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "IJKPlayer error: what=$what extra=$extra")
                isCurrentlyPlaying = false
                surfaceView?.keepScreenOn = false
                eventCallback?.onError()
                true
            }

            player.setOnCompletionListener {
                Log.d(TAG, "IJKPlayer completed")
                isCurrentlyPlaying = false
                surfaceView?.keepScreenOn = false
                eventCallback?.onEndReached()
            }

            // Connect directly to the Q2 camera's RTSP stream
            player.setDisplay(surfaceView!!.holder)
            player.setDataSource(rtspUrl)
            player.prepareAsync()

            ijkPlayer = player
            Log.d(TAG, "IJKPlayer started: $rtspUrl")

        } catch (e: Exception) {
            Log.e(TAG, "IJKPlayer start error", e)
            eventCallback?.onError()
        }
    }

    fun stop() {
        isCurrentlyPlaying = false
        surfaceView?.keepScreenOn = false
        pendingUrl = null
        releasePlayer()
        Log.d(TAG, "Stopped")
    }

    private fun releasePlayer() {
        try {
            ijkPlayer?.stop()
        } catch (_: Exception) {}
        try {
            ijkPlayer?.release()
        } catch (_: Exception) {}
        ijkPlayer = null
    }

    val isPlaying: Boolean get() {
        val sv = surfaceView ?: return false
        return isCurrentlyPlaying && sv.holder.surface.isValid
    }

    fun getVideoSize(): Pair<Int, Int> = Pair(videoWidth, videoHeight)

    fun release() {
        stop()
        parentLayoutListener?.let { listener ->
            (surfaceView?.parent as? ViewGroup)?.removeOnLayoutChangeListener(listener)
        }
        parentLayoutListener = null
        surfaceView = null
        videoWidth = 0
        videoHeight = 0
        eventCallback = null
        Log.d(TAG, "Released")
    }
}
