package com.hawkeye.wifi.viewer

import android.content.Context
import android.util.Log
import android.view.SurfaceView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout

/**
 * Shared VLC helper used by both Approach 1 (SurfaceView overlay) and
 * Approach 2 (NativeVideoActivity). Mirrors the low-latency options from
 * the Flutter VLC player configuration.
 */
class NativeVlcHelper(context: Context) {

    companion object {
        private const val TAG = "NativeVlcHelper"
    }

    val libVLC: LibVLC
    val mediaPlayer: MediaPlayer

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
            "--http-reconnect",
            "--no-sub-autodetect-file",
            "--no-stats",
            "--rtsp-tcp",          // RTP over RTSP (TCP) for reliable delivery
            "-vvv",                // verbose logging for debugging
        )
        libVLC = LibVLC(context, options)
        mediaPlayer = MediaPlayer(libVLC)
        Log.d(TAG, "LibVLC initialized")
    }

    fun attachToSurface(surfaceView: SurfaceView) {
        val vout: IVLCVout = mediaPlayer.vlcVout
        vout.setVideoView(surfaceView)
        vout.attachViews()
        Log.d(TAG, "Attached to SurfaceView")
    }

    fun detachSurface() {
        try {
            val vout: IVLCVout = mediaPlayer.vlcVout
            vout.detachViews()
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
        mediaPlayer.media = media
        mediaPlayer.play()
        media.release()
        Log.d(TAG, "Playing: $rtspUrl")
    }

    fun stop() {
        try {
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
            mediaPlayer.stop()
        } catch (_: Exception) {}
        try {
            mediaPlayer.vlcVout.detachViews()
        } catch (_: Exception) {}
        mediaPlayer.release()
        libVLC.release()
        Log.d(TAG, "Released")
    }
}
