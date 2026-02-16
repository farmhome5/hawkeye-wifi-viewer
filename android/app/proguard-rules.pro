# Keep all VLC classes — JNI native code references these by name
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

# Keep native video classes (used via MethodChannel)
-keep class com.hawkeye.wifi.viewer.NativeVlcHelper { *; }
