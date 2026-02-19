package com.hawkeye.wifi.viewer

import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import com.alexvas.rtsp.widget.RtspSurfaceView
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.android.TransparencyMode
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val CHANNEL = "hawkeye/native_vlc"
    }

    private var methodChannel: MethodChannel? = null
    private var overlaySurfaceView: RtspSurfaceView? = null
    private var mediaHelper: NativeMediaCodecHelper? = null
    private var captureHelper: CaptureHelper? = null
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
                "overlay_setInsets" -> {
                    val left = call.argument<Int>("left") ?: 0
                    val top = call.argument<Int>("top") ?: 0
                    val right = call.argument<Int>("right") ?: 0
                    val bottom = call.argument<Int>("bottom") ?: 0
                    mediaHelper?.setReservedInsets(left, top, right, bottom)
                    result.success(true)
                }
                "overlay_isPlaying" -> {
                    result.success(mediaHelper?.isPlaying == true)
                }
                "overlay_getVideoSize" -> {
                    result.success(mapOf("width" to videoWidth, "height" to videoHeight))
                }
                "capture_photo" -> {
                    val wifiName = call.argument<String>("wifiName") ?: "Camera"
                    val sv = overlaySurfaceView
                    if (sv == null) {
                        result.error("NO_SURFACE", "Surface not available", null)
                    } else {
                        ensureCaptureHelper()
                        captureHelper?.capturePhoto(sv, wifiName)
                        result.success(true)
                    }
                }
                "start_recording" -> {
                    val url = call.argument<String>("url") ?: ""
                    val wifiName = call.argument<String>("wifiName") ?: "Camera"
                    val width = call.argument<Int>("width") ?: 400
                    val height = call.argument<Int>("height") ?: 400
                    val sv = overlaySurfaceView
                    if (url.isEmpty()) {
                        result.error("NO_URL", "RTSP URL required", null)
                    } else if (sv == null) {
                        result.error("NO_SURFACE", "Surface not available", null)
                    } else {
                        ensureCaptureHelper()
                        captureHelper?.startRecording(sv, url, wifiName, width, height)
                        result.success(true)
                    }
                }
                "stop_recording" -> {
                    captureHelper?.stopRecording()
                    result.success(true)
                }
                "is_recording" -> {
                    result.success(captureHelper?.isRecording == true)
                }
                else -> result.notImplemented()
            }
        }
    }

    // --- Capture helper: photo + video recording ---

    private fun ensureCaptureHelper() {
        if (captureHelper == null) {
            captureHelper = CaptureHelper(applicationContext)
        }
        captureHelper?.callback = captureCallback
    }

    private val captureCallback = object : CaptureCallback {
        override fun onPhotoSaved(path: String) {
            runOnUiThread {
                methodChannel?.invokeMethod("nativeEvent", mapOf(
                    "event" to "photo_saved",
                    "path" to path
                ))
            }
        }

        override fun onRecordingStopped(path: String, durationMs: Long) {
            runOnUiThread {
                methodChannel?.invokeMethod("nativeEvent", mapOf(
                    "event" to "recording_stopped",
                    "path" to path,
                    "durationMs" to durationMs
                ))
            }
        }

        override fun onCaptureError(message: String) {
            runOnUiThread {
                methodChannel?.invokeMethod("nativeEvent", mapOf(
                    "event" to "capture_error",
                    "message" to message
                ))
            }
        }
    }

    // --- Event callback: forwards events to Flutter via MethodChannel ---

    private val playerCallback = object : VlcEventCallback {
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

    // --- SurfaceView overlay behind Flutter ---

    private fun overlayInit() {
        if (overlaySurfaceView != null) {
            Log.d(TAG, "overlay_init: already initialized")
            return
        }

        Log.d(TAG, "overlay_init: creating RtspSurfaceView behind Flutter")

        val root = findViewById<ViewGroup>(android.R.id.content)
        val container = root.getChildAt(0) as? ViewGroup ?: root

        if (mediaHelper == null) {
            mediaHelper = NativeMediaCodecHelper(applicationContext)
        }
        mediaHelper?.eventCallback = playerCallback

        overlaySurfaceView = mediaHelper!!.createSurfaceView().apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        container.addView(overlaySurfaceView, 0)
        mediaHelper?.setupParentLayoutListener()
    }

    private fun overlayPlay(url: String) {
        if (overlaySurfaceView == null) {
            overlayInit()
        }
        mediaHelper?.play(url)
        Log.d(TAG, "overlay playing: $url")
    }

    private fun overlayStop() {
        mediaHelper?.stop()
        Log.d(TAG, "overlay stopped")
    }

    private fun overlayDispose() {
        Log.d(TAG, "overlay dispose")
        // Stop recording if active — surfaceView is about to be destroyed
        if (captureHelper?.isRecording == true) {
            captureHelper?.stopRecording()
        }
        videoWidth = 0
        videoHeight = 0
        mediaHelper?.eventCallback = null
        mediaHelper?.release()
        mediaHelper = null

        overlaySurfaceView?.let { sv ->
            (sv.parent as? ViewGroup)?.removeView(sv)
        }
        overlaySurfaceView = null
    }

    override fun onStop() {
        super.onStop()
        // Stop recording if active — surface will be destroyed
        if (captureHelper?.isRecording == true) {
            captureHelper?.stopRecording()
        }
        // Activity no longer visible (recents, home, sleep).
        // Stop the RTSP stream — the SurfaceView surface will be destroyed
        // and the decoder killed. Stopping explicitly ensures Flutter knows
        // the stream is dead (prevents stale "playing" state).
        Log.d(TAG, "onStop: stopping stream")
        overlayStop()
    }

    override fun onStart() {
        super.onStart()
        // Activity becoming visible again — tell Flutter to re-probe.
        // This is more reliable than Flutter's WidgetsBindingObserver on Samsung.
        Log.d(TAG, "onStart: notifying Flutter")
        methodChannel?.invokeMethod("nativeEvent", mapOf("event" to "foregrounded"))
    }

    override fun onDestroy() {
        captureHelper?.release()
        captureHelper = null
        overlayDispose()
        super.onDestroy()
    }
}
