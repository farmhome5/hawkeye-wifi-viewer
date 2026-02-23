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

                // Parse resolution from SPS
                val spsDims = parseSpsResolution(sps)
                val vidW: Int
                val vidH: Int
                if (spsDims != null) {
                    vidW = spsDims.first
                    vidH = spsDims.second
                    Log.d(TAG, "Recording resolution: ${vidW}x${vidH}")
                } else {
                    vidW = width
                    vidH = height
                    Log.w(TAG, "SPS parse failed — using ${width}x${height}")
                }

                // 2. Init direct muxer (no decoder/encoder needed)
                try {
                    initDirectMux(sps, pps, vidW, vidH)
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

                // 3. Set data listener to start receiving NALs from live view
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

    private fun initDirectMux(sps: ByteArray, pps: ByteArray, width: Int, height: Int) {
        val sc = byteArrayOf(0, 0, 0, 1)
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, width, height
        ).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(sc + sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(sc + pps))
        }
        muxer = MediaMuxer(recordingOutputPath!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        videoTrackIndex = muxer!!.addTrack(format)
        muxer!!.start()
        muxerStarted = true
        Log.d(TAG, "Direct muxer started: ${width}x${height}")
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
     * Write a single NAL unit to the muxer. The Q2 camera sends all-intra frames
     * labeled as NAL type 1 (non-IDR). We rewrite type 1 → 5 (IDR) so gallery
     * apps can generate video thumbnails. BUFFER_FLAG_KEY_FRAME marks every
     * frame as a sync sample for seeking.
     *
     * IMPORTANT: NAL payload must be copied into a clean byte array first.
     * ByteBuffer.wrap(array, offset, len) with BufferInfo.offset=0 causes
     * MediaMuxer to read from array start instead of buffer position,
     * corrupting the last macroblocks (lower-right corner).
     */
    private fun muxNalUnit(data: ByteArray, offset: Int, length: Int, pts: Long) {
        if (!muxerStarted) return

        // Find start of NAL payload (skip 00 00 00 01 or 00 00 01)
        val payloadStart = findNalPayloadStart(data, offset, length)
        val payloadLen = length - (payloadStart - offset)
        if (payloadLen <= 0) return

        // Copy NAL payload into a clean array — ByteBuffer.wrap(array, offset, len)
        // is unreliable with MediaMuxer because BufferInfo.offset=0 can cause it to
        // read from array start (the start code) instead of the buffer position
        val nalPayload = ByteArray(payloadLen)
        System.arraycopy(data, payloadStart, nalPayload, 0, payloadLen)

        // Rewrite NAL type: 1 (non-IDR) → 5 (IDR) so gallery can generate thumbnails
        // (Q2 camera sends all-intra frames labeled as type 1)
        val nalHeader = nalPayload[0].toInt() and 0xFF
        val nalType = nalHeader and 0x1F
        if (nalType == 1) {
            nalPayload[0] = ((nalHeader and 0xE0.toInt()) or 5).toByte()
        }

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

    /**
     * Find the byte offset where the NAL payload starts (after start code).
     * Handles both 3-byte (00 00 01) and 4-byte (00 00 00 01) start codes.
     */
    private fun findNalPayloadStart(data: ByteArray, offset: Int, length: Int): Int {
        if (length >= 4 && data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
            data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()) {
            return offset + 4
        }
        if (length >= 3 && data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
            data[offset + 2] == 1.toByte()) {
            return offset + 3
        }
        return offset // No start code found — assume raw NAL
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/$subfolder")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return null

            context.contentResolver.openOutputStream(uri)?.use { out ->
                tempFile.inputStream().use { it.copyTo(out) }
            }
            Log.d(TAG, "Video saved via MediaStore: DCIM/$subfolder/$filename")
            return "DCIM/$subfolder/$filename"
        } else {
            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val dir = File(dcim, subfolder)
            dir.mkdirs()
            val destFile = File(dir, filename)
            tempFile.copyTo(destFile, overwrite = true)
            MediaScannerConnection.scanFile(
                context, arrayOf(destFile.absolutePath), arrayOf("video/mp4"), null
            )
            Log.d(TAG, "Video saved to file: ${destFile.absolutePath}")
            return destFile.absolutePath
        }
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

    private class BitReader(private val data: ByteArray) {
        private var bytePos = 0
        private var bitPos = 0

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
