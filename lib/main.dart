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

class _LiveViewState extends State<LiveView> {
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

  // Camera command channel (WebSocket on port 2023)
  WebSocket? _cameraWs;

  // Last successful RTSP URL — used for fast replay on stream loss
  String? _lastRtspUrl;

  // UI/state
  bool _connecting = false;
  bool _isRtspMode = false;
  String _status = 'Idle';

  // WiFi name
  String? _wifiName;

  // WiFi connectivity monitoring
  StreamSubscription<List<ConnectivityResult>>? _connectivitySub;
  bool _hasWifi = false;

  // Video aspect ratio — auto-detected from stream
  double _videoAspectRatio = 1.0;

  @override
  void initState() {
    super.initState();
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    SystemChrome.setPreferredOrientations([
      DeviceOrientation.portraitUp,
      DeviceOrientation.portraitDown,
      DeviceOrientation.landscapeLeft,
      DeviceOrientation.landscapeRight,
    ]);
    _nativeChannel.setMethodCallHandler(_onNativeMethodCall);
    _initConnectivityMonitor();
    _detectWifiName();
    _probe();
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
          if (mounted) {
            setState(() {
              _nativeSurfaceState = 'playing';
              _isRtspMode = true;
              final source = _wifiName ?? 'camera';
              _status = 'Streaming from $source';
            });
          }
          break;

        case 'videoSize':
          final w = args['width'] as int;
          final h = args['height'] as int;
          if (w > 0 && h > 0 && mounted) {
            setState(() => _videoAspectRatio = w / h);
            debugPrint('[HAWKEYE] Video size: ${w}x$h ratio=${_videoAspectRatio.toStringAsFixed(2)}');
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
      setState(() => _status = 'Reconnect failed after $_maxReconnectAttempts attempts.');
      _reconnectAttempt = 0;
      _lastRtspUrl = null;
      return;
    }

    // Fast replay: retry same URL without re-probing (first 6 attempts, 500ms interval)
    // Then fall back to full probe with exponential backoff
    final useFastReplay = _lastRtspUrl != null && _reconnectAttempt < 6;
    final delayMs = useFastReplay
        ? 500
        : (300 * (1 << (_reconnectAttempt - 6).clamp(0, 4))).clamp(300, 5000);
    _reconnectAttempt++;

    debugPrint('[HAWKEYE] Reconnect attempt $_reconnectAttempt in ${delayMs}ms (fast=$useFastReplay)');
    setState(() => _status = 'Reconnecting (attempt $_reconnectAttempt)...');

    _reconnectTimer = Timer(Duration(milliseconds: delayMs), () {
      if (mounted && !_connecting) {
        if (useFastReplay) {
          _fastReplay();
        } else {
          _nativeChannel.invokeMethod('overlay_stop').catchError((_) {});
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

    try {
      debugPrint('[HAWKEYE] Fast replay: $_lastRtspUrl');
      setState(() => _status = 'Reconnecting...');
      await _nativeChannel.invokeMethod('overlay_stop').catchError((_) {});
      await _nativeChannel.invokeMethod('overlay_play', {'url': _lastRtspUrl});
      setState(() => _nativeSurfaceState = 'connecting');

      // Wait up to 4 seconds for playing state
      for (int i = 0; i < 8; i++) {
        await Future.delayed(const Duration(milliseconds: 500));
        if (!mounted) return;
        if (_nativeSurfaceState == 'playing') return; // Success!
        if (_nativeSurfaceState == 'error') break; // Fail fast
      }

      // Failed — schedule next attempt
      debugPrint('[HAWKEYE] Fast replay failed, scheduling next attempt');
      _scheduleNativeReconnect();
    } finally {
      _connecting = false;
    }
  }

  Future<void> _detectWifiName() async {
    try {
      final status = await Permission.location.request();
      if (status.isGranted) {
        final info = NetworkInfo();
        final name = await info.getWifiName();
        if (name != null && mounted) {
          setState(() => _wifiName = name.replaceAll('"', ''));
          debugPrint('[HAWKEYE] WiFi SSID: $_wifiName');
        }
      } else {
        debugPrint('[HAWKEYE] Location permission denied — cannot get WiFi name');
      }
    } catch (e) {
      debugPrint('[HAWKEYE] WiFi name detection failed: $e');
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

  @override
  void dispose() {
    _connectivitySub?.cancel();
    _reconnectTimer?.cancel();
    _nativeChannel.setMethodCallHandler(null);
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
    debugPrint('[HAWKEYE] _probe() starting, ip=$_ip, fast=$fastReconnect');

    // Close previous WebSocket if any
    try { await _cameraWs?.close(); } catch (_) {}
    _cameraWs = null;

    setState(() {
      _status = fastReconnect ? 'Reconnecting...' : 'Discovering camera...';
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

      if (foundIp == null) {
        for (final candidateIp in _ipCandidates) {
          setState(() => _status = 'Scanning $candidateIp ...');
          for (final port in portsToScan) {
            try {
              final socket = await Socket.connect(candidateIp, port,
                  timeout: const Duration(milliseconds: 500));
              socket.destroy();
              debugPrint('[HAWKEYE] OPEN: $candidateIp:$port');
              openPorts.add(port);
              foundIp ??= candidateIp;
            } catch (_) {}
            if (!mounted) return;
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
      setState(() => _status = 'Found camera at $foundIp');

      // STEP 2: Open WebSocket command channel (activates camera)
      if (openPorts.contains(2023)) {
        setState(() => _status = 'Connecting to camera...');
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

      // STEP 3: Activate camera via HTTP API
      String streamPath = '/preview';
      if (openPorts.contains(80)) {
        setState(() => _status = 'Activating camera...');
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

      // STEP 4: Play RTSP stream via native surface
      final rtspUrls = [
        'rtsp://$foundIp:554$streamPath',
        'rtsp://$foundIp:554/preview',
        'rtsp://$foundIp:554/',
        'rtsp://$foundIp:554/live',
      ];

      for (final url in rtspUrls) {
        if (!mounted) return;
        debugPrint('[HAWKEYE] Trying: $url');
        setState(() => _status = 'Connecting: $url');
        final success = await _playRtspNativeSurface(url);
        if (success) {
          final source = _wifiName ?? 'camera';
          setState(() => _status = 'Streaming from $source');
          return;
        }
      }

      setState(() => _status = 'Camera found but stream failed. WS=${_cameraWs != null ? "OK" : "fail"}');
    } finally {
      _connecting = false;
    }
  }

  Future<bool> _playRtspNativeSurface(String rtspUrl) async {
    try {
      debugPrint('[HAWKEYE] Native Surface: $rtspUrl');
      setState(() => _nativeSurfaceState = 'connecting');

      // Init overlay only if not already active (reuse on reconnect)
      if (!_nativeOverlayActive) {
        await _nativeChannel.invokeMethod('overlay_init');
      }
      await _nativeChannel.invokeMethod('overlay_play', {'url': rtspUrl});
      setState(() {
        _nativeOverlayActive = true;
        _status = 'Connecting: $rtspUrl';
      });

      // Wait for VLC event (playing or error) — event handler updates _nativeSurfaceState
      for (int i = 0; i < 20; i++) { // 10 seconds max
        await Future.delayed(const Duration(milliseconds: 500));
        if (!mounted) return false;
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
        _status = 'Connection failed: $e';
      });
      return false;
    }
  }

  // ---- UI ----------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.transparent,
      body: Stack(
        fit: StackFit.expand,
        children: [
          // Loading overlay — covers screen until native surface stream starts
          if (!_isRtspMode)
            Container(
              color: const Color(0xFE000000),
              child: const Center(child: CircularProgressIndicator()),
            ),
          // Status overlay at bottom
          Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              decoration: const BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.bottomCenter,
                  end: Alignment.topCenter,
                  colors: [Color(0xCC000000), Color(0x00000000)],
                ),
              ),
              child: Text(
                _status,
                style: const TextStyle(
                  color: Colors.white70,
                  fontSize: 13,
                ),
                textAlign: TextAlign.center,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
