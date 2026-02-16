package com.hawkeye.wifi.viewer

import android.content.res.Configuration
import android.graphics.Color
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
    private var methodChannel: MethodChannel? = null
    private var overlaySurfaceView: SurfaceView? = null
    private var vlcHelper: NativeVlcHelper? = null
    private var pendingPlayUrl: String? = null
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0

    override fun getTransparencyMode(): TransparencyMode = TransparencyMode.transparent

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        methodChannel!!.setMethodCallHandler { call, result ->
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
                "overlay_getVideoSize" -> {
                    result.success(mapOf("width" to videoWidth, "height" to videoHeight))
                }
                else -> result.notImplemented()
            }
        }
    }

    // --- VLC event callback: forwards events to Flutter via MethodChannel ---

    private val vlcCallback = object : VlcEventCallback {
        override fun onPlaying() {
            runOnUiThread {
                methodChannel?.invokeMethod("nativeEvent", mapOf("event" to "playing"))
            }
        }

        override fun onError() {
            runOnUiThread {
                methodChannel?.invokeMethod("nativeEvent", mapOf("event" to "error"))
            }
        }

        override fun onEndReached() {
            runOnUiThread {
                methodChannel?.invokeMethod("nativeEvent", mapOf("event" to "ended"))
            }
        }

        override fun onStopped() {
            runOnUiThread {
                methodChannel?.invokeMethod("nativeEvent", mapOf("event" to "stopped"))
            }
        }

        override fun onVideoSizeChanged(width: Int, height: Int) {
            runOnUiThread {
                videoWidth = width
                videoHeight = height
                methodChannel?.invokeMethod("nativeEvent", mapOf(
                    "event" to "videoSize",
                    "width" to width,
                    "height" to height
                ))
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

        // SurfaceView stays MATCH_PARENT always — VLC handles aspect ratio/centering internally
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
                // Surface resized (e.g. rotation) — tell VLC the new window dimensions
                // VLC will re-scale and re-center the video automatically
                vlcHelper?.updateWindowSize(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "overlay surfaceDestroyed")
                vlcHelper?.stop()
                vlcHelper?.detachSurface()
            }
        })

        // Add SurfaceView at index 0 so it's behind the Flutter view
        container.addView(overlaySurfaceView, 0)

        // Initialize VLC helper with event callback
        if (vlcHelper == null) {
            vlcHelper = NativeVlcHelper(applicationContext)
        }
        vlcHelper?.eventCallback = vlcCallback
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
        // Clear surface to black to avoid ghost frame from previous stream
        try {
            val canvas = sv.holder.lockCanvas()
            if (canvas != null) {
                canvas.drawColor(Color.BLACK)
                sv.holder.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            Log.w(TAG, "clearSurface: $e")
        }
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
        videoWidth = 0
        videoHeight = 0
        vlcHelper?.eventCallback = null
        vlcHelper?.release()
        vlcHelper = null
        pendingPlayUrl = null

        overlaySurfaceView?.let { sv ->
            (sv.parent as? ViewGroup)?.removeView(sv)
        }
        overlaySurfaceView = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged: orientation=${newConfig.orientation}")
        // Defer VLC window size update until the surface has settled with new dimensions
        overlaySurfaceView?.postDelayed({
            val sv = overlaySurfaceView ?: return@postDelayed
            vlcHelper?.updateWindowSize(sv.width, sv.height)
        }, 150)
    }

    override fun onDestroy() {
        overlayDispose()
        super.onDestroy()
    }
}
