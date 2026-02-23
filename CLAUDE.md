# Hawkeye WiFi Viewer

RTSP video streaming app for Q2 WiFi borescope cameras. Flutter UI with native Kotlin MediaCodec integration.

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

Auto-reconnect uses a two-tier strategy: fast replay (first 3 attempts at 1s intervals with full dispose+init cycle) then falls back to full probe with exponential backoff (1s-8s). Max 10 total attempts. Only one RTSP URL is tried per probe cycle to avoid flooding the camera's single-client RTSP server.

### Recovery & Resilience

Multiple layers handle stream recovery:

1. **Fast replay / full probe** — handles brief stream interruptions while WiFi is up. Fast replay does full dispose+init (not just stop+play) to ensure clean decoder state.
2. **Connectivity monitoring** (`connectivity_plus`) — detects WiFi drop/restore, pauses reconnect when offline, re-probes on WiFi restore
3. **Native lifecycle** (`onStop`/`onStart`) — `MainActivity.onStop()` stops the stream, `onStart()` sends `foregrounded` event to Flutter for a fresh probe. Flutter's `WidgetsBindingObserver.resumed` is NOT used — native lifecycle is the sole recovery trigger on Samsung.
4. **Watchdog timer** (5s) — queries native `overlay_isPlaying` (checks `surface.isValid`) to detect silently-dead streams. Only force-resets probes stuck for >20s to avoid flooding.
5. **Probe generation counter** (`_probeId`) — checked at every key step (after dispose, after port scan, after WebSocket, after HTTP, before RTSP play) so stale probes bail immediately.

#### Anti-Flooding Design

The camera's RTSP server only supports one client at a time. Concurrent connection attempts overwhelm it into a permanent error state requiring app restart. Critical invariants:

- **Never run concurrent probes** — `_connecting` flag gates entry; `foregrounded` skips if a probe is already running
- **One RTSP URL per probe** — only tries the primary `/preview` path, not multiple URLs
- **500ms delay after dispose** — gives camera time to release old RTSP session before new connection
- **Spaced retries** — 1s minimum between attempts (was 300-500ms), exponential backoff up to 8s
- **Watchdog patience** — waits 20s before force-resetting a probe (was 5s)

On every reconnect, the overlay is fully disposed and recreated (`overlay_dispose` + `overlay_init`) to prevent pixelation from stale decoder state.

### Connection Hint

A 10-second timer shows "check Q2 borescope WiFi network" when the camera is unreachable. The timer must NOT reset on each probe (watchdog fires every ~5s, would prevent the timer from ever reaching 10s). Pattern: check if already showing, check if timer already active, only then start a new timer. Cancel on `playing` event, reset on `foregrounded`.

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
- **Video**: Taps into the live view's NAL stream via `RtspSurfaceView.setDataListener()`:
  1. SPS/PPS fetched via lightweight RTSP DESCRIBE request (single TCP exchange, no SETUP/PLAY)
  2. NAL payloads copied to clean byte arrays via `ArrayBlockingQueue` (decouples RTSP thread)
  3. NAL type 1 rewritten to type 5 (IDR) for gallery thumbnail generation
  4. Every frame marked as `BUFFER_FLAG_KEY_FRAME` (camera sends all-intra)
  5. Saved to `DCIM/{wifiName}/`
- Recording automatically stops on `onStop()` or `overlay_dispose` (stream destruction)

#### Direct Mux Approach

Camera sends all H.264 frames as **non-IDR (NAL type 1)** but they are all-intra (independently decodable). The direct mux approach rewrites NAL type 1→5 and sets `BUFFER_FLAG_KEY_FRAME` so MP4 players treat every frame as a sync sample.

A transcode pipeline (decoder→encoder) was tried first but doesn't work on resource-limited devices — the tablet's Snapdragon 429 can't allocate a second hardware decoder (error 0x80001001), and the software decoder can't produce output from non-IDR frames.

#### MediaMuxer Pitfalls

- **Do NOT add AVCC length prefixes** — `MediaMuxer.writeSampleData()` handles them internally. Adding `putInt(len)` creates a double prefix that corrupts playback entirely.
- **Do NOT use `ByteBuffer.wrap(array, offset, len)`** — with `BufferInfo.offset=0`, MediaMuxer may read from array start instead of buffer position, corrupting the lower-right corner of the video. Always copy NAL payload to a clean array first: `ByteBuffer.wrap(cleanArray)`.
- **Gallery thumbnails**: NAL type 1→5 rewrite is intended to help, but Q2 thumbnails may still show a placeholder (IDR slice header mismatch with `idr_pic_id` field).

### Toolbar UI

Right-side vertical toolbar (V3 View style) replaces the old bottom capture bar. No status text displayed in UI.

- **Landscape**: Vertical column on the right, centered vertically. Symmetric left/right insets reserve space so video doesn't overlap buttons.
- **Portrait**: Horizontal row at the bottom, centered. Bottom inset reserves space below video.
- Three buttons: WiFi status (green when streaming, shows SSID snackbar on tap), camera (black icon, photo capture), videocam (red icon, video recording)
- Button background: `#555555` gray, `borderRadius: 8`
- Record button turns red with stop icon and elapsed timer during recording
- Photo capture shows brief white flash overlay as feedback
- Loading spinner (CircularProgressIndicator) shown when connecting, with connection hint after 10s
- Location permission requested lazily on first capture/WiFi tap, not on launch

### Native Video Insets

`NativeMediaCodecHelper` supports reserved insets (`setReservedInsets`) so Flutter can reserve space for the toolbar. Video is aspect-ratio-fitted within the available area after subtracting insets, then centered with margin-based positioning. Flutter sends insets via `overlay_setInsets` MethodChannel call on orientation change.

## MethodChannel API

Channel: `hawkeye/native_vlc`

| Method               | Direction     | Purpose                        |
|----------------------|---------------|--------------------------------|
| `overlay_init`       | Flutter→Native | Initialize overlay RTSP surface |
| `overlay_play`       | Flutter→Native | Start RTSP stream (pass URL)   |
| `overlay_stop`       | Flutter→Native | Stop playback                  |
| `overlay_dispose`    | Flutter→Native | Release resources              |
| `overlay_setInsets`  | Flutter→Native | Set reserved insets for toolbar |
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
- Test phone: Samsung SM-S926U, device ID `R5CX15DGA2H`
- Android window/launch backgrounds must be black (not white) for optimal viewing
- Samsung phones need native `onStop`/`onStart` lifecycle for reliable recovery — Flutter's `WidgetsBindingObserver` is unreliable on Samsung
- Camera protocol: WebSocket on :2023 → HTTP GET /live_streaming → RTSP on :554/preview
- Camera IP: 192.168.42.1 (Q2 borescope WiFi network, user-configurable SSID)

## Sister Project

**Hawkeye Xplor Viewer** (`C:\Users\holmes\Projects\hawkeye_xplor_viewer`) — same UI, but uses Jieli SDK for Xplor borescope cameras (proprietary UDP, not RTSP). Decision: keep as separate apps (different streaming protocols benefit from isolation).
