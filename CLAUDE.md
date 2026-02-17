# Hawkeye WiFi Viewer

RTSP video streaming app for WiFi cameras. Flutter UI with native Kotlin MediaCodec integration.

## Tech Stack

- **Flutter** (Dart) — UI layer, Material 3 dark theme
- **Kotlin 2.2.0** — native Android bridge for RTSP video
- **rtsp-client-android 5.6.3** — direct RTSP→MediaCodec pipeline for low-latency playback
- **Gradle Kotlin DSL** (Gradle 8.12, AGP 8.7.3)
- Target SDK 36, Min SDK 24, Java 11

## Project Structure

```
lib/                          # Flutter/Dart source (UI + logic)
android/app/src/main/kotlin/  # Native Kotlin source
  com/hawkeye/wifi/viewer/
    MainActivity.kt           # Flutter activity + native RTSP bridge via MethodChannel
    NativeMediaCodecHelper.kt # RTSP→MediaCodec player with aspect ratio handling
    NativeVlcHelper.kt        # VlcEventCallback interface (shared event contract)
android/app/build.gradle.kts  # App-level build config
android/build.gradle.kts      # Project-level build config
assets/HawkEyewifi.png        # App launcher icon source (256x256)
pubspec.yaml                  # Flutter dependencies
```

## App Icon

Source: `assets/HawkEyewifi.png`. Generated via `flutter_launcher_icons` (configured in `pubspec.yaml`). To regenerate after replacing the source PNG: `dart run flutter_launcher_icons`

## Architecture

RtspSurfaceView (aspect-ratio-sized) behind transparent Flutter UI (`TransparencyMode.transparent`). Flutter controls playback via MethodChannel (`hawkeye/native_vlc`). The rtsp-client-android library feeds RTSP/RTP H.264 NAL units directly into Android's hardware MediaCodec decoder — no intermediate buffering. Latency is ~100-300ms.

`NativeMediaCodecHelper` manages the RtspSurfaceView, handles aspect ratio sizing with margin-based centering, and forwards RTSP status events to Flutter via the `VlcEventCallback` interface. Low-latency SPS rewriting is enabled (`experimentalUpdateSpsFrameWithLowLatencyParams`) to force `maxDecFrameBuffering=1` and `numReorderFrames=0`.

### Event Flow

RTSP events flow: `RtspStatusListener` → `NativeMediaCodecHelper` → `VlcEventCallback` → `MainActivity` → MethodChannel `nativeEvent` → Flutter `_onNativeMethodCall`. Events: `playing`, `error`, `ended`, `stopped`, `videoSize`.

Auto-reconnect uses a two-tier strategy: fast replay (first 6 attempts at 500ms intervals, just retries the same RTSP URL) then falls back to full probe with exponential backoff (300ms–5s). Max 10 total attempts.

### Aspect Ratio Handling

RtspSurfaceView does NOT handle aspect ratio internally — it stretches to fill its view bounds. `NativeMediaCodecHelper.updateViewAspectRatio()` calculates correct view dimensions from the video's aspect ratio and parent container size, then applies FrameLayout.LayoutParams with margins to center the view. This is called on `onRtspFrameSizeChanged` and on parent layout changes (rotation).

Do NOT use Gravity.CENTER — FlutterView's layout system does not reliably honor FrameLayout gravity on child views during config changes. Use margin-based centering instead.

### Rotation Handling

An `OnLayoutChangeListener` on the parent container detects dimension changes on rotation and calls `updateViewAspectRatio()` to recalculate the SurfaceView size and margins.

## MethodChannel API

Channel: `hawkeye/native_vlc`

| Method               | Direction     | Purpose                        |
|----------------------|---------------|--------------------------------|
| `overlay_init`       | Flutter→Native | Initialize overlay RTSP surface |
| `overlay_play`       | Flutter→Native | Start RTSP stream (pass URL)   |
| `overlay_stop`       | Flutter→Native | Stop playback                  |
| `overlay_dispose`    | Flutter→Native | Release resources              |
| `overlay_isPlaying`  | Flutter→Native | Query playback state           |
| `overlay_getVideoSize` | Flutter→Native | Get current video dimensions  |
| `nativeEvent`        | Native→Flutter | RTSP events (playing/error/ended/stopped/videoSize) |

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
- RTSP/MediaCodec configuration changes go in `NativeMediaCodecHelper.kt`
- Cleartext HTTP traffic is allowed (required for local camera streams)
- Impeller is disabled; Skia renderer is used for stable compositing with native SurfaceView

## Key Dependencies

**Flutter (pubspec.yaml):**
- `network_info_plus` — WiFi network detection
- `permission_handler` — runtime permissions

**Android (build.gradle.kts):**
- `com.github.alexeyvasilyev:rtsp-client-android:5.6.3` — direct RTSP→MediaCodec (via JitPack)

## Permissions

INTERNET, ACCESS_WIFI_STATE, ACCESS_NETWORK_STATE, ACCESS_FINE_LOCATION, CHANGE_WIFI_MULTICAST_STATE

## Known Considerations

- ProGuard keeps rtsp-client-android classes (`com.alexvas.**`) and app classes
- No automated tests or CI/CD currently configured
- Test tablet: Samsung SM-T290, device ID `R9WN809CT0J`
- Camera protocol: WebSocket on :2023 → HTTP GET /live_streaming → RTSP on :554/preview
- Camera IP: 192.168.42.1 (Q2VIEW WiFi network)
