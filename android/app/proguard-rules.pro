# Keep rtsp-client-android classes (used for headless RtspClient recording)
-keep class com.alexvas.** { *; }
-dontwarn com.alexvas.**

# Keep IJKPlayer classes (native JNI bindings)
-keep class tv.danmaku.ijk.media.player.** { *; }
-dontwarn tv.danmaku.ijk.media.player.**

# Keep native video classes (used via MethodChannel)
-keep class com.hawkeye.wifi.viewer.IjkPlayerHelper { *; }
-keep class com.hawkeye.wifi.viewer.CaptureHelper { *; }
