import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:flutter_vlc_player/flutter_vlc_player.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Hawkeye WiFi Viewer',
      theme: ThemeData.dark().copyWith(
        scaffoldBackgroundColor: const Color(0xFF0f0f10), // App background from HawkeyeViewerPlus
        colorScheme: ColorScheme.dark(
          primary: const Color(0xFFe74c3c), // Hawkeye red accent
          secondary: const Color(0xFFe74c3c),
          surface: const Color(0xFF1b1b1b), // Header/card background
          brightness: Brightness.dark,
        ),
        appBarTheme: const AppBarTheme(
          backgroundColor: Color(0xFF1b1b1b), // Dark gray header
          elevation: 0,
        ),
        elevatedButtonTheme: ElevatedButtonThemeData(
          style: ElevatedButton.styleFrom(
            backgroundColor: const Color(0xFFe74c3c), // Hawkeye red
            foregroundColor: Colors.white,
          ),
        ),
        outlinedButtonTheme: OutlinedButtonThemeData(
          style: OutlinedButton.styleFrom(
            foregroundColor: const Color(0xFFe74c3c), // Hawkeye red
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
  String _ip = '192.168.42.1'; // Camera WiFi network gateway

  // Common IP addresses for WiFi endoscopes
  final List<String> _ipCandidates = const [
    '192.168.42.1',   // Camera WiFi gateway (confirmed subnet)
    '192.168.42.254', // Alternative on confirmed subnet
    '192.168.42.100', // Alternative on confirmed subnet
    '192.168.10.123', // Standard Jieli endoscope
    '192.168.1.1',    // Alternative
    '192.168.0.1',    // Alternative
    '192.168.4.1',    // Alternative
  ];

  // TCP ports for WiFi endoscopes
  final List<int> _tcpPorts = const [
    80,   // Standard HTTP port (FOUND OPEN!)
    7060, // Most common MJPEG port
    8080, // HTTP alternative
    7070, // RTSP alternative
    554,  // RTSP standard port (FOUND OPEN!)
    81,   // Alternative HTTP
    8000, // Alternative streaming
    8888, // Alternative streaming
  ];

  // Common HTTP MJPEG endpoints (fallback)
  final List<String> _jpegCandidates = const [
    '/',              // Root path - very common for borescopes!
    '/stream',
    '/video',
    '/mjpeg',
    '/preview',
    '/video.cgi',     // Common for cheap WiFi cameras
    '/videostream.cgi',
    '/video.mjpg',
    '/stream.mjpg',
    '/?action=stream',
    '/mjpeg/1',
    '/snapshot.jpg',
    '/jpg',
  ];
  int _candidateIndex = 0;

  // Stream plumbing
  HttpClient? _client;
  Socket? _socket;
  StreamSubscription<List<int>>? _sub;
  BytesBuilder _buffer = BytesBuilder(copy: false);
  String? _boundary; // multipart boundary (parsed from header)
  bool _connecting = false;
  bool _running = false;
  bool _useTcp = false; // Track if using TCP vs HTTP

  // Camera command channel (WebSocket on port 2023)
  WebSocket? _cameraWs;

  // UI/state
  Uint8List? _lastFrame;
  String _status = 'Idle';
  String? _activeUrl;
  Timer? _watchdog; // if no frames for some time, restart
  int _frameCount = 0;
  DateTime? _lastFrameTime;

  // RTSP video player (VLC) — ONE persistent controller
  late VlcPlayerController _vlcController;
  bool _vlcReady = false; // true once platform view is initialized
  bool _isRtspMode = false;

  @override
  void initState() {
    super.initState();
    // Create the ONE VLC controller that lives for the app's lifetime.
    // Start with a dummy URL; we'll use setMediaFromNetwork() to switch.
    _vlcController = VlcPlayerController.network(
      'http://127.0.0.1/', // placeholder — will be replaced
      hwAcc: HwAcc.full,
      autoPlay: false,
      options: VlcPlayerOptions(
        advanced: VlcAdvancedOptions([
          VlcAdvancedOptions.networkCaching(500),
        ]),
        extras: [
          '--http-reconnect',         // Auto-reconnect on drops (key for borescopes)
          '--no-sub-autodetect-file',  // Don't waste time looking for subtitles
          '--no-stats',               // Less overhead
          '--drop-late-frames',       // Keep in sync
          '--skip-frames',            // Keep in sync
          '-vvv',                     // Maximum verbose logging
        ],
        rtp: VlcRtpOptions([
          VlcRtpOptions.rtpOverRtsp(true),
        ]),
      ),
    );
    _vlcController.addOnInitListener(() {
      debugPrint('[HAWKEYE] VLC platform view initialized');
      if (mounted) {
        setState(() => _vlcReady = true);
        // Auto-probe once VLC is ready
        if (!_connecting) _probe();
      }
    });

    // Also set up a fallback timer: if VLC hasn't initialized after 8s,
    // probe anyway (in case VLC init is permanently stuck).
    WidgetsBinding.instance.addPostFrameCallback((_) {
      Future.delayed(const Duration(seconds: 8), () {
        if (mounted && !_vlcReady && !_connecting) {
          debugPrint('[HAWKEYE] VLC init timeout - probing anyway');
          _probe();
        }
      });
    });
  }

  @override
  void dispose() {
    _stopStream();
    _vlcController.dispose();
    super.dispose();
  }

  // ---- UI actions -----------------------------------------------------------

  Future<void> _setIpPrompt() async {
    final ctrl = TextEditingController(text: _ip);
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('Set Camera IP'),
        content: TextField(
          controller: ctrl,
          keyboardType: TextInputType.url,
          decoration: const InputDecoration(
            labelText: 'IP only (e.g., 192.168.42.1)',
            hintText: 'Do NOT include http://',
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Cancel')),
          ElevatedButton(onPressed: () => Navigator.pop(c, true), child: const Text('Save')),
        ],
      ),
    );
    if (ok == true) {
      final ip = ctrl.text.trim();
      // Sanitize “http://x” -> “x”
      final cleaned = ip.replaceAll(RegExp(r'^https?://'), '').replaceAll('/', '');
      setState(() => _ip = cleaned);
      await _probe();
    }
  }

  Future<void> _openWebUi() async {
    final url = Uri.parse('http://$_ip/');
    await launchUrl(url, mode: LaunchMode.externalApplication);
  }

  Future<void> _tryRtsp() async {
    // Try common RTSP URLs for borescopes on both port 554 and port 80
    final rtspUrls = [
      // Port 554 (standard RTSP port - found open!)
      'rtsp://$_ip:554/',
      'rtsp://$_ip:554/stream',
      'rtsp://$_ip:554/video',
      'rtsp://$_ip:554/live',
      'rtsp://$_ip:554/h264',
      'rtsp://$_ip:554/webcam',
      // Port 80 (also found open - some devices serve RTSP here)
      'rtsp://$_ip:80/',
      'rtsp://$_ip:80/stream',
      'rtsp://$_ip:80/video',
      'rtsp://$_ip:80/h264',
    ];

    int totalTests = rtspUrls.length;
    for (int i = 0; i < rtspUrls.length; i++) {
      final rtspUrl = rtspUrls[i];
      setState(() => _status = 'RTSP ${i + 1}/$totalTests: $rtspUrl');
      final success = await _playRtspStream(rtspUrl);
      if (success) {
        return;
      }
      await Future.delayed(const Duration(milliseconds: 1000));
    }

    setState(() => _status = 'RTSP test complete - no stream found. All tests failed.');
  }

  Future<bool> _playRtspStream(String rtspUrl) async {
    try {
      // Stop any existing non-VLC streams
      _running = false;
      _watchdog?.cancel();
      try { await _sub?.cancel(); } catch (_) {}
      _sub = null;
      try { _socket?.close(); } catch (_) {}
      _socket = null;
      try { _client?.close(force: true); } catch (_) {}
      _client = null;
      _buffer = BytesBuilder(copy: false);
      _boundary = null;

      debugPrint('[HAWKEYE] Opening via VLC (persistent): $rtspUrl');
      debugPrint('[HAWKEYE] VLC ready=$_vlcReady, current state=${_vlcController.value.playingState}');

      // Wait for VLC platform view to be ready (up to 5 seconds)
      if (!_vlcReady) {
        debugPrint('[HAWKEYE] Waiting for VLC platform view to initialize...');
        for (int w = 0; w < 10; w++) {
          await Future.delayed(const Duration(milliseconds: 500));
          if (_vlcReady) break;
          if (!mounted) return false;
        }
        if (!_vlcReady) {
          debugPrint('[HAWKEYE] VLC platform view never initialized!');
          return false;
        }
      }

      // Stop any currently playing media
      try { await _vlcController.stop(); } catch (_) {}

      // Set the new media URL and play
      await _vlcController.setMediaFromNetwork(
        rtspUrl,
        hwAcc: HwAcc.full,
        autoPlay: true,
      );

      setState(() {
        _isRtspMode = true;
        _lastFrame = null;
        _activeUrl = rtspUrl;
        _status = 'VLC connecting: $rtspUrl';
      });

      // Poll for up to 6 seconds to see if VLC starts playing
      for (int i = 0; i < 12; i++) {
        await Future.delayed(const Duration(milliseconds: 500));
        final state = _vlcController.value.playingState;
        final size = _vlcController.value.size;
        debugPrint('[HAWKEYE] VLC playing state after ${(i+1)*0.5}s: $state size=$size');
        if (state == PlayingState.playing || state == PlayingState.buffering) {
          setState(() => _status = 'VLC streaming: $rtspUrl');
          return true;
        }
        if (state == PlayingState.error || state == PlayingState.stopped) {
          debugPrint('[HAWKEYE] VLC error/stopped for $rtspUrl');
          break;
        }
        if (!mounted) return false;
      }

      debugPrint('[HAWKEYE] VLC did not start playing for $rtspUrl');
      return false;
    } catch (e) {
      debugPrint('[HAWKEYE] VLC exception: $e');
      setState(() => _status = 'VLC failed: $e');
      _isRtspMode = false;
      return false;
    }
  }

  Future<void> _switchUrl() async {
    // advance one candidate and try it
    _candidateIndex = (_candidateIndex + 1) % _jpegCandidates.length;
    final path = _jpegCandidates[_candidateIndex];
    final url = 'http://$_ip$path';
    setState(() {
      _status = 'Trying path ${_candidateIndex + 1}/${_jpegCandidates.length}: $url';
    });
    await _startMjpeg(url);
  }

  Future<void> _scanPorts() async {
    setState(() => _status = 'Scanning ports on $_ip...');
    final openPorts = <int>[];

    // Common ports to scan
    final portsToScan = [80, 81, 554, 7060, 7070, 8000, 8080, 8081, 8888, 9000];

    for (final port in portsToScan) {
      try {
        final socket = await Socket.connect(_ip, port, timeout: const Duration(milliseconds: 500));
        socket.close();
        openPorts.add(port);
        setState(() => _status = 'Found open port: $port');
        await Future.delayed(const Duration(milliseconds: 100));
      } catch (_) {
        // Port closed or unreachable
      }
    }

    if (openPorts.isEmpty) {
      setState(() => _status = 'No open ports found on $_ip');
    } else {
      setState(() => _status = 'Open ports: ${openPorts.join(", ")}');
    }
  }

  Future<void> _probe() async {
    if (_connecting) return;
    _connecting = true;
    debugPrint('[HAWKEYE] _probe() starting, ip=$_ip');

    // Stop any existing streams
    _running = false;
    _watchdog?.cancel();
    try { await _sub?.cancel(); } catch (_) {}
    _sub = null;
    try { _socket?.close(); } catch (_) {}
    _socket = null;
    try { _client?.close(force: true); } catch (_) {}
    _client = null;
    try { await _cameraWs?.close(); } catch (_) {}
    _cameraWs = null;
    setState(() {
      _status = 'Discovering camera...';
      _lastFrame = null;
      _frameCount = 0;
      _lastFrameTime = null;
      _isRtspMode = false;
    });

    try {
      // ============================================================
      // Confirmed protocol (from reverse-engineering VS borescope app):
      //   1. WebSocket to ws://camera:2023 (command/control channel)
      //   2. GET /live_streaming returns RTSP stream path ["/preview"]
      //   3. VLC plays rtsp://camera:554/preview (H.264 400x400 30fps)
      // ============================================================

      // Brief wait for VLC if it's not ready yet
      if (!_vlcReady) {
        setState(() => _status = 'Waiting for VLC...');
        for (int w = 0; w < 8; w++) {
          await Future.delayed(const Duration(milliseconds: 250));
          if (_vlcReady) break;
        }
        debugPrint('[HAWKEYE] VLC ready=$_vlcReady');
      }

      // STEP 1: Quick port check to find camera
      final portsToScan = [2023, 554, 80];
      String? foundIp;
      final openPorts = <int>[];

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
      String streamPath = '/preview'; // default
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

      // STEP 4: Play RTSP stream via VLC
      if (!_vlcReady) {
        setState(() => _status = 'VLC not initialized. Tap Auto-Detect to retry.');
        return;
      }

      // Try RTSP URLs in order of likelihood
      final rtspUrls = [
        'rtsp://$foundIp:554$streamPath',
        'rtsp://$foundIp:554/preview',
        'rtsp://$foundIp:554/',
        'rtsp://$foundIp:554/live',
      ];

      for (final url in rtspUrls) {
        if (!mounted) return;
        debugPrint('[HAWKEYE] Trying VLC: $url');
        setState(() => _status = 'Connecting: $url');
        final success = await _playRtspStream(url);
        if (success) {
          setState(() => _status = 'Streaming from camera');
          return;
        }
      }

      setState(() => _status = 'Camera found but stream failed. WS=${_cameraWs != null ? "OK" : "fail"}');
    } finally {
      _connecting = false;
    }
  }

  // ---- Networking / MJPEG parse --------------------------------------------

  Future<void> _startMjpeg(String url) async {
    await _stopStream();
    setState(() {
      _connecting = true;
      _running = true;
      _activeUrl = url;
      _status = 'Connecting to MJPEG stream @ $url';
      _lastFrame = null;
      _frameCount = 0;
      _lastFrameTime = null;
    });

    _client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 5)
      ..badCertificateCallback = (a, b, c) => true;

    try {
      final uri = Uri.parse(url);
      final req = await _client!.getUrl(uri);
      req.headers.set(HttpHeaders.connectionHeader, 'keep-alive');
      final resp = await req.close();

      final ct = (resp.headers.value(HttpHeaders.contentTypeHeader) ?? '').toLowerCase();
      _boundary = _extractBoundary(ct);

      setState(() {
        _status = 'JPEG streaming @ $url (boundary: ${_boundary ?? 'unknown'})';
      });

      // Watchdog: if no frames for a while, restart stream.
      _watchdog?.cancel();
      _watchdog = Timer.periodic(const Duration(seconds: 8), (_) {
        if (!_running) return;
        final now = DateTime.now();
        final stale = _lastFrameTime == null ||
                      now.difference(_lastFrameTime!).inSeconds > 10;
        if (stale) {
          // Try restart same URL
          setState(() => _status = 'Stream stalled, reconnecting...');
          _startMjpeg(url);
        }
      });

      _sub = resp.listen(
        _onData,
        onError: (e, st) {
          if (!mounted) return;
          setState(() => _status = 'Stream error: $e');
        },
        onDone: () {
          if (!mounted) return;
          setState(() => _status = 'Stream closed');
        },
        cancelOnError: true,
      );
    } catch (e) {
      setState(() => _status = 'Connect failed: $e');
    } finally {
      setState(() => _connecting = false);
    }
  }

  Future<void> _startTcpStream(String ip, int port) async {
    await _stopStream();
    setState(() {
      _connecting = true;
      _running = true;
      _useTcp = true;
      _activeUrl = 'tcp://$ip:$port';
      _status = 'Connecting to TCP stream @ $ip:$port';
      _lastFrame = null;
      _frameCount = 0;
      _lastFrameTime = null;
    });

    try {
      _socket = await Socket.connect(ip, port, timeout: const Duration(seconds: 5));

      setState(() {
        _status = 'TCP streaming @ $ip:$port';
      });

      // Watchdog: if no frames for a while, restart stream.
      _watchdog?.cancel();
      _watchdog = Timer.periodic(const Duration(seconds: 8), (_) {
        if (!_running) return;
        final now = DateTime.now();
        final stale = _lastFrameTime == null ||
                      now.difference(_lastFrameTime!).inSeconds > 10;
        if (stale) {
          setState(() => _status = 'Stream stalled, reconnecting...');
          _startTcpStream(ip, port);
        }
      });

      _sub = _socket!.listen(
        _onData,
        onError: (e, st) {
          if (!mounted) return;
          setState(() => _status = 'TCP error: $e');
        },
        onDone: () {
          if (!mounted) return;
          setState(() => _status = 'TCP stream closed');
        },
        cancelOnError: true,
      );
    } catch (e) {
      setState(() => _status = 'TCP connect failed: $e');
    } finally {
      setState(() => _connecting = false);
    }
  }

  Future<void> _stopStream() async {
    _running = false;
    _useTcp = false;
    _isRtspMode = false;
    _watchdog?.cancel();
    _watchdog = null;
    try {
      await _sub?.cancel();
    } catch (_) {}
    _sub = null;
    try {
      _socket?.close();
    } catch (_) {}
    _socket = null;
    try {
      _client?.close(force: true);
    } catch (_) {}
    _client = null;
    // Stop VLC playback but do NOT dispose — controller is persistent
    try {
      await _vlcController.stop();
    } catch (_) {}
    // Close WebSocket command channel
    try {
      await _cameraWs?.close();
    } catch (_) {}
    _cameraWs = null;
    _buffer = BytesBuilder(copy: false);
    _boundary = null;
  }

  // Parse MJPEG chunks out of the byte stream.
  void _onData(List<int> chunk) {
    if (!_running) return;
    if (_frameCount < 3) {
      debugPrint('[HAWKEYE] _onData: ${chunk.length} bytes, first=${chunk.length > 0 ? "0x${chunk[0].toRadixString(16)}" : "empty"}');
    }
    _buffer.add(chunk);

    // Try multiple parse strategies:
    // 1. BoundaryS/BoundaryE format (common cheap borescopes)
    if (_parseBoundarySE()) return;
    // 2. Standard multipart boundary
    if (_boundary != null) {
      _parseMultipartBoundaryAware(_boundary!);
      return;
    }
    // 3. Raw JPEG SOI/EOI markers
    _parseByJpegMarkers();
  }

  /// Parse BoundaryS ... JPEG ... BoundaryE format
  /// Format: "BoundaryS" + header bytes + JPEG (FFD8...FFD9) + "BoundaryE"
  bool _parseBoundarySE() {
    final data = _buffer.toBytes();
    final boundaryS = ascii.encode('BoundaryS');
    final idx = _indexOfSubsequence(data, boundaryS, 0);
    if (idx < 0) return false;

    // Found BoundaryS — look for JPEG SOI after it
    // Skip BoundaryS + header (typically 36 bytes after "BoundaryS")
    final afterBoundary = idx + boundaryS.length;
    // Find SOI (FFD8) which starts the actual JPEG
    final soi = _indexOfJpegSoi(data, from: afterBoundary);
    if (soi < 0) return false;
    // Find EOI (FFD9) after SOI
    final eoi = _indexOfJpegEoi(data, from: soi + 2);
    if (eoi < 0) return false;

    final jpgData = Uint8List.sublistView(data, soi, eoi + 2);

    if (_frameCount < 3) {
      debugPrint('[HAWKEYE] BoundaryS frame: header=${soi - afterBoundary}B, jpeg=${jpgData.length}B');
    }

    _emitFrame(jpgData);

    // Keep remainder after BoundaryE (or after EOI if no BoundaryE found)
    final boundaryE = ascii.encode('BoundaryE');
    final endIdx = _indexOfSubsequence(data, boundaryE, eoi + 2);
    final cutAt = endIdx >= 0 ? endIdx + boundaryE.length : eoi + 2;
    final remainder = data.sublist(cutAt);
    _buffer = BytesBuilder(copy: false)..add(remainder);
    return true;
  }

  void _parseMultipartBoundaryAware(String boundary) {
    // We’ll search for: --boundary\r\n ... headers ... \r\n\r\n <JPEG bytes> ... next boundary
    final marker = ascii.encode('--$boundary');
    final data = _buffer.toBytes(); // (copy) fine at moderate framerate
    int idx = _indexOfSubsequence(data, marker, 0);
    if (idx < 0) return;

    // Look for header end
    final hdrStart = idx + marker.length;
    final dblCrlf = ascii.encode('\r\n\r\n');
    final hdrEnd = _indexOfSubsequence(data, dblCrlf, hdrStart);
    if (hdrEnd < 0) return;

    final headersBytes = data.sublist(hdrStart, hdrEnd);
    final headersText = ascii.decode(headersBytes, allowInvalid: true);
    final len = _contentLengthFromHeaders(headersText);

    final payloadStart = hdrEnd + dblCrlf.length;

    if (len != null) {
      final need = payloadStart + len;
      if (data.length < need) return; // wait for more
      final jpg = Uint8List.sublistView(data, payloadStart, need);
      _emitFrame(jpg);

      // Truncate buffer to remainder after this frame.
      final remainder = data.sublist(need);
      _buffer = BytesBuilder(copy: false)..add(remainder);
      return;
    } else {
      // No Content-Length -> find JPEG EOI (FFD9)
      final eoi = _indexOfJpegEoi(data, from: payloadStart);
      if (eoi < 0) return;
      final jpg = Uint8List.sublistView(data, payloadStart, eoi + 2);
      _emitFrame(jpg);

      final remainder = data.sublist(eoi + 2);
      _buffer = BytesBuilder(copy: false)..add(remainder);
      return;
    }
  }

  void _parseByJpegMarkers() {
    // Fallback: find SOI FFD8 then EOI FFD9
    final data = _buffer.toBytes();
    final soi = _indexOfJpegSoi(data, from: 0);
    if (soi < 0) return;
    final eoi = _indexOfJpegEoi(data, from: soi + 2);
    if (eoi < 0) return;
    final jpg = Uint8List.sublistView(data, soi, eoi + 2);
    _emitFrame(jpg);

    final remainder = data.sublist(eoi + 2);
    _buffer = BytesBuilder(copy: false)..add(remainder);
  }

  void _emitFrame(Uint8List jpg) {
    if (!mounted) return;
    setState(() {
      _lastFrame = jpg;
      _frameCount++;
      _lastFrameTime = DateTime.now();
      _status = 'Live @ ${_activeUrl ?? ''} | Frame #$_frameCount (${jpg.lengthInBytes} bytes)';
    });
  }

  // ---- Helpers --------------------------------------------------------------

  String? _extractBoundary(String contentType) {
    // e.g. "multipart/x-mixed-replace; boundary=--myboundary"
    if (!contentType.contains('multipart/x-mixed-replace')) return null;
    final parts = contentType.split(';');
    for (final p in parts) {
      final t = p.trim().toLowerCase();
      if (t.startsWith('boundary=')) {
        var b = p.split('=').last.trim();
        b = b.replaceAll('"', '');
        // Strip leading "--" if present
        b = b.replaceAll(RegExp(r'^--'), '');
        return b;
      }
    }
    return null;
  }

  int? _contentLengthFromHeaders(String headers) {
    for (final line in headers.split('\n')) {
      final l = line.trim().toLowerCase();
      if (l.startsWith('content-length:')) {
        final v = l.split(':').last.trim();
        final n = int.tryParse(v);
        if (n != null && n > 0) return n;
      }
    }
    return null;
  }

  int _indexOfSubsequence(List<int> data, List<int> pattern, int start) {
    if (pattern.isEmpty) return -1;
    for (int i = start; i <= data.length - pattern.length; i++) {
      bool match = true;
      for (int j = 0; j < pattern.length; j++) {
        if (data[i + j] != pattern[j]) {
          match = false;
          break;
        }
      }
      if (match) return i;
    }
    return -1;
  }

  int _indexOfJpegSoi(List<int> data, {int from = 0}) {
    for (int i = from; i < data.length - 1; i++) {
      if (data[i] == 0xFF && data[i + 1] == 0xD8) return i;
    }
    return -1;
  }

  int _indexOfJpegEoi(List<int> data, {int from = 0}) {
    for (int i = from; i < data.length - 1; i++) {
      if (data[i] == 0xFF && data[i + 1] == 0xD9) return i;
    }
    return -1;
  }

  // ---- UI -------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    final url = _activeUrl ?? 'http://$_ip${_jpegCandidates[_candidateIndex]}';
    return Scaffold(
      appBar: AppBar(
        title: const Text('Hawkeye WiFi Viewer'),
        actions: [
          IconButton(
            tooltip: 'Open Web UI',
            onPressed: _openWebUi,
            icon: const Icon(Icons.open_in_new),
          ),
          IconButton(
            tooltip: 'Set IP',
            onPressed: _setIpPrompt,
            icon: const Icon(Icons.settings_ethernet),
          ),
        ],
      ),
      body: Column(
        children: [
          // Status & controls
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 12, 12, 8),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    _status,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                const SizedBox(width: 8),
                ElevatedButton(
                  onPressed: _probe,
                  child: const Text('Auto-Detect'),
                ),
                const SizedBox(width: 8),
                OutlinedButton(
                  onPressed: _switchUrl,
                  child: const Text('Next Path'),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
            child: Row(
              children: [
                ElevatedButton(
                  onPressed: () => _startTcpStream(_ip, 7060),
                  child: const Text('TCP :7060'),
                ),
                const SizedBox(width: 8),
                OutlinedButton(
                  onPressed: _scanPorts,
                  child: const Text('Scan Ports'),
                ),
                const SizedBox(width: 8),
                OutlinedButton(
                  onPressed: _tryRtsp,
                  child: const Text('Try RTSP'),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text('URL: $url', style: const TextStyle(fontSize: 12)),
            ),
          ),
          // Live image area
          Expanded(
            child: Center(
              child: AspectRatio(
                aspectRatio: 1,
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: Colors.black,
                    border: Border.all(color: Colors.white24),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      // VLC player ALWAYS in the tree so the platform view initializes.
                      // It sits at the bottom of the stack; other widgets overlay it.
                      VlcPlayer(
                        controller: _vlcController,
                        aspectRatio: 1,
                        placeholder: const SizedBox.shrink(),
                      ),
                      // MJPEG image display (overlays VLC when active)
                      if (!_isRtspMode && _lastFrame != null)
                        Image.memory(_lastFrame!, gaplessPlayback: true, fit: BoxFit.contain),
                      // Loading/scanning indicator (overlays VLC when not streaming)
                      // Use slightly transparent black so Flutter doesn't skip
                      // creating the VLC platform view underneath.
                      if (!_isRtspMode && _lastFrame == null)
                        Container(
                          color: const Color(0xFE000000), // 99.6% opaque
                          child: const Center(child: CircularProgressIndicator()),
                        ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
