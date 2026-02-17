package com.hawkeye.wifi.viewer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.alexvas.rtsp.widget.RtspStatusListener
import com.alexvas.rtsp.widget.RtspSurfaceView

/**
 * Low-latency RTSP player using rtsp-client-android library.
 * Replaces VLC with direct RTSP→MediaCodec pipeline.
 * Implements the same VlcEventCallback interface for Flutter MethodChannel compatibility.
 */
class NativeMediaCodecHelper(private val context: Context) {

    companion object {
        private const val TAG = "NativeMediaCodecHelper"
    }

    var eventCallback: VlcEventCallback? = null
    private var rtspSurfaceView: RtspSurfaceView? = null
    private var isCurrentlyPlaying = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var currentUrl: String? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var parentLayoutListener: View.OnLayoutChangeListener? = null

    /**
     * Create and return an RtspSurfaceView (extends SurfaceView).
     * Caller adds this to the FrameLayout at index 0, behind Flutter.
     */
    fun createSurfaceView(): RtspSurfaceView {
        val sv = RtspSurfaceView(context)
        rtspSurfaceView = sv

        sv.debug = false
        sv.experimentalUpdateSpsFrameWithLowLatencyParams = true

        sv.setStatusListener(object : RtspStatusListener {
            override fun onRtspStatusConnecting() {
                Log.d(TAG, "RTSP: connecting")
            }

            override fun onRtspStatusConnected() {
                Log.d(TAG, "RTSP: connected")
            }

            override fun onRtspStatusDisconnecting() {
                Log.d(TAG, "RTSP: disconnecting")
            }

            override fun onRtspStatusDisconnected() {
                Log.d(TAG, "RTSP: disconnected")
                isCurrentlyPlaying = false
                uiHandler.post { eventCallback?.onEndReached() }
            }

            override fun onRtspStatusFailedUnauthorized() {
                Log.e(TAG, "RTSP: unauthorized")
                isCurrentlyPlaying = false
                uiHandler.post {
                    Log.e(TAG, "Firing error event: unauthorized")
                    eventCallback?.onError()
                }
            }

            override fun onRtspStatusFailed(message: String?) {
                Log.e(TAG, "RTSP: failed - $message")
                isCurrentlyPlaying = false
                uiHandler.post {
                    Log.e(TAG, "Firing error event: $message")
                    eventCallback?.onError()
                }
            }

            override fun onRtspFirstFrameRendered() {
                Log.d(TAG, "RTSP: first frame rendered")
                isCurrentlyPlaying = true
                uiHandler.post { eventCallback?.onPlaying() }
            }

            override fun onRtspFrameSizeChanged(width: Int, height: Int) {
                Log.d(TAG, "RTSP: frame size ${width}x${height}")
                videoWidth = width
                videoHeight = height
                uiHandler.post {
                    updateViewAspectRatio()
                    eventCallback?.onVideoSizeChanged(width, height)
                }
            }
        })

        Log.d(TAG, "RtspSurfaceView created")
        return sv
    }

    /**
     * Call after adding the SurfaceView to its parent FrameLayout.
     * Sets up a layout listener to recalculate aspect ratio on rotation.
     */
    fun setupParentLayoutListener() {
        val sv = rtspSurfaceView ?: return
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

    private fun updateViewAspectRatio() {
        val sv = rtspSurfaceView ?: return
        if (videoWidth <= 0 || videoHeight <= 0) return
        val parent = sv.parent as? ViewGroup ?: return
        val parentW = parent.width
        val parentH = parent.height
        if (parentW <= 0 || parentH <= 0) return

        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val parentAspect = parentW.toFloat() / parentH.toFloat()

        val viewW: Int
        val viewH: Int
        if (videoAspect > parentAspect) {
            // Video wider than parent — fit width, letterbox top/bottom
            viewW = parentW
            viewH = (parentW / videoAspect).toInt()
        } else {
            // Video taller than parent — fit height, pillarbox left/right
            viewW = (parentH * videoAspect).toInt()
            viewH = parentH
        }

        // Use margins to center — Gravity.CENTER is unreliable in FlutterView's layout
        val marginH = (parentW - viewW) / 2
        val marginV = (parentH - viewH) / 2

        sv.layoutParams = FrameLayout.LayoutParams(viewW, viewH).apply {
            setMargins(marginH, marginV, marginH, marginV)
        }

        Log.d(TAG, "Aspect ratio: ${videoWidth}x${videoHeight} -> view ${viewW}x${viewH} in ${parentW}x${parentH}")
    }

    fun play(rtspUrl: String) {
        val sv = rtspSurfaceView ?: return
        currentUrl = rtspUrl

        if (sv.isStarted()) {
            sv.stop()
        }

        val uri = Uri.parse(rtspUrl)
        sv.init(uri, null, null)
        sv.start(requestVideo = true, requestAudio = false, requestApplication = false)
        Log.d(TAG, "Playing: $rtspUrl")
    }

    fun stop() {
        try {
            val sv = rtspSurfaceView
            if (sv != null && sv.isStarted()) {
                sv.stop()
            }
            isCurrentlyPlaying = false
            Log.d(TAG, "Stopped")
        } catch (e: Exception) {
            Log.w(TAG, "stop error: $e")
        }
    }

    val isPlaying: Boolean get() = isCurrentlyPlaying

    fun getVideoSize(): Pair<Int, Int> = Pair(videoWidth, videoHeight)

    fun release() {
        try {
            val sv = rtspSurfaceView
            if (sv != null && sv.isStarted()) {
                sv.stop()
            }
        } catch (_: Exception) {}
        parentLayoutListener?.let { listener ->
            (rtspSurfaceView?.parent as? ViewGroup)?.removeOnLayoutChangeListener(listener)
        }
        parentLayoutListener = null
        rtspSurfaceView?.setStatusListener(null)
        rtspSurfaceView = null
        isCurrentlyPlaying = false
        videoWidth = 0
        videoHeight = 0
        currentUrl = null
        eventCallback = null
        Log.d(TAG, "Released")
    }
}
