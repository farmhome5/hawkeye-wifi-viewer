# Keep rtsp-client-android classes (used via reflection/JNI)
-keep class com.alexvas.** { *; }
-dontwarn com.alexvas.**

# Keep native video classes (used via MethodChannel)
-keep class com.hawkeye.wifi.viewer.NativeMediaCodecHelper { *; }
