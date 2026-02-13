package com.hawkeye.wifi.viewer

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.app.Activity

/**
 * Approach 2: Standalone native Activity for RTSP playback.
 * Zero Flutter involvement — SurfaceView renders VLC directly.
 * Uses plain Activity (not AppCompatActivity) to avoid theme conflicts.
 */
class NativeVideoActivity : Activity(), SurfaceHolder.Callback {

    companion object {
        const val EXTRA_RTSP_URL = "rtsp_url"
        private const val TAG = "NativeVideoActivity"
    }

    private var vlcHelper: NativeVlcHelper? = null
    private var rtspUrl: String = ""
    private var surfaceReady = false
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rtspUrl = intent.getStringExtra(EXTRA_RTSP_URL) ?: ""
        Log.d(TAG, "onCreate, url=$rtspUrl")

        // Build layout programmatically
        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        // SurfaceView — fullscreen video
        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        surfaceView.holder.addCallback(this)
        root.addView(surfaceView)

        // Status text at bottom
        statusText = TextView(this).apply {
            text = "Connecting..."
            setTextColor(android.graphics.Color.argb(180, 255, 255, 255))
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                bottomMargin = 24
                leftMargin = 16
                rightMargin = 16
            }
        }
        root.addView(statusText)

        // Back button — top-left
        val backBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(android.graphics.Color.argb(100, 0, 0, 0))
            setPadding(24, 24, 24, 24)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                topMargin = 32
                leftMargin = 16
            }
            setOnClickListener { finish() }
        }
        root.addView(backBtn)

        setContentView(root)

        // Immersive fullscreen (must be after setContentView so DecorView exists)
        goImmersive()

        // Init VLC
        vlcHelper = NativeVlcHelper(applicationContext)
    }

    private fun goImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    // --- SurfaceHolder.Callback ---

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceCreated")
        surfaceReady = true
        startPlayback()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "surfaceChanged: ${width}x$height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceDestroyed")
        surfaceReady = false
        vlcHelper?.stop()
        vlcHelper?.detachSurface()
    }

    private fun startPlayback() {
        if (rtspUrl.isEmpty()) {
            statusText.text = "No RTSP URL provided"
            return
        }
        val helper = vlcHelper ?: return
        helper.attachToSurface(surfaceView)
        helper.play(rtspUrl)
        statusText.text = "Native Activity: $rtspUrl"

        // Monitor playback state
        helper.mediaPlayer.setEventListener { event ->
            runOnUiThread {
                when (event.type) {
                    org.videolan.libvlc.MediaPlayer.Event.Playing ->
                        statusText.text = "Native Activity streaming"
                    org.videolan.libvlc.MediaPlayer.Event.EncounteredError ->
                        statusText.text = "VLC error"
                    org.videolan.libvlc.MediaPlayer.Event.EndReached ->
                        statusText.text = "Stream ended"
                    org.videolan.libvlc.MediaPlayer.Event.Stopped ->
                        statusText.text = "Stopped"
                }
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        vlcHelper?.release()
        vlcHelper = null
        super.onDestroy()
    }
}
