import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:network_info_plus/network_info_plus.dart';
import 'package:permission_handler/permission_handler.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();

  FlutterError.onError = (details) {
    debugPrint('[HAWKEYE] FlutterError: ${details.exception}');
    debugPrint('[HAWKEYE] ${details.stack}');
  };

  runZonedGuarded(
    () => runApp(const MyApp()),
    (error, stack) {
      debugPrint('[HAWKEYE] Uncaught error: $error');
      debugPrint('[HAWKEYE] $stack');
    },
  );
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Hawkeye WiFi Viewer',
      theme: ThemeData.dark().copyWith(
        scaffoldBackgroundColor: const Color(0xFF0f0f10),
        colorScheme: ColorScheme.dark(
          primary: const Color(0xFFe74c3c),
          secondary: const Color(0xFFe74c3c),
          surface: const Color(0xFF1b1b1b),
          brightness: Brightness.dark,
        ),
        appBarTheme: const AppBarTheme(
          backgroundColor: Color(0xFF1b1b1b),
          elevation: 0,
        ),
        elevatedButtonTheme: ElevatedButtonThemeData(
          style: ElevatedButton.styleFrom(
            backgroundColor: const Color(0xFFe74c3c),
            foregroundColor: Colors.white,
          ),
        ),
        outlinedButtonTheme: OutlinedButtonThemeData(
          style: OutlinedButton.styleFrom(
            foregroundColor: const Color(0xFFe74c3c),
            side: const BorderSide(color: Color(0xFFe74c3c)),
          ),
        ),
      ),
      home: const LiveView(),
      debugShowCheckedModeBanner: false,
    );
  }
}

class LiveView extends StatefulWidget {
  const LiveView({super.key});
  @override
  State<LiveView> createState() => _LiveViewState();
}

class _LiveViewState extends State<LiveView> with WidgetsBindingObserver {
  String _ip = '192.168.42.1';

  final List<String> _ipCandidates = const [
    '192.168.42.1',
    '192.168.42.254',
    '192.168.42.100',
    '192.168.10.123',
    '192.168.1.1',
    '192.168.0.1',
    '192.168.4.1',
  ];

  // Native surface via MethodChannel
  static const _nativeChannel = MethodChannel('hawkeye/native_vlc');
  bool _nativeOverlayActive = false;
  String _nativeSurfaceState = 'idle';
  Timer? _reconnectTimer;
  int _reconnectAttempt = 0;
  static const int _maxReconnectAttempts = 10;

  // Generation counter — prevents stale async probes from interfering
  int _probeId = 0;

  // Track when the current probe started (for watchdog stuck detection)
  DateTime _probeStartTime = DateTime.now();

  // Camera command channel (WebSocket on port 2023)
  WebSocket? _cameraWs;

  // Last successful RTSP URL — used for fast replay on stream loss
  String? _lastRtspUrl;

  // UI/state
  bool _connecting = false;
  bool _isRtspMode = false;
  String _status = 'Idle'; // ignore: unused_field — kept for debug setState breadcrumbs

  // WiFi name
  String? _wifiName;

  // WiFi connectivity monitoring
  StreamSubscription<List<ConnectivityResult>>? _connectivitySub;
  bool _hasWifi = false;

  // Watchdog: periodic check to recover from any missed reconnect scenario
  Timer? _watchdogTimer;

  // Video aspect ratio — auto-detected from stream
  double _videoAspectRatio = 1.0;
  int _videoWidth = 0;
  int _videoHeight = 0;

  // Toolbar insets — track last sent orientation to avoid redundant calls
  Orientation? _lastInsetOrientation;
  static const double _toolbarReserve = 64.0; // logical px reserved for toolbar

  // Capture state
  bool _isRecording = false;
  Duration _recordingDuration = Duration.zero;
  Timer? _recordingTimer;
  bool _photoFlash = false; // brief visual feedback on photo capture

  // Connection timeout — show WiFi hint after failing to connect
  bool _showConnectionHint = false;
  Timer? _connectionHintTimer;
  static const int _connectionHintDelaySec = 10;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    SystemChrome.setPreferredOrientations([
      DeviceOrientation.portraitUp,
      DeviceOrientation.portraitDown,
      DeviceOrientation.landscapeLeft,
      DeviceOrientation.landscapeRight,
    ]);
    _nativeChannel.setMethodCallHandler(_onNativeMethodCall);
    _initConnectivityMonitor();
    _startWatchdog();
    _detectWifiName();
    _probe();
  }

  // ---- App lifecycle (power save / background recovery) --------------------

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    debugPrint('[HAWKEYE] Lifecycle: $state');
    if (state == AppLifecycleState.paused) {
      // App going to background — kill any running probe and stop reconnects.
      // Increment _probeId so any in-flight async probe exits cleanly.
      _probeId++;
      _connecting = false;
      _reconnectTimer?.cancel();
      _reconnectAttempt = 0;
    }
    // Note: resumed is handled by native foregrounded event (more reliable on Samsung).
    // See 'foregrounded' case in _onNativeMethodCall.
  }

  // ---- Watchdog: safety net for missed reconnect scenarios -----------------

  void _startWatchdog() {
    _watchdogTimer = Timer.periodic(const Duration(seconds: 5), (_) async {
      if (!mounted) return;

      // Ask the native side if the stream is genuinely alive —
      // don't trust _nativeSurfaceState which can be stale after
      // recents/sleep when the surface is destroyed silently.
      if (_nativeSurfaceState == 'playing' && _nativeOverlayActive) {
        try {
          final alive = await _nativeChannel.invokeMethod('overlay_isPlaying');
          if (alive == true) return; // Stream is genuinely healthy
        } catch (_) {}

        // Surface destroyed or decoder dead — state variable is stale
        debugPrint('[HAWKEYE] Watchdog: stream dead (surface destroyed?), recovering');
        _probeId++;
        _connecting = false;
        setState(() {
          _nativeSurfaceState = 'stopped';
          _isRtspMode = false;
          _status = 'Reconnecting...';
        });
        _probe();
        return;
      }

      // If a probe is running, only force-reset if it's been stuck for >20s.
      // This prevents connection flooding from watchdog firing new probes
      // while old ones are still making RTSP connections.
      if (_connecting) {
        final elapsed = DateTime.now().difference(_probeStartTime).inSeconds;
        if (elapsed < 20) {
          return; // Probe is still young, let it work
        }
        debugPrint('[HAWKEYE] Watchdog: probe stuck for ${elapsed}s, force-resetting');
        _probeId++;
        _connecting = false;
      }

      // Not playing and not connecting — start a fresh probe
      if (!_connecting && _nativeSurfaceState != 'playing') {
        _reconnectAttempt = 0;
        _probe();
      }
    });
  }

  // ---- Native surface event handler (from Kotlin MethodChannel) -----------

  Future<dynamic> _onNativeMethodCall(MethodCall call) async {
    if (call.method == 'nativeEvent') {
      final args = Map<String, dynamic>.from(call.arguments as Map);
      final event = args['event'] as String;
      debugPrint('[HAWKEYE] Native event: $event $args');

      switch (event) {
        case 'playing':
          _reconnectAttempt = 0;
          _reconnectTimer?.cancel();
          _connectionHintTimer?.cancel();
          if (mounted) {
            setState(() {
              _nativeSurfaceState = 'playing';
              _isRtspMode = true;
              _showConnectionHint = false;
              final source = _wifiName ?? 'camera';
              _status = 'Streaming from $source';
            });
          }
          break;

        case 'videoSize':
          final w = args['width'] as int;
          final h = args['height'] as int;
          if (w > 0 && h > 0 && mounted) {
            setState(() {
              _videoAspectRatio = w / h;
              _videoWidth = w;
              _videoHeight = h;
            });
            debugPrint('[HAWKEYE] Video size: ${w}x$h ratio=${_videoAspectRatio.toStringAsFixed(2)}');
          }
          break;

        case 'foregrounded':
          // Sent from native onStart() — activity is visible again after
          // recents/sleep/home. Stream was stopped in onStop().
          // If a probe is already running (e.g., from initState on cold start),
          // don't interrupt it — that causes concurrent probes which flood the camera.
          if (_connecting) {
            debugPrint('[HAWKEYE] Foregrounded — probe already running, skipping');
            return;
          }
          if (_nativeSurfaceState == 'playing') {
            debugPrint('[HAWKEYE] Foregrounded — already playing, skipping');
            return;
          }
          debugPrint('[HAWKEYE] Foregrounded — will re-probe in 1s');
          Future.delayed(const Duration(seconds: 1), () {
            if (!mounted) return;
            if (_connecting) {
              debugPrint('[HAWKEYE] Foregrounded: probe started in the meantime, skipping');
              return;
            }
            // Force fresh probe
            _probeId++;
            _reconnectTimer?.cancel();
            _reconnectAttempt = 0;
            _lastRtspUrl = null;
            setState(() {
              _nativeSurfaceState = 'stopped';
              _isRtspMode = false;
              _showConnectionHint = false;
              _status = 'Resuming...';
            });
            _probe();
          });
          break;

        case 'photo_saved':
          debugPrint('[HAWKEYE] Photo saved: ${args['path']}');
          if (mounted) {
            setState(() => _photoFlash = true);
            Future.delayed(const Duration(milliseconds: 300), () {
              if (mounted) setState(() => _photoFlash = false);
            });
          }
          break;

        case 'recording_stopped':
          debugPrint('[HAWKEYE] Recording stopped: ${args['path']} (${args['durationMs']}ms)');
          _recordingTimer?.cancel();
          if (mounted) {
            setState(() {
              _isRecording = false;
              _recordingDuration = Duration.zero;
            });
          }
          break;

        case 'capture_error':
          debugPrint('[HAWKEYE] Capture error: ${args['message']}');
          _recordingTimer?.cancel();
          if (mounted) {
            setState(() {
              _isRecording = false;
              _recordingDuration = Duration.zero;
            });
          }
          break;

        case 'error':
        case 'ended':
        case 'stopped':
          final wasPlaying = _nativeSurfaceState == 'playing';
          if (mounted) {
            setState(() => _nativeSurfaceState = event);
          }
          if (wasPlaying && mounted) {
            debugPrint('[HAWKEYE] Stream lost ($event) — will reconnect');
            // Stop recording if stream is lost
            if (_isRecording) {
              _nativeChannel.invokeMethod('stop_recording').catchError((_) {});
              _recordingTimer?.cancel();
              setState(() {
                _isRecording = false;
                _recordingDuration = Duration.zero;
              });
            }
            setState(() {
              _isRtspMode = false;
              _status = 'Stream lost — reconnecting...';
            });
            _scheduleNativeReconnect();
          }
          break;
      }
    }
  }

  void _scheduleNativeReconnect() {
    _reconnectTimer?.cancel();

    // Don't waste attempts when WiFi is known to be down —
    // the connectivity listener will re-probe when WiFi returns.
    if (!_hasWifi) {
      debugPrint('[HAWKEYE] No WiFi — skipping reconnect, waiting for connectivity');
      setState(() => _status = 'WiFi disconnected — waiting for network...');
      return;
    }

    if (_reconnectAttempt >= _maxReconnectAttempts) {
      setState(() => _status = 'Reconnecting...');
      _reconnectAttempt = 0;
      _lastRtspUrl = null;
      return;
    }

    // Fast replay: retry same URL without re-probing (first 3 attempts, 1s interval)
    // Then fall back to full probe with exponential backoff
    final useFastReplay = _lastRtspUrl != null && _reconnectAttempt < 3;
    final delayMs = useFastReplay
        ? 1000
        : (1000 * (1 << (_reconnectAttempt - 3).clamp(0, 3))).clamp(1000, 8000);
    _reconnectAttempt++;

    debugPrint('[HAWKEYE] Reconnect attempt $_reconnectAttempt in ${delayMs}ms (fast=$useFastReplay)');
    setState(() => _status = 'Reconnecting...');

    _reconnectTimer = Timer(Duration(milliseconds: delayMs), () {
      if (mounted && !_connecting) {
        if (useFastReplay) {
          _fastReplay();
        } else {
          _probe(fastReconnect: true);
        }
      }
    });
  }

  /// Quickly retry the last RTSP URL without re-probing the camera.
  Future<void> _fastReplay() async {
    if (_connecting || _lastRtspUrl == null) {
      _probe(fastReconnect: true);
      return;
    }
    _connecting = true;
    _probeStartTime = DateTime.now();
    final myProbeId = _probeId;

    try {
      debugPrint('[HAWKEYE] Fast replay: $_lastRtspUrl');
      setState(() => _status = 'Reconnecting...');

      // Full dispose + recreate to ensure clean decoder state
      if (_nativeOverlayActive) {
        await _nativeChannel.invokeMethod('overlay_stop').catchError((_) {});
        await _nativeChannel.invokeMethod('overlay_dispose').catchError((_) {});
        _nativeOverlayActive = false;
        // Give camera time to release the RTSP session
        await Future.delayed(const Duration(milliseconds: 500));
        if (_probeId != myProbeId || !mounted) return;
      }

      await _nativeChannel.invokeMethod('overlay_init');
      _nativeOverlayActive = true;
      await _nativeChannel.invokeMethod('overlay_play', {'url': _lastRtspUrl});
      setState(() => _nativeSurfaceState = 'connecting');

      // Send insets for proper sizing
      _lastInsetOrientation = null;
      if (mounted) {
        final orientation = MediaQuery.of(context).orientation;
        _sendInsets(orientation);
      }

      // Wait up to 6 seconds for playing state
      for (int i = 0; i < 12; i++) {
        await Future.delayed(const Duration(milliseconds: 500));
        if (!mounted || _probeId != myProbeId) return;
        if (_nativeSurfaceState == 'playing') return; // Success!
        if (_nativeSurfaceState == 'error') break; // Fail fast
      }

      // Failed — schedule next attempt
      debugPrint('[HAWKEYE] Fast replay failed, scheduling next attempt');
      _scheduleNativeReconnect();
    } finally {
      if (_probeId == myProbeId) _connecting = false;
    }
  }

  /// Reads WiFi SSID only if location permission is already granted (no prompt).
  /// Safe to call on init, WiFi restore, resume — never shows a permission dialog.
  Future<void> _detectWifiName() async {
    try {
      final status = await Permission.location.status;
      if (status.isGranted) {
        final info = NetworkInfo();
        final name = await info.getWifiName();
        if (name != null && mounted) {
          setState(() {
            _wifiName = name.replaceAll('"', '');
          });
          debugPrint('[HAWKEYE] WiFi SSID: $_wifiName');
        }
      } else {
        debugPrint('[HAWKEYE] Location not yet granted — skipping WiFi name');
      }
    } catch (e) {
      debugPrint('[HAWKEYE] WiFi name detection failed: $e');
    }
  }

  /// Requests location permission if needed, then detects WiFi name.
  /// Call from capture actions and WiFi button tap.
  Future<void> _ensureWifiNameForCapture() async {
    try {
      var status = await Permission.location.status;
      if (!status.isGranted) {
        status = await Permission.location.request();
      }
      if (status.isGranted) {
        final info = NetworkInfo();
        final name = await info.getWifiName();
        if (name != null && mounted) {
          setState(() {
            _wifiName = name.replaceAll('"', '');
          });
          debugPrint('[HAWKEYE] WiFi SSID (ensured): $_wifiName');
        }
      }
    } catch (e) {
      debugPrint('[HAWKEYE] WiFi name ensure failed: $e');
    }
  }

  void _initConnectivityMonitor() {
    // Seed initial WiFi state
    Connectivity().checkConnectivity().then((results) {
      _hasWifi = results.contains(ConnectivityResult.wifi);
      debugPrint('[HAWKEYE] Initial connectivity: $results (wifi=$_hasWifi)');
    });

    _connectivitySub = Connectivity().onConnectivityChanged.listen((results) {
      final wifiNow = results.contains(ConnectivityResult.wifi);
      final hadWifi = _hasWifi;
      _hasWifi = wifiNow;

      debugPrint('[HAWKEYE] Connectivity changed: $results (wifi: $hadWifi→$wifiNow)');

      if (wifiNow && !hadWifi) {
        // WiFi restored — reset and re-probe if not already streaming
        debugPrint('[HAWKEYE] WiFi restored — will re-probe');
        _reconnectTimer?.cancel();
        _reconnectAttempt = 0;
        _lastRtspUrl = null;
        _detectWifiName();
        if (!_connecting && _nativeSurfaceState != 'playing') {
          setState(() => _status = 'WiFi restored — reconnecting...');
          _probe();
        }
      } else if (!wifiNow && hadWifi) {
        // WiFi lost — stop wasting reconnect attempts, wait for network
        debugPrint('[HAWKEYE] WiFi lost — pausing reconnect');
        _reconnectTimer?.cancel();
        _reconnectAttempt = 0;
        _nativeChannel.invokeMethod('overlay_stop').catchError((_) {});
        if (mounted) {
          setState(() {
            _isRtspMode = false;
            _nativeSurfaceState = 'stopped';
            _status = 'WiFi disconnected — waiting for network...';
          });
        }
      }
    });
  }

  void _startConnectionHintTimer() {
    if (_showConnectionHint) return; // already showing
    if (_connectionHintTimer?.isActive == true) return; // already counting down
    _connectionHintTimer = Timer(
      const Duration(seconds: _connectionHintDelaySec),
      () {
        if (mounted && !_isRtspMode) {
          setState(() => _showConnectionHint = true);
        }
      },
    );
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _watchdogTimer?.cancel();
    _connectivitySub?.cancel();
    _reconnectTimer?.cancel();
    _recordingTimer?.cancel();
    _connectionHintTimer?.cancel();
    _nativeChannel.setMethodCallHandler(null);
    if (_isRecording) {
      _nativeChannel.invokeMethod('stop_recording').catchError((_) {});
    }
    if (_nativeOverlayActive) {
      _nativeChannel.invokeMethod('overlay_stop').catchError((_) {});
      _nativeChannel.invokeMethod('overlay_dispose').catchError((_) {});
    }
    try { _cameraWs?.close(); } catch (_) {}
    super.dispose();
  }

  // ---- Camera discovery & connection ----------------------------------------

  Future<void> _probe({bool fastReconnect = false}) async {
    if (_connecting) return;
    _connecting = true;
    _probeStartTime = DateTime.now();
    final myProbeId = _probeId; // Snapshot — if this changes, we've been superseded
    debugPrint('[HAWKEYE] _probe() #$myProbeId starting, ip=$_ip, fast=$fastReconnect');

    // Start connection hint timer (shows WiFi notice after delay)
    _startConnectionHintTimer();

    // Dispose old overlay to force a fresh decoder — prevents pixelation
    // from stale decoder state after reconnect/sleep/WiFi restore
    if (_nativeOverlayActive) {
      try {
        await _nativeChannel.invokeMethod('overlay_stop');
        await _nativeChannel.invokeMethod('overlay_dispose');
      } catch (_) {}
      _nativeOverlayActive = false;
    }

    // Close previous WebSocket if any
    try { await _cameraWs?.close(); } catch (_) {}
    _cameraWs = null;

    // Brief delay after dispose to let camera release old RTSP session
    await Future.delayed(const Duration(milliseconds: 500));
    if (_probeId != myProbeId || !mounted) {
      if (_probeId == myProbeId) _connecting = false;
      return;
    }

    setState(() {
      _status = 'Connecting...';
      _isRtspMode = false;
    });

    try {
      // ============================================================
      // Protocol (from reverse-engineering VS borescope app):
      //   1. WebSocket to ws://camera:2023 (command/control channel)
      //   2. GET /live_streaming returns RTSP stream path ["/preview"]
      //   3. VLC plays rtsp://camera:554/preview (H.264 400x400 30fps)
      // ============================================================

      // STEP 1: Quick port check to find camera
      final portsToScan = [2023, 554, 80];
      String? foundIp;
      final openPorts = <int>[];

      if (fastReconnect) {
        try {
          final socket = await Socket.connect(_ip, 554,
              timeout: const Duration(milliseconds: 300));
          socket.destroy();
          foundIp = _ip;
          openPorts.addAll([2023, 554, 80]);
          debugPrint('[HAWKEYE] Fast reconnect: camera still at $_ip');
        } catch (_) {
          debugPrint('[HAWKEYE] Fast reconnect failed, falling back to full scan');
        }
      }

      if (_probeId != myProbeId) return; // Bail check

      if (foundIp == null) {
        for (final candidateIp in _ipCandidates) {
          setState(() => _status = 'Searching for camera...');
          for (final port in portsToScan) {
            try {
              final socket = await Socket.connect(candidateIp, port,
                  timeout: const Duration(milliseconds: 500));
              socket.destroy();
              debugPrint('[HAWKEYE] OPEN: $candidateIp:$port');
              openPorts.add(port);
              foundIp ??= candidateIp;
            } catch (_) {}
            if (!mounted || _probeId != myProbeId) return;
          }
          if (openPorts.isNotEmpty) break;
        }
      }

      if (foundIp == null) {
        setState(() => _status = 'No camera found. Check WiFi connection.');
        return;
      }

      setState(() => _ip = foundIp!);
      debugPrint('[HAWKEYE] Camera at $foundIp, ports: $openPorts');
      setState(() => _status = 'Camera found — connecting...');

      if (_probeId != myProbeId) return; // Bail check

      // STEP 2: Open WebSocket command channel (activates camera)
      if (openPorts.contains(2023)) {
        setState(() => _status = 'Connecting...');
        try {
          _cameraWs = await WebSocket.connect('ws://$foundIp:2023')
              .timeout(const Duration(seconds: 5));
          debugPrint('[HAWKEYE] WebSocket connected to ws://$foundIp:2023');

          _cameraWs!.listen(
            (data) => debugPrint('[HAWKEYE] WS: ${data.toString().substring(0, data.toString().length.clamp(0, 200))}'),
            onError: (e) => debugPrint('[HAWKEYE] WS error: $e'),
            onDone: () => debugPrint('[HAWKEYE] WS closed'),
          );
          await Future.delayed(const Duration(milliseconds: 500));
        } catch (e) {
          debugPrint('[HAWKEYE] WebSocket failed: $e');
        }
      }

      if (_probeId != myProbeId) return; // Bail check

      // STEP 3: Activate camera via HTTP API
      String streamPath = '/preview';
      if (openPorts.contains(80)) {
        setState(() => _status = 'Connecting...');
        try {
          final c = HttpClient()..connectionTimeout = const Duration(seconds: 3);
          final rq = await c.getUrl(Uri.parse('http://$foundIp/live_streaming'));
          final rs = await rq.close().timeout(const Duration(seconds: 3));
          final body = await rs.transform(utf8.decoder).join();
          debugPrint('[HAWKEYE] /live_streaming => ${rs.statusCode} "$body"');
          c.close(force: true);
          if (rs.statusCode == 200) {
            try {
              final decoded = jsonDecode(body);
              if (decoded is List && decoded.isNotEmpty) {
                streamPath = decoded[0].toString();
                debugPrint('[HAWKEYE] Stream path: $streamPath');
              }
            } catch (_) {}
          }
        } catch (e) {
          debugPrint('[HAWKEYE] API error: $e');
        }
        await Future.delayed(const Duration(milliseconds: 200));
      }

      if (_probeId != myProbeId) return; // Bail check

      // STEP 4: Play RTSP stream via native surface
      // Only try the primary URL — trying multiple URLs floods the camera's
      // single-client RTSP server and can cause permanent connection failures
      final rtspUrl = 'rtsp://$foundIp:554$streamPath';
      debugPrint('[HAWKEYE] Trying: $rtspUrl');
      setState(() => _status = 'Connecting...');
      final success = await _playRtspNativeSurface(rtspUrl, myProbeId);
      if (success) {
        final source = _wifiName ?? 'camera';
        setState(() => _status = 'Streaming from $source');
        return;
      }

      setState(() => _status = 'Connection failed — retrying...');
    } finally {
      // Only clear _connecting if this probe is still the current one —
      // a newer probe may have already taken over via lifecycle/watchdog
      if (_probeId == myProbeId) _connecting = false;
    }
  }

  Future<bool> _playRtspNativeSurface(String rtspUrl, int myProbeId) async {
    try {
      debugPrint('[HAWKEYE] Native Surface: $rtspUrl');
      setState(() => _nativeSurfaceState = 'connecting');

      // Init overlay only if not already active (reuse on reconnect)
      if (!_nativeOverlayActive) {
        await _nativeChannel.invokeMethod('overlay_init');
      }
      if (_probeId != myProbeId) return false; // Bail check

      await _nativeChannel.invokeMethod('overlay_play', {'url': rtspUrl});
      setState(() {
        _nativeOverlayActive = true;
        _status = 'Connecting...';
      });
      // Send insets so native sizes video with room for toolbar
      _lastInsetOrientation = null; // force re-send
      final orientation = MediaQuery.of(context).orientation;
      _sendInsets(orientation);

      // Wait for RTSP event (playing or error) — event handler updates _nativeSurfaceState
      for (int i = 0; i < 20; i++) { // 10 seconds max
        await Future.delayed(const Duration(milliseconds: 500));
        if (!mounted || _probeId != myProbeId) return false;
        if (_nativeSurfaceState == 'playing') {
          _lastRtspUrl = rtspUrl;
          return true;
        }
        if (_nativeSurfaceState == 'error') break;
      }

      debugPrint('[HAWKEYE] Did not reach playing state (state=$_nativeSurfaceState)');
      await _nativeChannel.invokeMethod('overlay_stop');
      setState(() => _nativeOverlayActive = false);
      return false;
    } catch (e) {
      debugPrint('[HAWKEYE] Native Surface error: $e');
      setState(() {
        _nativeOverlayActive = false;
        _status = 'Connection failed — retrying...';
      });
      return false;
    }
  }

  /// Send reserved insets to native so the video is sized to leave room for toolbar.
  void _sendInsets(Orientation orientation) {
    if (!_nativeOverlayActive) return;
    if (orientation == _lastInsetOrientation) return;
    _lastInsetOrientation = orientation;

    final dpr = WidgetsBinding.instance.platformDispatcher.views.first.devicePixelRatio;
    final reserve = (_toolbarReserve * dpr).round();

    final int left, top, right, bottom;
    if (orientation == Orientation.landscape) {
      // Reserve symmetric left/right for toolbar on right
      left = reserve;
      top = 0;
      right = reserve;
      bottom = 0;
    } else {
      // Reserve bottom for toolbar in portrait
      left = 0;
      top = 0;
      right = 0;
      bottom = reserve;
    }

    _nativeChannel.invokeMethod('overlay_setInsets', {
      'left': left, 'top': top, 'right': right, 'bottom': bottom,
    }).catchError((_) {});
    debugPrint('[HAWKEYE] Sent insets: L=$left T=$top R=$right B=$bottom (orientation=$orientation)');
  }

  // ---- Capture: photo + video recording -----------------------------------

  Future<void> _requestStoragePermissions() async {
    // API 33+ needs READ_MEDIA_IMAGES/VIDEO; API < 29 needs WRITE_EXTERNAL_STORAGE
    // permission_handler maps these automatically based on API level
    final statuses = await [
      Permission.photos,
      Permission.videos,
      Permission.storage,
    ].request();
    debugPrint('[HAWKEYE] Storage permissions: $statuses');
  }

  Future<void> _capturePhoto() async {
    if (!_isRtspMode || _lastRtspUrl == null) return;
    await _ensureWifiNameForCapture();
    await _requestStoragePermissions();
    final wifiName = _wifiName ?? 'Camera';
    try {
      await _nativeChannel.invokeMethod('capture_photo', {'wifiName': wifiName});
      debugPrint('[HAWKEYE] Photo capture requested');
    } catch (e) {
      debugPrint('[HAWKEYE] Photo capture error: $e');
    }
  }

  Future<void> _toggleRecording() async {
    if (_isRecording) {
      // Stop recording
      try {
        await _nativeChannel.invokeMethod('stop_recording');
        debugPrint('[HAWKEYE] Stop recording requested');
      } catch (e) {
        debugPrint('[HAWKEYE] Stop recording error: $e');
      }
    } else {
      // Start recording
      if (!_isRtspMode || _lastRtspUrl == null) return;
      await _ensureWifiNameForCapture();
      await _requestStoragePermissions();
      final wifiName = _wifiName ?? 'Camera';
      try {
        await _nativeChannel.invokeMethod('start_recording', {
          'url': _lastRtspUrl,
          'wifiName': wifiName,
          'width': _videoWidth > 0 ? _videoWidth : 400,
          'height': _videoHeight > 0 ? _videoHeight : 400,
        });
        setState(() {
          _isRecording = true;
          _recordingDuration = Duration.zero;
        });
        _recordingTimer = Timer.periodic(const Duration(seconds: 1), (_) {
          if (mounted) {
            setState(() => _recordingDuration += const Duration(seconds: 1));
          }
        });
        debugPrint('[HAWKEYE] Start recording requested: $_lastRtspUrl');
      } catch (e) {
        debugPrint('[HAWKEYE] Start recording error: $e');
      }
    }
  }

  String _formatDuration(Duration d) {
    final minutes = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final seconds = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    if (d.inHours > 0) {
      return '${d.inHours}:$minutes:$seconds';
    }
    return '$minutes:$seconds';
  }

  // ---- UI ----------------------------------------------------------------

  void _onWifiButtonTap() async {
    await _ensureWifiNameForCapture();
    if (!mounted) return;
    final message = _wifiName != null
        ? 'Connected to $_wifiName'
        : 'Not connected';
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        duration: const Duration(seconds: 2),
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final orientation = MediaQuery.of(context).orientation;
    // Send insets to native when orientation changes
    if (_nativeOverlayActive) {
      _sendInsets(orientation);
    }

    final isLandscape = orientation == Orientation.landscape;

    return Scaffold(
      backgroundColor: Colors.transparent,
      body: Stack(
        fit: StackFit.expand,
        children: [
          // Loading overlay — covers screen until native surface stream starts
          if (!_isRtspMode)
            Container(
              color: const Color(0xFE000000),
              child: Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const CircularProgressIndicator(),
                    if (_showConnectionHint) ...[
                      const SizedBox(height: 24),
                      const Padding(
                        padding: EdgeInsets.symmetric(horizontal: 32),
                        child: Text(
                          'Unable to connect to camera.\n\n'
                          'Please check that you are connected\n'
                          'to the Q2 borescope WiFi network.',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            color: Colors.white70,
                            fontSize: 14,
                          ),
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ),
          // Photo flash feedback
          if (_photoFlash)
            Container(color: const Color(0xBBFFFFFF)),
          // Toolbar (visible when streaming)
          if (_isRtspMode)
            isLandscape ? _buildLandscapeToolbar() : _buildPortraitToolbar(),
        ],
      ),
    );
  }

  /// Landscape: vertical column on the right, centered vertically.
  Widget _buildLandscapeToolbar() {
    return Positioned(
      right: 8,
      top: 0,
      bottom: 0,
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            _buildToolbarButton(
              icon: Icons.wifi,
              color: Colors.green,
              onPressed: _onWifiButtonTap,
            ),
            const SizedBox(height: 12),
            _buildToolbarButton(
              icon: Icons.camera_alt,
              color: Colors.black,
              onPressed: _capturePhoto,
            ),
            const SizedBox(height: 12),
            _buildToolbarButton(
              icon: _isRecording ? Icons.stop : Icons.videocam,
              color: _isRecording ? Colors.white : const Color(0xFFFF0000),
              background: _isRecording ? const Color(0x44FF0000) : null,
              onPressed: _toggleRecording,
            ),
            if (_isRecording) ...[
              const SizedBox(height: 4),
              Text(
                _formatDuration(_recordingDuration),
                style: const TextStyle(
                  color: Color(0xFFFF4444),
                  fontSize: 11,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  /// Portrait: horizontal row at the bottom, centered horizontally.
  Widget _buildPortraitToolbar() {
    return Positioned(
      left: 0,
      right: 0,
      bottom: 8,
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                _buildToolbarButton(
                  icon: Icons.wifi,
                  color: Colors.green,
                  onPressed: _onWifiButtonTap,
                ),
                const SizedBox(width: 12),
                _buildToolbarButton(
                  icon: Icons.camera_alt,
                  color: Colors.black,
                  onPressed: _capturePhoto,
                ),
                const SizedBox(width: 12),
                _buildToolbarButton(
                  icon: _isRecording ? Icons.stop : Icons.videocam,
                  color: _isRecording ? Colors.white : const Color(0xFFFF0000),
                  background: _isRecording ? const Color(0x44FF0000) : null,
                  onPressed: _toggleRecording,
                ),
              ],
            ),
            if (_isRecording) ...[
              const SizedBox(height: 4),
              Text(
                _formatDuration(_recordingDuration),
                style: const TextStyle(
                  color: Color(0xFFFF4444),
                  fontSize: 11,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildToolbarButton({
    required IconData icon,
    required Color color,
    required VoidCallback onPressed,
    Color? background,
  }) {
    return Container(
      decoration: BoxDecoration(
        color: background ?? const Color(0xFF555555),
        borderRadius: BorderRadius.circular(8),
      ),
      child: IconButton(
        onPressed: onPressed,
        icon: Icon(icon, color: color, size: 28),
        padding: const EdgeInsets.all(10),
        constraints: const BoxConstraints(),
      ),
    );
  }
}
