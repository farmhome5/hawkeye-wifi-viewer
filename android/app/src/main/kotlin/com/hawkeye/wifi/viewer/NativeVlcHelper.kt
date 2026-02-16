package com.hawkeye.wifi.viewer

import android.content.Context
import android.util.Log
import android.view.SurfaceView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout

interface VlcEventCallback {
    fun onPlaying()
    fun onError()
    fun onEndReached()
    fun onStopped()
    fun onVideoSizeChanged(width: Int, height: Int)
}

/**
 * Shared VLC helper for native SurfaceView overlay.
 * Configures VLC for low-latency RTSP streaming.
 */
class NativeVlcHelper(context: Context) {

    companion object {
        private const val TAG = "NativeVlcHelper"
    }

    val libVLC: LibVLC
    val mediaPlayer: MediaPlayer
    var eventCallback: VlcEventCallback? = null
    private var currentSurfaceView: SurfaceView? = null

    init {
        val options = arrayListOf(
            "--no-audio",
            "--network-caching=100",
            "--live-caching=0",
            "--file-caching=0",
            "--disc-caching=0",
            "--clock-jitter=0",
            "--clock-synchro=0",
            "--drop-late-frames",
            "--skip-frames",
            "--avcodec-hurry-up",
            "--deinterlace=0",
            "--http-reconnect",
            "--no-sub-autodetect-file",
            "--no-stats",
            "--rtsp-tcp",
        )
        libVLC = LibVLC(context, options)
        mediaPlayer = MediaPlayer(libVLC)
        Log.d(TAG, "LibVLC initialized")
    }

    // OnNewVideoLayoutListener: VLC calls this when it knows the video dimensions.
    // CRITICAL: we MUST call vlcVout.setWindowSize() here or VLC will abort playback.
    private val videoLayoutListener = IVLCVout.OnNewVideoLayoutListener {
        vlcVout, width, height, visibleWidth, visibleHeight, sarNum, sarDen ->
        Log.d(TAG, "onNewVideoLayout: ${width}x${height} visible=${visibleWidth}x${visibleHeight} SAR=$sarNum/$sarDen")

        val sv = currentSurfaceView
        if (sv != null && sv.width > 0 && sv.height > 0) {
            vlcVout.setWindowSize(sv.width, sv.height)
            Log.d(TAG, "setWindowSize: ${sv.width}x${sv.height}")
        }

        var displayWidth = if (width > 0) width else visibleWidth
        var displayHeight = if (height > 0) height else visibleHeight
        if (sarNum > 0 && sarDen > 0 && sarNum != sarDen) {
            displayWidth = displayWidth * sarNum / sarDen
        }

        if (displayWidth > 0 && displayHeight > 0) {
            eventCallback?.onVideoSizeChanged(displayWidth, displayHeight)
        }
    }

    fun attachToSurface(surfaceView: SurfaceView) {
        currentSurfaceView = surfaceView
        val vout: IVLCVout = mediaPlayer.vlcVout
        vout.setVideoView(surfaceView)
        if (surfaceView.width > 0 && surfaceView.height > 0) {
            vout.setWindowSize(surfaceView.width, surfaceView.height)
            Log.d(TAG, "Pre-set windowSize: ${surfaceView.width}x${surfaceView.height}")
        }
        vout.attachViews(videoLayoutListener)
        Log.d(TAG, "Attached to SurfaceView with layout listener")
    }

    fun updateWindowSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            try {
                mediaPlayer.vlcVout.setWindowSize(width, height)
                Log.d(TAG, "updateWindowSize: ${width}x${height}")
            } catch (e: Exception) {
                Log.w(TAG, "updateWindowSize error: $e")
            }
        }
    }

    fun detachSurface() {
        try {
            val vout: IVLCVout = mediaPlayer.vlcVout
            vout.detachViews()
            currentSurfaceView = null
            Log.d(TAG, "Detached from SurfaceView")
        } catch (e: Exception) {
            Log.w(TAG, "detachSurface error: $e")
        }
    }

    fun play(rtspUrl: String) {
        val media = Media(libVLC, android.net.Uri.parse(rtspUrl))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":network-caching=100")
        media.addOption(":live-caching=0")
        media.addOption(":clock-jitter=0")
        media.addOption(":clock-synchro=0")

        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    Log.d(TAG, "Event: Playing")
                    eventCallback?.onPlaying()
                }
                MediaPlayer.Event.EncounteredError -> {
                    Log.d(TAG, "Event: EncounteredError")
                    eventCallback?.onError()
                }
                MediaPlayer.Event.EndReached -> {
                    Log.d(TAG, "Event: EndReached")
                    eventCallback?.onEndReached()
                }
                MediaPlayer.Event.Stopped -> {
                    Log.d(TAG, "Event: Stopped")
                    eventCallback?.onStopped()
                }
            }
        }

        mediaPlayer.media = media
        mediaPlayer.play()
        media.release()
        Log.d(TAG, "Playing: $rtspUrl")
    }

    fun stop() {
        try {
            mediaPlayer.setEventListener(null)
            mediaPlayer.stop()
            Log.d(TAG, "Stopped")
        } catch (e: Exception) {
            Log.w(TAG, "stop error: $e")
        }
    }

    val isPlaying: Boolean
        get() = mediaPlayer.isPlaying

    fun release() {
        try {
            mediaPlayer.setEventListener(null)
        } catch (_: Exception) {}
        try {
            mediaPlayer.stop()
        } catch (_: Exception) {}
        try {
            mediaPlayer.vlcVout.detachViews()
        } catch (_: Exception) {}
        currentSurfaceView = null
        mediaPlayer.release()
        libVLC.release()
        Log.d(TAG, "Released")
    }
}
