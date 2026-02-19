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
    CaptureHelper.kt          # Photo capture (PixelCopy) + video recording (direct mux)
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

RTSP events flow: `RtspStatusListener` → `NativeMediaCodecHelper` → `VlcEventCallback` → `MainActivity` → MethodChannel `nativeEvent` → Flutter `_onNativeMethodCall`. Events: `playing`, `error`, `ended`, `stopped`, `videoSize`, `foregrounded`.

Auto-reconnect uses a two-tier strategy: fast replay (first 6 attempts at 500ms intervals, just retries the same RTSP URL) then falls back to full probe with exponential backoff (300ms–5s). Max 10 total attempts.

### Recovery & Resilience

Multiple layers handle stream recovery:

1. **Fast replay / full probe** — handles brief stream interruptions while WiFi is up
2. **Connectivity monitoring** (`connectivity_plus`) — detects WiFi drop/restore, pauses reconnect when offline, re-probes on WiFi restore
3. **Native lifecycle** (`onStop`/`onStart`) — `MainActivity.onStop()` stops the stream, `onStart()` sends `foregrounded` event to Flutter for a fresh probe. More reliable than Flutter's `WidgetsBindingObserver` on Samsung devices.
4. **Watchdog timer** (5s) — queries native `overlay_isPlaying` (checks `surface.isValid`) to detect silently-dead streams (e.g. surface destroyed without RTSP disconnect event). Force-resets stuck probes.
5. **Probe generation counter** (`_probeId`) — prevents stale async probes from interfering when a newer probe is started by lifecycle/watchdog.

On every reconnect, the overlay is fully disposed and recreated (`overlay_dispose` + `overlay_init`) to prevent pixelation from stale decoder state.

### Screen Wake Lock

`keepScreenOn` is set on the RtspSurfaceView when the stream starts playing, and cleared when it stops. This prevents the device from sleeping while actively streaming.

### Aspect Ratio Handling

RtspSurfaceView does NOT handle aspect ratio internally — it stretches to fill its view bounds. `NativeMediaCodecHelper.updateViewAspectRatio()` calculates correct view dimensions from the video's aspect ratio and parent container size, then applies FrameLayout.LayoutParams with margins to center the view. This is called on `onRtspFrameSizeChanged` and on parent layout changes (rotation).

Do NOT use Gravity.CENTER — FlutterView's layout system does not reliably honor FrameLayout gravity on child views during config changes. Use margin-based centering instead.

### Rotation Handling

An `OnLayoutChangeListener` on the parent container detects dimension changes on rotation and calls `updateViewAspectRatio()` to recalculate the SurfaceView size and margins.

### Capture: Photos & Video Clips

`CaptureHelper.kt` handles both photo capture and video recording:

- **Photos**: `PixelCopy.request()` on the SurfaceView → JPEG saved to `DCIM/{wifiName}/` via MediaStore (API 29+) or direct file + MediaScanner (API 24-28)
- **Video**: Taps into the live view's NAL stream via `RtspSurfaceView.setDataListener()`, rewrites NAL type 1→5 (IDR), converts Annex B→AVCC format, and muxes directly to MP4 via `MediaMuxer`. Saved to `DCIM/{wifiName}/`
- SPS/PPS are fetched via a lightweight RTSP DESCRIBE request (single TCP exchange, no SETUP/PLAY)
- NALs flow through an `ArrayBlockingQueue` to decouple the RTSP thread from the muxing thread
- Recording automatically stops on `onStop()` or `overlay_dispose` (stream destruction)

#### Direct Mux Approach

Camera sends all H.264 frames as **non-IDR (NAL type 1)** but they are all-intra (independently decodable). The direct mux approach rewrites NAL type 1→5 and sets `BUFFER_FLAG_KEY_FRAME` so MP4 players treat every frame as a sync sample.

A transcode pipeline (decoder→encoder) was tried first but doesn't work on resource-limited devices — the tablet's Snapdragon 429 can't allocate a second hardware decoder (error 0x80001001), and the software decoder can't produce output from non-IDR frames.

### Capture Bar UI

Bottom bar replaces the old floating status text. Adaptive layout:
- **Landscape**: `Row` — status text left, photo + record buttons right
- **Portrait**: `Column` — status text top, buttons below
- Visible when streaming; falls back to status-only text when connecting/loading
- Record button turns red with elapsed timer during recording
- Photo capture shows brief white flash overlay as feedback

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
| `capture_photo`      | Flutter→Native | Take still photo (PixelCopy)   |
| `start_recording`    | Flutter→Native | Start video recording          |
| `stop_recording`     | Flutter→Native | Stop video recording           |
| `is_recording`       | Flutter→Native | Query recording state          |
| `nativeEvent`        | Native→Flutter | Events: playing/error/ended/stopped/videoSize/foregrounded/photo_saved/recording_stopped/capture_error |

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
- `connectivity_plus` — WiFi connectivity monitoring (auto-reconnect on WiFi restore)
- `network_info_plus` — WiFi network detection
- `permission_handler` — runtime permissions

**Android (build.gradle.kts):**
- `com.github.alexeyvasilyev:rtsp-client-android:5.6.3` — direct RTSP→MediaCodec (via JitPack)

## Permissions

INTERNET, ACCESS_WIFI_STATE, ACCESS_NETWORK_STATE, ACCESS_FINE_LOCATION, CHANGE_WIFI_MULTICAST_STATE, WRITE_EXTERNAL_STORAGE (API < 29), READ_MEDIA_IMAGES (API 33+), READ_MEDIA_VIDEO (API 33+)

## Known Considerations

- ProGuard keeps rtsp-client-android classes (`com.alexvas.**`) and app classes
- No automated tests or CI/CD currently configured
- Test tablet: Samsung SM-T290, device ID `R9WN809CT0J`
- Test phone: Samsung, device ID `R5CX15DGA2H`
- Android window/launch backgrounds must be black (not white) for optimal viewing
- Samsung phones need native `onStop`/`onStart` lifecycle for reliable recovery — Flutter's `WidgetsBindingObserver` is unreliable on Samsung
- Camera protocol: WebSocket on :2023 → HTTP GET /live_streaming → RTSP on :554/preview
- Camera IP: 192.168.42.1 (Q2VIEW WiFi network)
