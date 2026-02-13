# Keep all VLC classes — JNI native code references these by name
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

# Keep native video classes (used via MethodChannel / Intent)
-keep class com.hawkeye.wifi.viewer.NativeVlcHelper { *; }
-keep class com.hawkeye.wifi.viewer.NativeVideoActivity { *; }
