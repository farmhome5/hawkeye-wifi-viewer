# Hawkeye WiFi Viewer

RTSP video streaming app for WiFi cameras. Flutter UI with native Kotlin VLC integration.

## Tech Stack

- **Flutter** (Dart) — UI layer, Material 3 dark theme
- **Kotlin 2.2.0** — native Android bridge for VLC video
- **VLC (libvlc-all 3.6.3)** — low-latency RTSP playback
- **Gradle Kotlin DSL** (Gradle 8.12, AGP 8.7.3)
- Target SDK 36, Min SDK 21, Java 11

## Project Structure

```
lib/                          # Flutter/Dart source (UI + logic)
android/app/src/main/kotlin/  # Native Kotlin source
  com/hawkeye/wifi/viewer/
    MainActivity.kt           # Flutter activity + native VLC bridge via MethodChannel
    NativeVideoActivity.kt    # Standalone fullscreen RTSP activity
    NativeVlcHelper.kt        # Shared VLC setup (low-latency config)
android/app/build.gradle.kts  # App-level build config
android/build.gradle.kts      # Project-level build config
pubspec.yaml                  # Flutter dependencies
```

## Architecture

Three video rendering modes, selectable at runtime via tune icon in the status overlay:

1. **Flutter VLC** (default) — `flutter_vlc_player` renders through TextureView → Flutter texture registry → Skia compositor. Highest latency (~1s behind real-time vs VS Borescope app).
2. **Native Surface (Exp.1)** — Native SurfaceView behind transparent Flutter UI (`TransparencyMode.transparent`). Flutter controls VLC via MethodChannel (`hawkeye/native_vlc`). Near-native latency, occasionally matching VS Borescope, sometimes ~0.5s behind.
3. **Native Activity (Exp.2)** — `NativeVideoActivity` launched via Intent, zero Flutter involvement. Similar latency to Exp.1. Has back button (X) in top-left corner.

`NativeVlcHelper` is shared between approaches 2 and 3, configuring VLC for low-latency streaming (live-caching=0, network-caching=100, RTSP over TCP, hardware decoding).

The `stable-before-native-bypass` branch preserves the pre-experiment codebase (Flutter VLC only).

## MethodChannel API

Channel: `hawkeye/native_vlc`

| Method               | Direction     | Purpose                        |
|----------------------|---------------|--------------------------------|
| `overlay_init`       | Flutter→Native | Initialize overlay VLC surface |
| `overlay_play`       | Flutter→Native | Start RTSP stream (pass URL)   |
| `overlay_stop`       | Flutter→Native | Stop playback                  |
| `overlay_dispose`    | Flutter→Native | Release VLC resources          |
| `overlay_isPlaying`  | Flutter→Native | Query playback state           |
| `launch_native_activity` | Flutter→Native | Launch standalone video activity |

## Build & Run

```bash
# Run in debug mode (device must be connected)
flutter run

# Build release APK
flutter build apk --release

# Run only Android native build
cd android && ./gradlew assembleDebug
```

## Conventions

- Native Kotlin follows standard Android conventions (PascalCase classes, camelCase functions)
- Flutter/Dart follows standard Dart conventions (snake_case files, camelCase variables, PascalCase classes)
- VLC configuration changes go in `NativeVlcHelper.kt` — both approaches share it
- Cleartext HTTP traffic is allowed (required for local camera streams)
- Impeller is disabled; Skia renderer is used for stable platform view compositing with VLC

## Key Dependencies

**Flutter (pubspec.yaml):**
- `flutter_vlc_player` — Flutter-side VLC wrapper
- `network_info_plus` — WiFi network detection
- `permission_handler` — runtime permissions
- `url_launcher` — external URL handling

**Android (build.gradle.kts):**
- `org.videolan.android:libvlc-all:3.6.3` — native VLC

## Permissions

INTERNET, ACCESS_WIFI_STATE, ACCESS_NETWORK_STATE, ACCESS_FINE_LOCATION, CHANGE_WIFI_MULTICAST_STATE

## Known Issues

- **Flutter VLC does not reinitialize after mode switching.** After toggling to Native Surface or Native Activity and back, the Flutter VLC player fails with "Already Initialized". Requires app restart to recover Flutter mode. Root cause: `VlcPlayerController` doesn't support re-initialization after the platform view is recreated.
- Native modes (Exp.1 and Exp.2) are not yet as fast as VS Borescope — there's still a small gap, possibly from VLC config tuning or RTSP session negotiation overhead.

## Known Considerations

- ProGuard keeps VLC classes (`org.videolan.**`) and app classes for JNI safety
- Gradle JVM args are set high (8G heap) — needed for VLC native compilation
- No automated tests or CI/CD currently configured
- `NativeVideoActivity` uses plain `Activity` (not AppCompatActivity) to avoid theme incompatibility crashes
