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
    NativeVlcHelper.kt        # Shared VLC setup (low-latency config)
android/app/build.gradle.kts  # App-level build config
android/build.gradle.kts      # Project-level build config
assets/HawkEyewifi.png        # App launcher icon source (256x256)
pubspec.yaml                  # Flutter dependencies
```

## App Icon

Source: `assets/HawkEyewifi.png`. Generated via `flutter_launcher_icons` (configured in `pubspec.yaml`). To regenerate after replacing the source PNG: `dart run flutter_launcher_icons`

## Architecture

Native SurfaceView (MATCH_PARENT) behind transparent Flutter UI (`TransparencyMode.transparent`). Flutter controls VLC via MethodChannel (`hawkeye/native_vlc`). VLC handles aspect ratio scaling and centering internally via `setWindowSize()`. Near-native latency. Supports rotation, auto-reconnect on stream loss, and camera switching.

`NativeVlcHelper` configures VLC for low-latency streaming (live-caching=0, network-caching=100, RTSP over TCP, hardware decoding).

### Event Flow

VLC events flow: `NativeVlcHelper` → `VlcEventCallback` → `MainActivity` → MethodChannel `nativeEvent` → Flutter `_onNativeMethodCall`. Events: `playing`, `error`, `ended`, `stopped`, `videoSize`.

Auto-reconnect uses exponential backoff (300ms–5s, max 10 attempts). On stream loss, the overlay is kept alive and `_probe(fastReconnect: true)` skips port scanning.

### Rotation Handling

SurfaceView stays MATCH_PARENT — never resized via LayoutParams. On rotation, `surfaceChanged()` calls `vlcHelper.updateWindowSize(w, h)` which tells VLC the new surface dimensions. VLC re-scales and re-centers the video internally. Do NOT attempt to resize/center the SurfaceView via Gravity or margins — FlutterView's layout system does not reliably honor FrameLayout gravity on child views during config changes.

## MethodChannel API

Channel: `hawkeye/native_vlc`

| Method               | Direction     | Purpose                        |
|----------------------|---------------|--------------------------------|
| `overlay_init`       | Flutter→Native | Initialize overlay VLC surface |
| `overlay_play`       | Flutter→Native | Start RTSP stream (pass URL)   |
| `overlay_stop`       | Flutter→Native | Stop playback                  |
| `overlay_dispose`    | Flutter→Native | Release VLC resources          |
| `overlay_isPlaying`  | Flutter→Native | Query playback state           |
| `overlay_getVideoSize` | Flutter→Native | Get current video dimensions  |
| `nativeEvent`        | Native→Flutter | VLC events (playing/error/ended/stopped/videoSize) |

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
- Impeller is disabled; Skia renderer is used for stable compositing with native SurfaceView

## Key Dependencies

**Flutter (pubspec.yaml):**
- `network_info_plus` — WiFi network detection
- `permission_handler` — runtime permissions

**Android (build.gradle.kts):**
- `org.videolan.android:libvlc-all:3.6.3` — native VLC

## Permissions

INTERNET, ACCESS_WIFI_STATE, ACCESS_NETWORK_STATE, ACCESS_FINE_LOCATION, CHANGE_WIFI_MULTICAST_STATE

## Known Issues

- Latency is not yet as fast as VS Borescope — there's still a small gap, possibly from VLC config tuning or RTSP session negotiation overhead.
- Camera switching delay is ~4-7 seconds due to full probe cycle (WebSocket + HTTP API + RTSP connection). Fast reconnect path helps for stream recovery but camera switching still requires re-probing.

## Known Considerations

- ProGuard keeps VLC classes (`org.videolan.**`) and app classes for JNI safety
- Gradle JVM args are set high (8G heap) — needed for VLC native compilation
- No automated tests or CI/CD currently configured
- `IVLCVout.OnNewVideoLayoutListener` MUST call `vlcVout.setWindowSize()` or VLC aborts playback immediately
- Test tablet: Samsung SM-T290, device ID `R9WN809CT0J`
