package com.hawkeye.wifi.viewer

import android.content.Intent
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.android.TransparencyMode
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val CHANNEL = "hawkeye/native_vlc"
    }

    // Approach 1: native SurfaceView behind transparent Flutter
    private var overlaySurfaceView: SurfaceView? = null
    private var vlcHelper: NativeVlcHelper? = null
    private var pendingPlayUrl: String? = null

    override fun getTransparencyMode(): TransparencyMode = TransparencyMode.transparent

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "overlay_init" -> {
                        overlayInit()
                        result.success(true)
                    }
                    "overlay_play" -> {
                        val url = call.argument<String>("url") ?: ""
                        overlayPlay(url)
                        result.success(true)
                    }
                    "overlay_stop" -> {
                        overlayStop()
                        result.success(true)
                    }
                    "overlay_dispose" -> {
                        overlayDispose()
                        result.success(true)
                    }
                    "overlay_isPlaying" -> {
                        result.success(vlcHelper?.isPlaying == true)
                    }
                    "launch_native_activity" -> {
                        val url = call.argument<String>("url") ?: ""
                        launchNativeActivity(url)
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    // --- Approach 1: SurfaceView overlay behind Flutter ---

    private fun overlayInit() {
        if (overlaySurfaceView != null) {
            Log.d(TAG, "overlay_init: already initialized")
            return
        }

        Log.d(TAG, "overlay_init: creating SurfaceView behind Flutter")

        // Get the root FrameLayout that contains the FlutterView
        val root = findViewById<ViewGroup>(android.R.id.content)
        val container = root.getChildAt(0) as? ViewGroup ?: root

        overlaySurfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        overlaySurfaceView!!.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG, "overlay surfaceCreated")
                // If a play was requested before surface was ready, start now
                pendingPlayUrl?.let { url ->
                    pendingPlayUrl = null
                    overlayPlayInternal(url)
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d(TAG, "overlay surfaceChanged: ${width}x$height")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "overlay surfaceDestroyed")
                vlcHelper?.stop()
                vlcHelper?.detachSurface()
            }
        })

        // Add SurfaceView at index 0 so it's behind the Flutter view
        container.addView(overlaySurfaceView, 0)

        // Initialize VLC helper
        if (vlcHelper == null) {
            vlcHelper = NativeVlcHelper(applicationContext)
        }
    }

    private fun overlayPlay(url: String) {
        if (overlaySurfaceView == null) {
            overlayInit()
        }

        val holder = overlaySurfaceView?.holder
        if (holder?.surface?.isValid == true) {
            overlayPlayInternal(url)
        } else {
            Log.d(TAG, "overlay_play: surface not ready, deferring")
            pendingPlayUrl = url
        }
    }

    private fun overlayPlayInternal(url: String) {
        val helper = vlcHelper ?: return
        val sv = overlaySurfaceView ?: return
        helper.stop()
        helper.detachSurface()
        helper.attachToSurface(sv)
        helper.play(url)
        Log.d(TAG, "overlay playing: $url")
    }

    private fun overlayStop() {
        vlcHelper?.stop()
        Log.d(TAG, "overlay stopped")
    }

    private fun overlayDispose() {
        Log.d(TAG, "overlay dispose")
        vlcHelper?.release()
        vlcHelper = null
        pendingPlayUrl = null

        overlaySurfaceView?.let { sv ->
            (sv.parent as? ViewGroup)?.removeView(sv)
        }
        overlaySurfaceView = null
    }

    // --- Approach 2: Launch NativeVideoActivity ---

    private fun launchNativeActivity(url: String) {
        Log.d(TAG, "launching NativeVideoActivity: $url")
        val intent = Intent(this, NativeVideoActivity::class.java).apply {
            putExtra(NativeVideoActivity.EXTRA_RTSP_URL, url)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        overlayDispose()
        super.onDestroy()
    }
}
