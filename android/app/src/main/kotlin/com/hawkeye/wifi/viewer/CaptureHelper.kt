package com.hawkeye.wifi.viewer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import com.alexvas.rtsp.widget.RtspDataListener
import com.alexvas.rtsp.widget.RtspSurfaceView
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.MediaCodecInfo
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

interface CaptureCallback {
    fun onPhotoSaved(path: String)
    fun onRecordingStopped(path: String, durationMs: Long)
    fun onCaptureError(message: String)
}

class CaptureHelper(private val context: Context) {

    companion object {
        private const val TAG = "CaptureHelper"
        private const val NAL_QUEUE_CAPACITY = 60 // ~6 seconds at 10fps
    }

    var callback: CaptureCallback? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    // --- Recording state ---
    private var recordThread: Thread? = null
    private val recordStopped = AtomicBoolean(false)
    private var recordingOutputPath: String? = null
    private var recordingStartTimeMs = 0L
    private var recordingWifiName: String? = null
    private var startNanos = 0L
    private var frameCount = 0

    // NAL queue: data listener (RTSP thread) → muxing thread
    private data class NalUnit(val data: ByteArray, val timestamp: Long)
    private var nalQueue: ArrayBlockingQueue<NalUnit>? = null
    private var recordingSurfaceView: RtspSurfaceView? = null

    // Direct muxing (no transcode — camera sends all-intra frames)
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var frameNumBits = 0 // log2_max_frame_num_minus4 + 4, from SPS
    private var picOrderCntType = -1 // -1 = unknown
    private var pocLsbBits = 0 // log2_max_pic_order_cnt_lsb_minus4 + 4, if poc_type == 0

    val isRecording: Boolean get() = recordThread?.isAlive == true && !recordStopped.get()

    // =====================================================================
    // PHOTO CAPTURE
    // =====================================================================

    fun capturePhoto(surfaceView: SurfaceView, wifiName: String) {
        val width = surfaceView.width
        val height = surfaceView.height
        if (width <= 0 || height <= 0) {
            postError("Surface not ready for capture")
            return
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        PixelCopy.request(surfaceView, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                Thread({
                    try {
                        val path = saveBitmapToGallery(bitmap, wifiName)
                        bitmap.recycle()
                        if (path != null) {
                            uiHandler.post { callback?.onPhotoSaved(path) }
                        } else {
                            postError("Failed to save photo")
                        }
                    } catch (e: Exception) {
                        bitmap.recycle()
                        Log.e(TAG, "Photo save error", e)
                        postError("Photo save failed: ${e.message}")
                    }
                }, "PhotoSave").start()
            } else {
                bitmap.recycle()
                postError("PixelCopy failed (code $result)")
            }
        }, uiHandler)
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, wifiName: String): String? {
        val filename = "IMG_${System.currentTimeMillis()}.jpg"
        val subfolder = sanitizeFolderName(wifiName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/$subfolder")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return null

            context.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            Log.d(TAG, "Photo saved via MediaStore: DCIM/$subfolder/$filename")
            return "DCIM/$subfolder/$filename"
        } else {
            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val dir = File(dcim, subfolder)
            dir.mkdirs()
            val file = File(dir, filename)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
            Log.d(TAG, "Photo saved to file: ${file.absolutePath}")
            return file.absolutePath
        }
    }

    // =====================================================================
    // VIDEO RECORDING — direct mux (no transcode)
    //
    // The camera sends all-intra H.264 frames labeled as NAL type 1
    // (non-IDR). Since every frame is independently decodable, we mux
    // them directly into MP4 with BUFFER_FLAG_KEY_FRAME set so every
    // frame is a sync sample. The NAL type is left as-is (type 1) —
    // rewriting to type 5 (IDR) corrupts playback because IDR slices
    // have an extra idr_pic_id field in the slice header.
    //
    // This avoids the decoder+encoder transcode pipeline entirely, which
    // doesn't work on resource-limited devices (tablet's Snapdragon 429
    // can't allocate a second hardware decoder, and the software decoder
    // can't decode non-IDR frames from a cold start).
    // =====================================================================

    fun startRecording(surfaceView: RtspSurfaceView, rtspUrl: String, wifiName: String, width: Int = 400, height: Int = 400) {
        if (isRecording) {
            postError("Already recording")
            return
        }

        recordStopped.set(false)
        recordingWifiName = wifiName
        muxerStarted = false
        videoTrackIndex = -1
        startNanos = 0L
        frameCount = 0
        recordingSurfaceView = surfaceView
        nalQueue = ArrayBlockingQueue(NAL_QUEUE_CAPACITY)

        val tempFile = File(context.cacheDir, "recording_${System.currentTimeMillis()}.mp4")
        recordingOutputPath = tempFile.absolutePath

        // Capture first frame from live view for thumbnail IDR
        val thumbnailHolder = arrayOfNulls<Bitmap>(1)
        val thumbnailLatch = java.util.concurrent.CountDownLatch(1)
        val sw = surfaceView.width
        val sh = surfaceView.height
        if (sw > 0 && sh > 0) {
            val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            PixelCopy.request(surfaceView, bmp, { result ->
                if (result == PixelCopy.SUCCESS) {
                    thumbnailHolder[0] = bmp
                } else {
                    bmp.recycle()
                }
                thumbnailLatch.countDown()
            }, uiHandler)
        } else {
            thumbnailLatch.countDown()
        }

        recordThread = Thread({
            try {
                // 1. Get SPS/PPS via lightweight RTSP DESCRIBE
                val spsPps = fetchSpsAndPps(rtspUrl)
                if (spsPps == null) {
                    Log.e(TAG, "Failed to get SPS/PPS via DESCRIBE")
                    postError("Could not get video parameters")
                    return@Thread
                }
                if (recordStopped.get()) return@Thread

                val (sps, pps) = spsPps
                Log.d(TAG, "Got SPS (${sps.size}B) and PPS (${pps.size}B) from DESCRIBE")

                // 2. Determine video dimensions
                val vidW: Int
                val vidH: Int
                if (width > 0 && height > 0) {
                    vidW = width
                    vidH = height
                    Log.d(TAG, "Recording resolution from stream: ${vidW}x${vidH}")
                } else {
                    val spsDims = parseSpsResolution(sps)
                    if (spsDims != null) {
                        vidW = spsDims.first
                        vidH = spsDims.second
                        Log.d(TAG, "Recording resolution from SPS: ${vidW}x${vidH}")
                    } else {
                        vidW = 960
                        vidH = 720
                        Log.w(TAG, "No resolution available — using default 960x720")
                    }
                }

                // 2.5. Wait for thumbnail PixelCopy and encode as IDR
                if (recordStopped.get()) return@Thread
                var encoderPps: ByteArray? = null
                var encoderIdr: ByteArray? = null
                thumbnailLatch.await(2, TimeUnit.SECONDS)
                val thumbBmp = thumbnailHolder[0]
                if (thumbBmp != null) {
                    try {
                        val scaled = if (thumbBmp.width != vidW || thumbBmp.height != vidH) {
                            Bitmap.createScaledBitmap(thumbBmp, vidW, vidH, true)
                        } else null
                        val bmp = scaled ?: thumbBmp
                        val encoded = encodeFrameAsIdr(bmp, vidW, vidH, sps)
                        scaled?.recycle()
                        if (encoded != null) {
                            // encoded.first (encoder SPS) is null — we use camera SPS only
                            encoderPps = encoded.second
                            encoderIdr = encoded.third
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Thumbnail encoding failed: ${e.message}", e)
                    }
                    thumbBmp.recycle()
                }

                // 3. Init direct muxer
                if (recordStopped.get()) return@Thread
                try {
                    initDirectMux(sps, pps, vidW, vidH, null, encoderPps)
                    recordingStartTimeMs = System.currentTimeMillis()
                } catch (e: Exception) {
                    Log.e(TAG, "Muxer init failed", e)
                    cleanupMuxer()
                    postError("Recording init failed: ${e.message}")
                    return@Thread
                }
                if (recordStopped.get()) {
                    cleanupMuxer()
                    return@Thread
                }

                // 3.5. Write thumbnail IDR as first frame
                if (encoderIdr != null) {
                    try {
                        val buf = ByteBuffer.wrap(encoderIdr)
                        val info = MediaCodec.BufferInfo().apply {
                            offset = 0; size = encoderIdr.size
                            presentationTimeUs = 0
                            flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
                        }
                        muxer!!.writeSampleData(videoTrackIndex, buf, info)
                        frameCount++
                        Log.d(TAG, "Thumbnail IDR written: ${encoderIdr.size} bytes")
                    } catch (e: Exception) {
                        Log.w(TAG, "Thumbnail IDR write failed: ${e.message}", e)
                    }
                }

                // startNanos initialized lazily on first NAL so camera frames
                // start at PTS≈0, minimizing the IDR thumbnail's display time

                // 4. Set data listener to start receiving NALs from live view
                surfaceView.setDataListener(dataListener)
                Log.d(TAG, "Data listener set — recording active")

                // 4. Consume NALs from queue and mux directly to MP4
                while (!recordStopped.get()) {
                    val nal = nalQueue?.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    if (startNanos == 0L) startNanos = System.nanoTime()
                    val pts = (System.nanoTime() - startNanos) / 1000 // μs
                    frameCount++
                    muxNalUnit(nal.data, 0, nal.data.size, pts)
                }

                // 5. Remove listener and finalize
                surfaceView.setDataListener(null)
                finalizeRecording()

            } catch (e: Exception) {
                Log.e(TAG, "Recording thread error", e)
                try { surfaceView.setDataListener(null) } catch (_: Exception) {}
                finalizeRecording()
            }
        }, "RecordMux")
        recordThread!!.start()
        Log.d(TAG, "Recording started → $recordingOutputPath")
    }

    private val dataListener = object : RtspDataListener {
        override fun onRtspDataVideoNalUnitReceived(
            data: ByteArray, offset: Int, length: Int, timestamp: Long
        ) {
            if (recordStopped.get()) return
            if (length <= 4) return
            // Copy data (library may reuse buffer) and enqueue
            val copy = ByteArray(length)
            System.arraycopy(data, offset, copy, 0, length)
            nalQueue?.offer(NalUnit(copy, timestamp)) // non-blocking, drops if full
        }
    }

    // =====================================================================
    // RTSP DESCRIBE — lightweight SPS/PPS extraction
    // =====================================================================

    private fun fetchSpsAndPps(rtspUrl: String): Pair<ByteArray, ByteArray>? {
        try {
            val uri = Uri.parse(rtspUrl)
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 554

            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 3000)
            socket.soTimeout = 3000

            try {
                val out = socket.getOutputStream()
                val inp = BufferedReader(InputStreamReader(socket.getInputStream()))

                val request = "DESCRIBE $rtspUrl RTSP/1.0\r\n" +
                    "CSeq: 1\r\n" +
                    "Accept: application/sdp\r\n" +
                    "\r\n"
                out.write(request.toByteArray())
                out.flush()

                // Read response headers
                val response = StringBuilder()
                var contentLength = 0
                while (true) {
                    val line = inp.readLine() ?: break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    response.append(line).append('\n')
                    if (line.isEmpty()) break // end of headers
                }

                // Read SDP body
                if (contentLength > 0) {
                    val body = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = inp.read(body, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    response.append(body, 0, read)
                }

                val sdp = response.toString()
                Log.d(TAG, "DESCRIBE response received (${sdp.length} chars)")

                // Parse sprop-parameter-sets=<base64 SPS>,<base64 PPS>
                val match = Regex("sprop-parameter-sets=([A-Za-z0-9+/=]+),([A-Za-z0-9+/=]+)")
                    .find(sdp)
                if (match != null) {
                    val sps = Base64.decode(match.groupValues[1], Base64.NO_WRAP)
                    val pps = Base64.decode(match.groupValues[2], Base64.NO_WRAP)
                    return Pair(sps, pps)
                }
                Log.w(TAG, "No sprop-parameter-sets found in SDP")
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "DESCRIBE failed: ${e.message}")
        }
        return null
    }

    // =====================================================================
    // DIRECT MUX — raw H.264 NALs → MP4 (no transcode)
    // =====================================================================

    private fun initDirectMux(sps: ByteArray, pps: ByteArray, width: Int, height: Int,
                              encoderSps: ByteArray? = null, encoderPps: ByteArray? = null) {
        parseSpsInfo(sps)
        val cameraEntropy = parsePpsEntropyMode(pps)
        Log.d(TAG, "Camera PPS: entropy=${if (cameraEntropy) "CABAC" else "CAVLC"} " +
            "hex=${pps.joinToString("") { String.format("%02X", it) }}")

        val sc = byteArrayOf(0, 0, 0, 1)
        // csd-0: camera SPS only (all frames use sps_id=0)
        val csd0 = sc + sps
        // csd-1: camera PPS (id=0, CABAC) + optional encoder PPS (id=2, CAVLC)
        val csd1 = if (encoderPps != null) sc + pps + sc + encoderPps else sc + pps

        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, width, height
        ).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
        }
        muxer = MediaMuxer(recordingOutputPath!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        videoTrackIndex = muxer!!.addTrack(format)
        muxer!!.start()
        muxerStarted = true
        val ppsCount = if (encoderPps != null) 2 else 1
        Log.d(TAG, "Direct muxer started: ${width}x${height} ($ppsCount PPS)")
    }

    private fun cleanupMuxer() {
        if (muxerStarted) {
            try { muxer?.stop() } catch (_: Exception) {}
        }
        try { muxer?.release() } catch (_: Exception) {}
        muxer = null
        muxerStarted = false
    }

    /**
     * Write NAL unit(s) to the muxer. The Q2's RTSP data may contain multiple
     * NALs per callback (e.g. a filler NAL + frame NAL). We parse them apart
     * and skip non-video NALs.
     *
     * NAL type rewrite (1→5 IDR) is NOT possible because the camera sends
     * P-slices (slice_type=0) which have additional header fields (ref_idx,
     * ref_pic_list_mod) that IDR slices don't. Instead, we mark all frames
     * as KEY_FRAME and embed a cover art thumbnail in the MP4 for Gallery.
     */
    private fun muxNalUnit(data: ByteArray, offset: Int, length: Int, pts: Long) {
        if (!muxerStarted) return

        // Parse individual NAL units from Annex B data
        val nalUnits = parseNalUnits(data, offset, length)

        for (nalPayload in nalUnits) {
            if (nalPayload.isEmpty()) continue
            val nalType = nalPayload[0].toInt() and 0x1F

            // Only mux video slice NALs (type 1 non-IDR, type 5 IDR)
            if (nalType != 1 && nalType != 5) continue

            val buf = ByteBuffer.wrap(nalPayload)
            val info = MediaCodec.BufferInfo().apply {
                this.offset = 0
                this.size = nalPayload.size
                this.presentationTimeUs = pts
                this.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
            }

            try {
                muxer!!.writeSampleData(videoTrackIndex, buf, info)
            } catch (e: Exception) {
                Log.w(TAG, "Muxer write error on frame $frameCount: ${e.message}")
            }
        }
    }

    /**
     * Parse SPS to extract parameters needed for IDR rewrite:
     * - frameNumBits (log2_max_frame_num_minus4 + 4)
     * - picOrderCntType (0, 1, or 2)
     * - pocLsbBits (log2_max_pic_order_cnt_lsb_minus4 + 4, if poc_type == 0)
     */
    private fun parseSpsInfo(sps: ByteArray) {
        frameNumBits = 0
        picOrderCntType = -1
        pocLsbBits = 0

        try {
            val r = BitReader(sps)
            r.skip(8) // NAL header
            val profileIdc = r.readBits(8)
            r.skip(8) // constraint flags
            r.skip(8) // level_idc
            r.readUE() // seq_parameter_set_id

            if (profileIdc in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
                val chromaFormatIdc = r.readUE()
                if (chromaFormatIdc == 3) r.skip(1)
                r.readUE() // bit_depth_luma_minus8
                r.readUE() // bit_depth_chroma_minus8
                r.skip(1)  // qpprime_y_zero_transform_bypass
                if (r.readBit() == 1) { // seq_scaling_matrix_present
                    val cnt = if (chromaFormatIdc != 3) 8 else 12
                    for (i in 0 until cnt) {
                        if (r.readBit() == 1) {
                            val size = if (i < 6) 16 else 64
                            var lastScale = 8; var nextScale = 8
                            for (j in 0 until size) {
                                if (nextScale != 0) nextScale = (lastScale + r.readSE() + 256) % 256
                                lastScale = if (nextScale == 0) lastScale else nextScale
                            }
                        }
                    }
                }
            }

            val log2MaxFrameNumMinus4 = r.readUE()
            frameNumBits = log2MaxFrameNumMinus4 + 4

            picOrderCntType = r.readUE()
            if (picOrderCntType == 0) {
                pocLsbBits = r.readUE() + 4 // log2_max_pic_order_cnt_lsb_minus4 + 4
            } else if (picOrderCntType == 1) {
                r.skip(1) // delta_pic_order_always_zero_flag
                r.readSE() // offset_for_non_ref_pic
                r.readSE() // offset_for_top_to_bottom_field
                val n = r.readUE() // num_ref_frames_in_pic_order_cnt_cycle
                for (i in 0 until n) r.readSE()
            }
            // pic_order_cnt_type == 2: nothing more to parse

            Log.d(TAG, "SPS: frameNumBits=$frameNumBits, pocType=$picOrderCntType, pocLsbBits=$pocLsbBits")
        } catch (e: Exception) {
            Log.w(TAG, "SPS parse error: ${e.message}")
            frameNumBits = 0 // disables IDR rewrite
        }
    }

    /** Parsed SPS timing info needed for IDR slice header rewriting. */
    private data class SpsTimingInfo(val frameNumBits: Int, val pocType: Int, val pocLsbBits: Int)

    /** Parse SPS to extract frameNumBits, pocType, and pocLsbBits. */
    private fun getSpsTimingInfo(sps: ByteArray): SpsTimingInfo? {
        return try {
            val r = BitReader(sps)
            r.skip(8) // NAL header
            val profileIdc = r.readBits(8)
            r.skip(8) // constraint flags
            r.skip(8) // level_idc
            r.readUE() // seq_parameter_set_id
            if (profileIdc in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
                val chromaFormatIdc = r.readUE()
                if (chromaFormatIdc == 3) r.skip(1)
                r.readUE(); r.readUE(); r.skip(1)
                if (r.readBit() == 1) {
                    val cnt = if (chromaFormatIdc != 3) 8 else 12
                    for (i in 0 until cnt) {
                        if (r.readBit() == 1) {
                            val size = if (i < 6) 16 else 64
                            var lastScale = 8; var nextScale = 8
                            for (j in 0 until size) {
                                if (nextScale != 0) nextScale = (lastScale + r.readSE() + 256) % 256
                                lastScale = if (nextScale == 0) lastScale else nextScale
                            }
                        }
                    }
                }
            }
            val fnBits = r.readUE() + 4
            val pocType = r.readUE()
            val pocLsb = if (pocType == 0) r.readUE() + 4 else 0
            SpsTimingInfo(fnBits, pocType, pocLsb)
        } catch (e: Exception) { null }
    }

    /** Parse PPS to extract entropy_coding_mode_flag. Returns true if CABAC. */
    private fun parsePpsEntropyMode(pps: ByteArray): Boolean {
        return try {
            val r = BitReader(pps)
            r.skip(8)     // NAL header
            r.readUE()    // pic_parameter_set_id
            r.readUE()    // seq_parameter_set_id
            r.readBit() == 1 // entropy_coding_mode_flag: 1=CABAC, 0=CAVLC
        } catch (e: Exception) {
            Log.w(TAG, "PPS parse error: ${e.message}")
            false
        }
    }

    /**
     * Parse Annex B byte stream into individual NAL unit payloads (without start codes).
     * Handles both 3-byte (00 00 01) and 4-byte (00 00 00 01) start codes.
     */
    private fun parseNalUnits(data: ByteArray, offset: Int, length: Int): List<ByteArray> {
        val units = mutableListOf<ByteArray>()
        val end = offset + length
        var i = offset

        // Find all start code positions
        val starts = mutableListOf<Int>()
        while (i < end - 2) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (i + 2 < end && data[i + 2] == 1.toByte()) {
                    starts.add(i + 3) // 3-byte start code
                    i += 3
                    continue
                } else if (i + 3 < end && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    starts.add(i + 4) // 4-byte start code
                    i += 4
                    continue
                }
            }
            i++
        }

        // Extract each NAL payload as a clean copy
        for (j in starts.indices) {
            val nalStart = starts[j]
            val nalEnd = if (j + 1 < starts.size) {
                // Find the start code before the next NAL
                var sc = starts[j + 1] - 3
                if (sc >= 1 && data[sc - 1] == 0.toByte()) sc-- // 4-byte start code
                sc
            } else {
                end
            }
            val len = nalEnd - nalStart
            if (len > 0) {
                val payload = ByteArray(len)
                System.arraycopy(data, nalStart, payload, 0, len)
                units.add(payload)
            }
        }

        return units
    }

    fun stopRecording() {
        if (!isRecording) return
        Log.d(TAG, "Stopping recording")
        recordStopped.set(true)
    }

    private fun finalizeRecording() {
        val outputPath = recordingOutputPath ?: return
        val durationMs = System.currentTimeMillis() - recordingStartTimeMs
        val wifiName = recordingWifiName ?: "Camera"

        try {
            if (muxerStarted && frameCount > 0) {
                try { muxer?.stop() } catch (_: Exception) {}
                try { muxer?.release() } catch (_: Exception) {}
                muxer = null
                muxerStarted = false

                Log.d(TAG, "Recording finalized: $outputPath (${durationMs}ms, $frameCount frames)")

                // Save to gallery on a separate thread
                Thread({
                    try {
                        val galleryPath = saveVideoToGallery(outputPath, wifiName)
                        File(outputPath).delete()
                        if (galleryPath != null) {
                            uiHandler.post { callback?.onRecordingStopped(galleryPath, durationMs) }
                        } else {
                            postError("Failed to save video to gallery")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Video gallery save error", e)
                        postError("Video save failed: ${e.message}")
                    }
                }, "VideoSave").start()
            } else {
                cleanupMuxer()
                File(outputPath).delete()
                Log.w(TAG, "No frames recorded — no video saved")
                postError("Recording failed — no video data captured")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording finalize error", e)
            cleanupMuxer()
        }

        recordingOutputPath = null
        recordingSurfaceView = null
        nalQueue = null
    }

    private fun saveVideoToGallery(tempPath: String, wifiName: String): String? {
        val filename = "VID_${System.currentTimeMillis()}.mp4"
        val subfolder = sanitizeFolderName(wifiName)
        val tempFile = File(tempPath)

        // Embed Q2 logo as MP4 cover art BEFORE copying to MediaStore
        embedCoverArt(tempPath)

        val galleryPath: String?

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/$subfolder")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return null

            context.contentResolver.openOutputStream(uri)?.use { out ->
                tempFile.inputStream().use { it.copyTo(out) }
            }
            Log.d(TAG, "Video saved via MediaStore: DCIM/$subfolder/$filename")

            val clearPending = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, clearPending, null, null)
            Log.d(TAG, "Video published (IS_PENDING cleared)")

            galleryPath = "DCIM/$subfolder/$filename"
        } else {
            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val dir = File(dcim, subfolder)
            dir.mkdirs()
            val destFile = File(dir, filename)
            tempFile.copyTo(destFile, overwrite = true)
            Log.d(TAG, "Video saved to file: ${destFile.absolutePath}")
            galleryPath = destFile.absolutePath

            MediaScannerConnection.scanFile(
                context, arrayOf(destFile.absolutePath), arrayOf("video/mp4"), null
            )
        }

        return galleryPath
    }

    /**
     * Embed Q2 logo as MP4 cover art (iTunes metadata).
     * Loads Q2.png from app assets and embeds as JPEG in the moov/udta/meta/ilst/covr atom.
     */
    private fun embedCoverArt(mp4Path: String) {
        try {
            // Load Q2 logo from assets and encode as JPEG
            val logoBitmap = BitmapFactory.decodeStream(
                context.assets.open("Q2.png")
            )
            if (logoBitmap == null) {
                Log.w(TAG, "Failed to decode Q2.png from assets")
                return
            }
            val jpegStream = java.io.ByteArrayOutputStream()
            logoBitmap.compress(Bitmap.CompressFormat.JPEG, 90, jpegStream)
            logoBitmap.recycle()
            val jpegBytes = jpegStream.toByteArray()
            Log.d(TAG, "Cover art JPEG: ${jpegBytes.size} bytes")

            // Build iTunes metadata atoms: udta > meta > hdlr + ilst > covr > data
            val dataPayload = ByteBuffer.allocate(8 + jpegBytes.size).apply {
                putInt(0x0000000D) // type indicator: 13 = JPEG
                putInt(0)          // locale
                put(jpegBytes)
            }.array()
            val dataAtom = buildAtom("data", dataPayload)
            val covrAtom = buildAtom("covr", dataAtom)
            val ilstAtom = buildAtom("ilst", covrAtom)

            // hdlr atom for meta container
            val hdlrPayload = ByteBuffer.allocate(25).apply {
                putInt(0)  // version + flags
                putInt(0)  // pre_defined
                put("mdir".toByteArray()) // handler_type
                putInt(0); putInt(0); putInt(0) // reserved
                put(0)     // name (null-terminated empty string)
            }.array()
            val hdlrAtom = buildAtom("hdlr", hdlrPayload)

            // meta is a "full box": 4 bytes version+flags before children
            val metaChildren = ByteBuffer.allocate(4 + hdlrAtom.size + ilstAtom.size).apply {
                putInt(0) // version + flags
                put(hdlrAtom)
                put(ilstAtom)
            }.array()
            val metaAtom = buildAtom("meta", metaChildren)
            val udtaAtom = buildAtom("udta", metaAtom)

            // Read the MP4 and find the moov atom
            val mp4File = File(mp4Path)
            val mp4Data = mp4File.readBytes()

            var moovOffset = -1
            var moovSize = 0
            var pos = 0
            while (pos + 8 <= mp4Data.size) {
                // Read atom size as unsigned 32-bit big-endian
                var atomSize = (((mp4Data[pos].toInt() and 0xFF).toLong() shl 24) or
                    ((mp4Data[pos+1].toInt() and 0xFF).toLong() shl 16) or
                    ((mp4Data[pos+2].toInt() and 0xFF).toLong() shl 8) or
                    (mp4Data[pos+3].toInt() and 0xFF).toLong())
                val atomType = String(mp4Data, pos + 4, 4, Charsets.US_ASCII)

                // Handle extended size (64-bit) when size field == 1
                if (atomSize == 1L && pos + 16 <= mp4Data.size) {
                    atomSize = ByteBuffer.wrap(mp4Data, pos + 8, 8).long
                } else if (atomSize == 0L) {
                    atomSize = (mp4Data.size - pos).toLong() // extends to EOF
                }

                Log.d(TAG, "MP4 atom: '$atomType' size=$atomSize at offset=$pos")

                if (atomSize < 8) break
                if (atomType == "moov") {
                    moovOffset = pos
                    moovSize = atomSize.toInt()
                    break
                }
                pos += atomSize.toInt()
            }

            if (moovOffset < 0) {
                Log.w(TAG, "No moov atom found in MP4")
                return
            }

            // Append udta inside moov (update moov size)
            val newMoovSize = moovSize + udtaAtom.size
            val output = java.io.ByteArrayOutputStream(mp4Data.size + udtaAtom.size)

            // Everything before moov
            output.write(mp4Data, 0, moovOffset)
            // Updated moov size (big-endian)
            output.write(byteArrayOf(
                (newMoovSize shr 24).toByte(),
                (newMoovSize shr 16).toByte(),
                (newMoovSize shr 8).toByte(),
                newMoovSize.toByte()
            ))
            // Rest of original moov content (skip size field)
            output.write(mp4Data, moovOffset + 4, moovSize - 4)
            // Append udta as last child of moov
            output.write(udtaAtom)
            // Everything after moov
            val afterMoov = moovOffset + moovSize
            if (afterMoov < mp4Data.size) {
                output.write(mp4Data, afterMoov, mp4Data.size - afterMoov)
            }

            mp4File.writeBytes(output.toByteArray())
            Log.d(TAG, "Embedded cover art in MP4 (${jpegBytes.size}B JPEG, moov at $moovOffset)")
        } catch (e: Exception) {
            Log.w(TAG, "Cover art embedding failed: ${e.message}")
        }
    }

    private fun buildAtom(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return ByteBuffer.allocate(size).apply {
            putInt(size)
            put(type.toByteArray(Charsets.US_ASCII))
            put(payload)
        }.array()
    }

    fun release() {
        if (isRecording) {
            recordStopped.set(true)
            try { recordingSurfaceView?.setDataListener(null) } catch (_: Exception) {}
            recordThread?.join(3000)
        }
        cleanupMuxer()
        recordingOutputPath?.let { File(it).delete() }
        recordingOutputPath = null
        recordingSurfaceView = null
        nalQueue = null
        callback = null
    }

    // =====================================================================
    // UTILITIES
    // =====================================================================

    private fun sanitizeFolderName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").ifEmpty { "Camera" }
    }

    private fun postError(message: String) {
        uiHandler.post { callback?.onCaptureError(message) }
    }

    // =====================================================================
    // SPS DIMENSION PARSER
    // =====================================================================

    private fun parseSpsResolution(sps: ByteArray): Pair<Int, Int>? {
        try {
            val r = BitReader(sps)
            r.skip(8) // NAL header
            val profileIdc = r.readBits(8)
            r.skip(8) // constraint flags
            r.skip(8) // level_idc
            r.readUE() // seq_parameter_set_id

            if (profileIdc in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
                val chromaFormatIdc = r.readUE()
                if (chromaFormatIdc == 3) r.skip(1)
                r.readUE(); r.readUE(); r.skip(1)
                if (r.readBit() == 1) {
                    val cnt = if (chromaFormatIdc != 3) 8 else 12
                    for (i in 0 until cnt) {
                        if (r.readBit() == 1) {
                            val size = if (i < 6) 16 else 64
                            var lastScale = 8; var nextScale = 8
                            for (j in 0 until size) {
                                if (nextScale != 0) nextScale = (lastScale + r.readSE() + 256) % 256
                                lastScale = if (nextScale == 0) lastScale else nextScale
                            }
                        }
                    }
                }
            }

            r.readUE() // log2_max_frame_num_minus4
            when (r.readUE()) {
                0 -> r.readUE()
                1 -> { r.skip(1); r.readSE(); r.readSE(); repeat(r.readUE()) { r.readSE() } }
            }
            r.readUE(); r.skip(1)

            val picWidthMbs = r.readUE() + 1
            val picHeightMapUnits = r.readUE() + 1
            val frameMbsOnly = r.readBit()
            if (frameMbsOnly == 0) r.skip(1)
            r.skip(1)

            var width = picWidthMbs * 16
            var height = picHeightMapUnits * 16 * (2 - frameMbsOnly)

            if (r.readBit() == 1) {
                val cropLeft = r.readUE(); val cropRight = r.readUE()
                val cropTop = r.readUE(); val cropBottom = r.readUE()
                width -= (cropLeft + cropRight) * 2
                height -= (cropTop + cropBottom) * 2 * (2 - frameMbsOnly)
            }

            return Pair(width, height)
        } catch (e: Exception) {
            Log.w(TAG, "SPS resolution parse failed", e)
            return null
        }
    }

    // =====================================================================
    // CAVLC PPS + RESET IDR — camera-SPS-compatible I_16x16 gray frame
    //
    // After the encoder thumbnail IDR (which uses encoder SPS, sps_id=1),
    // a "reset" IDR under the camera's SPS (sps_id=0) re-initializes the
    // decoder before camera P-frames start. This prevents the 2-second
    // corruption caused by the SPS parameter mismatch during transition.
    // The reset frame uses I_16x16 DC prediction (solid gray, ~2.7KB)
    // with a CAVLC PPS (pps_id=1) since the camera's PPS uses CABAC.
    // =====================================================================

    /**
     * Generate a minimal PPS NAL with pps_id=1, sps_id=0, CAVLC entropy.
     * Used by the reset IDR so it can use CAVLC UE-coded mb_type
     * while referencing the camera's SPS.
     */
    private fun generateCavlcPps(): ByteArray {
        val w = BitWriter(8)
        w.writeBits(0x68, 8)  // NAL header: type=8 (PPS), NRI=3
        w.writeUE(1)          // pic_parameter_set_id = 1
        w.writeUE(0)          // seq_parameter_set_id = 0 (camera's SPS)
        w.writeBit(0)         // entropy_coding_mode_flag = 0 (CAVLC)
        w.writeBit(0)         // bottom_field_pic_order_in_frame_present_flag
        w.writeUE(0)          // num_slice_groups_minus1 = 0
        w.writeUE(0)          // num_ref_idx_l0_default_active_minus1 = 0
        w.writeUE(0)          // num_ref_idx_l1_default_active_minus1 = 0
        w.writeBit(0)         // weighted_pred_flag = 0
        w.writeBits(0, 2)     // weighted_bipred_idc = 0
        w.writeSE(0)          // pic_init_qp_minus26 = 0 (QP=26)
        w.writeSE(0)          // pic_init_qs_minus26 = 0
        w.writeSE(0)          // chroma_qp_index_offset = 0
        w.writeBit(0)         // deblocking_filter_control_present_flag
        w.writeBit(0)         // constrained_intra_pred_flag
        w.writeBit(0)         // redundant_pic_cnt_present_flag
        w.writeBit(1)         // RBSP stop bit
        w.byteAlign()
        return w.toByteArray()
    }

    /**
     * Build a reset IDR NAL with I_16x16 DC prediction (solid gray frame).
     * Uses camera's SPS (sps_id=0) via CAVLC PPS (pps_id=1) to properly
     * re-initialize the decoder after the encoder thumbnail IDR.
     * Per macroblock: UE(3) + UE(0) + SE(0) + "1" = 8 bits. ~2.7KB total.
     */
    private fun generateResetIdr(width: Int, height: Int): ByteArray? {
        try {
            val mbW = (width + 15) / 16
            val mbH = (height + 15) / 16
            val totalMbs = mbW * mbH

            val w = BitWriter(totalMbs + 64)

            // NAL header: forbidden=0, NRI=3, type=5 (IDR)
            w.writeBits(0x65, 8)

            // Slice header (camera SPS params: frameNumBits=16, pocType=2)
            w.writeUE(0)                    // first_mb_in_slice = 0
            w.writeUE(2)                    // slice_type = 2 (I-slice)
            w.writeUE(1)                    // pic_parameter_set_id = 1 (CAVLC PPS)
            w.writeBits(0, frameNumBits)    // frame_num = 0
            w.writeUE(0)                    // idr_pic_id = 0
            // poc_type=2 → no POC fields
            // nal_ref_idc=3 → dec_ref_pic_marking (IDR):
            w.writeBit(0)                   // no_output_of_prior_pics_flag
            w.writeBit(0)                   // long_term_reference_flag
            w.writeSE(0)                    // slice_qp_delta = 0

            // Macroblock layer: I_16x16_2_0_0 (DC prediction, zero CBP)
            for (i in 0 until totalMbs) {
                w.writeUE(3)                // mb_type = I_16x16_2_0_0
                w.writeUE(0)                // intra_chroma_pred_mode = 0 (DC)
                w.writeSE(0)                // mb_qp_delta = 0
                w.writeBit(1)               // coeff_token: TotalCoeff=0 at nC=0
            }

            w.writeBit(1)                   // RBSP stop bit
            w.byteAlign()

            val rbsp = w.toByteArray()
            val nal = addEmulationPrevention(rbsp)
            Log.d(TAG, "Reset IDR: ${totalMbs} MBs, ${nal.size}B")
            return nal
        } catch (e: Exception) {
            Log.e(TAG, "Reset IDR generation failed", e)
            return null
        }
    }

    // =====================================================================
    // THUMBNAIL IDR — MediaCodec encoder from PixelCopy bitmap
    //
    // Encodes a PixelCopy frame from the live view as a proper H.264 IDR
    // using the device's hardware encoder. This produces a standards-compliant
    // keyframe that Samsung Gallery's getFrameAtTime(0) can decode for
    // thumbnails. The encoder's SPS/PPS use rewritten IDs (sps_id=1, pps_id=1)
    // so they don't conflict with the camera's SPS/PPS (sps_id=0, pps_id=0).
    // =====================================================================

    /**
     * Encode a bitmap as a single H.264 IDR frame using MediaCodec encoder.
     * Returns Triple(sps, pps, idr) with IDs rewritten to avoid conflict
     * with camera params, or null on failure.
     */
    private fun encodeFrameAsIdr(bitmap: Bitmap, width: Int, height: Int,
                                cameraSps: ByteArray? = null): Triple<ByteArray?, ByteArray, ByteArray>? {
        var encoder: MediaCodec? = null
        var inputSurface: android.view.Surface? = null
        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0) // all keyframes
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()

            // Draw bitmap onto encoder input surface
            val canvas = inputSurface.lockCanvas(null)
            canvas.drawBitmap(bitmap, null, Rect(0, 0, width, height), null)
            inputSurface.unlockCanvasAndPost(canvas)

            // Signal end of input
            encoder.signalEndOfInputStream()

            // Drain encoder output
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            var idr: ByteArray? = null
            val info = MediaCodec.BufferInfo()

            for (attempt in 0 until 100) {
                val outIdx = encoder.dequeueOutputBuffer(info, 100_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = encoder.outputFormat
                        val csd0 = outFmt.getByteBuffer("csd-0")
                        val csd1 = outFmt.getByteBuffer("csd-1")
                        if (csd0 != null) {
                            val bytes = ByteArray(csd0.remaining())
                            csd0.get(bytes)
                            val nals = parseNalUnits(bytes, 0, bytes.size)
                            for (nal in nals) {
                                if ((nal[0].toInt() and 0x1F) == 7) sps = nal
                            }
                        }
                        if (csd1 != null) {
                            val bytes = ByteArray(csd1.remaining())
                            csd1.get(bytes)
                            val nals = parseNalUnits(bytes, 0, bytes.size)
                            for (nal in nals) {
                                if ((nal[0].toInt() and 0x1F) == 8) pps = nal
                            }
                        }
                    }
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val outBuf = encoder.getOutputBuffer(outIdx)!!
                            outBuf.position(info.offset)
                            val data = ByteArray(info.size)
                            outBuf.get(data)

                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                // Some encoders send SPS+PPS as CODEC_CONFIG
                                val nals = parseNalUnits(data, 0, data.size)
                                for (nal in nals) {
                                    val type = nal[0].toInt() and 0x1F
                                    if (type == 7 && sps == null) sps = nal
                                    if (type == 8 && pps == null) pps = nal
                                }
                            } else {
                                // Frame data — may contain inline SPS/PPS + IDR
                                val nals = parseNalUnits(data, 0, data.size)
                                for (nal in nals) {
                                    val type = nal[0].toInt() and 0x1F
                                    if (type == 5 && idr == null) idr = nal
                                    if (type == 7 && sps == null) sps = nal
                                    if (type == 8 && pps == null) pps = nal
                                }
                            }
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }

            encoder.stop()
            encoder.release()
            encoder = null
            inputSurface.release()
            inputSurface = null

            if (sps == null || pps == null || idr == null) {
                Log.w(TAG, "Encoder output incomplete: sps=${sps != null} pps=${pps != null} idr=${idr != null}")
                return null
            }

            // Discard encoder SPS entirely — make encoder IDR use camera's SPS (id=0).
            // This avoids ALL SPS switching during playback (no decoder reconfiguration).
            // The IDR slice header is rewritten: frame_num expanded, POC field stripped.
            val encInfo = getSpsTimingInfo(sps)
            val camInfo = if (cameraSps != null) getSpsTimingInfo(cameraSps) else null
            Log.d(TAG, "Encoder SPS: frameNumBits=${encInfo?.frameNumBits}, " +
                "pocType=${encInfo?.pocType}, pocLsbBits=${encInfo?.pocLsbBits}")
            Log.d(TAG, "Camera SPS: frameNumBits=${camInfo?.frameNumBits}, " +
                "pocType=${camInfo?.pocType}, pocLsbBits=${camInfo?.pocLsbBits}")

            // PPS: pps_id=2, refs sps_id=0 (camera's SPS, not encoder's)
            val newPps = rewritePpsIds(pps, 2, 0)
            // IDR: pps_id=2, transplant slice header from encoder SPS to camera SPS
            val newIdr = rewriteSlicePpsId(idr, 2, encInfo, camInfo)

            Log.d(TAG, "Encoded thumbnail: PPS ${pps.size}->${newPps.size}B, " +
                "IDR ${idr.size}->${newIdr.size}B (no encoder SPS — using camera's)")
            // Return null for SPS (not needed — all frames use camera SPS)
            return Triple(null, newPps, newIdr)
        } catch (e: Exception) {
            Log.w(TAG, "Frame encoding failed: ${e.message}", e)
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { inputSurface?.release() } catch (_: Exception) {}
            return null
        }
    }

    // =====================================================================
    // NAL ID REWRITING — modify SPS/PPS/slice IDs in H.264 NAL units
    //
    // The encoder and camera both use sps_id=0, pps_id=0 by default.
    // We rewrite the encoder's output to sps_id=1, pps_id=1 so both
    // parameter sets can coexist in the MP4's avcC box.
    // =====================================================================

    /** Remove H.264 emulation prevention bytes (0x03) to get RBSP from NAL. */
    private fun removeEmulationPrevention(nal: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(nal.size)
        var i = 0
        while (i < nal.size) {
            if (i + 2 < nal.size &&
                nal[i] == 0.toByte() && nal[i + 1] == 0.toByte() && nal[i + 2] == 3.toByte()) {
                out.write(0)
                out.write(0)
                i += 3 // skip emulation prevention byte
            } else {
                out.write(nal[i].toInt() and 0xFF)
                i++
            }
        }
        return out.toByteArray()
    }

    /** Insert H.264 emulation prevention bytes to convert RBSP back to NAL. */
    private fun addEmulationPrevention(rbsp: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(rbsp.size + rbsp.size / 256)
        out.write(rbsp[0].toInt()) // NAL header byte (never needs prevention)
        var zeroCount = 0
        for (i in 1 until rbsp.size) {
            val b = rbsp[i].toInt() and 0xFF
            if (zeroCount >= 2 && b <= 3) {
                out.write(0x03)
                zeroCount = 0
            }
            out.write(b)
            zeroCount = if (b == 0) zeroCount + 1 else 0
        }
        return out.toByteArray()
    }

    /** Rewrite sps_id in SPS NAL. Layout: [header(8)][profile(8)][constraints(8)][level(8)][UE:sps_id]... */
    private fun rewriteSpsId(sps: ByteArray, newId: Int): ByteArray {
        val rbsp = removeEmulationPrevention(sps)
        val r = BitReader(rbsp)
        val w = BitWriter(rbsp.size + 4)
        // Copy NAL header + profile_idc + constraint_flags + level_idc = 32 bits
        for (i in 0 until 32) w.writeBit(r.readBit())
        r.readUE() // skip old sps_id
        w.writeUE(newId)
        copyRemainingBits(r, w, rbsp.size * 8)
        return addEmulationPrevention(w.toByteArray())
    }

    /** Rewrite pps_id and sps_id in PPS NAL. Layout: [header(8)][UE:pps_id][UE:sps_id]... */
    private fun rewritePpsIds(pps: ByteArray, newPpsId: Int, newSpsId: Int): ByteArray {
        val rbsp = removeEmulationPrevention(pps)
        val r = BitReader(rbsp)
        val w = BitWriter(rbsp.size + 4)
        // Copy NAL header = 8 bits
        for (i in 0 until 8) w.writeBit(r.readBit())
        r.readUE(); w.writeUE(newPpsId)  // pps_id
        r.readUE(); w.writeUE(newSpsId)  // sps_id
        copyRemainingBits(r, w, rbsp.size * 8)
        return addEmulationPrevention(w.toByteArray())
    }

    /** Rewrite pps_id in IDR slice header. Layout: [header(8)][UE:first_mb][UE:slice_type][UE:pps_id]... */
    /**
     * Rewrite IDR slice header to transplant it from encoder's SPS to camera's SPS.
     * - Replaces pps_id
     * - Expands frame_num from encoder's bit width to camera's
     * - Strips POC field if encoder has poc_type=0 but camera has poc_type=2
     */
    private fun rewriteSlicePpsId(idr: ByteArray, newPpsId: Int,
                                   encoderInfo: SpsTimingInfo? = null,
                                   cameraInfo: SpsTimingInfo? = null): ByteArray {
        val rbsp = removeEmulationPrevention(idr)
        val r = BitReader(rbsp)
        val w = BitWriter(rbsp.size + 16)
        // Copy NAL header = 8 bits
        for (i in 0 until 8) w.writeBit(r.readBit())
        // Copy first_mb_in_slice and slice_type verbatim
        val firstMb = r.readUE(); w.writeUE(firstMb)
        val sliceType = r.readUE(); w.writeUE(sliceType)
        // Replace pps_id
        r.readUE(); w.writeUE(newPpsId)
        if (encoderInfo != null && cameraInfo != null) {
            // Expand frame_num from encoder's bit width to camera's
            val frameNum = r.readBits(encoderInfo.frameNumBits)
            w.writeBits(frameNum, cameraInfo.frameNumBits)
            // Copy idr_pic_id (UE) — present for NAL type 5
            val idrPicId = r.readUE(); w.writeUE(idrPicId)
            // Handle POC field: strip if encoder has it but camera doesn't
            if (encoderInfo.pocType == 0 && cameraInfo.pocType == 2) {
                // Encoder wrote pic_order_cnt_lsb — read and discard
                r.readBits(encoderInfo.pocLsbBits)
            } else if (encoderInfo.pocType == 0 && cameraInfo.pocType == 0) {
                // Both have POC LSB — expand/copy
                val pocLsb = r.readBits(encoderInfo.pocLsbBits)
                w.writeBits(pocLsb, cameraInfo.pocLsbBits)
            }
            // poc_type=2 has no POC field — nothing to read or write
        }
        copyRemainingBits(r, w, rbsp.size * 8)
        return addEmulationPrevention(w.toByteArray())
    }

    /** Copy remaining bits from BitReader to BitWriter. */
    private fun copyRemainingBits(r: BitReader, w: BitWriter, totalBits: Int) {
        while (r.bitPosition() < totalBits) {
            try { w.writeBit(r.readBit()) } catch (_: IndexOutOfBoundsException) { break }
        }
    }

    /** Bit-level writer for H.264 NAL construction. */
    private class BitWriter(initialCapacity: Int = 1024) {
        private val bytes = java.io.ByteArrayOutputStream(initialCapacity)
        private var currentByte = 0
        private var bitCount = 0

        fun writeBit(v: Int) {
            currentByte = (currentByte shl 1) or (v and 1)
            if (++bitCount == 8) {
                bytes.write(currentByte)
                currentByte = 0
                bitCount = 0
            }
        }

        fun writeBits(value: Int, n: Int) {
            for (i in n - 1 downTo 0) writeBit((value shr i) and 1)
        }

        fun writeUE(value: Int) {
            val v = value + 1
            val bits = 32 - Integer.numberOfLeadingZeros(v)
            for (i in 0 until bits - 1) writeBit(0) // leading zeros
            writeBits(v, bits)
        }

        fun writeSE(value: Int) {
            writeUE(if (value <= 0) -2 * value else 2 * value - 1)
        }

        fun byteAlign() {
            while (bitCount != 0) writeBit(0)
        }

        fun writeBytes(data: ByteArray, offset: Int, length: Int) {
            if (bitCount != 0) throw IllegalStateException("Not byte-aligned for writeBytes")
            bytes.write(data, offset, length)
        }

        fun toByteArray(): ByteArray {
            if (bitCount > 0) {
                bytes.write(currentByte shl (8 - bitCount))
                currentByte = 0
                bitCount = 0
            }
            return bytes.toByteArray()
        }
    }

    private class BitReader(private val data: ByteArray) {
        private var bytePos = 0
        private var bitPos = 0

        fun bitPosition(): Int = bytePos * 8 + bitPos

        fun readBit(): Int {
            if (bytePos >= data.size) throw IndexOutOfBoundsException("SPS too short")
            val bit = (data[bytePos].toInt() shr (7 - bitPos)) and 1
            if (++bitPos == 8) { bitPos = 0; bytePos++ }
            return bit
        }

        fun readBits(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or readBit() }
            return v
        }

        fun skip(n: Int) = repeat(n) { readBit() }

        fun readUE(): Int {
            var zeros = 0
            while (readBit() == 0) zeros++
            return if (zeros == 0) 0 else (1 shl zeros) - 1 + readBits(zeros)
        }

        fun readSE(): Int {
            val ue = readUE()
            return if (ue % 2 == 0) -(ue / 2) else (ue + 1) / 2
        }
    }
}
