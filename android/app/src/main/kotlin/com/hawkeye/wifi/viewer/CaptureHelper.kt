package com.hawkeye.wifi.viewer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.alexvas.rtsp.RtspClient
import com.alexvas.rtsp.RtspClient.SdpInfo
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

    // NAL queue: headless RtspClient thread → muxing thread
    private data class NalUnit(val data: ByteArray, val timestamp: Long)
    private var nalQueue: ArrayBlockingQueue<NalUnit>? = null
    // Headless RtspClient for recording (second RTSP connection)
    private var rtspClientSocket: Socket? = null
    private var rtspClientStopped: AtomicBoolean? = null
    private var rtspClientThread: Thread? = null

    // PixelCopy cover art: captured at recording start, used in embedCoverArt
    private var coverArtBitmap: Bitmap? = null

    // Direct muxing (no transcode — camera sends all-intra frames)
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var muxSps: ByteArray? = null  // SPS for inline prepend on first frame
    private var muxPps: ByteArray? = null  // PPS for inline prepend on first frame
    private var wroteFirstFrame = false

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

    fun startRecording(surfaceView: SurfaceView, rtspUrl: String, wifiName: String, width: Int = 400, height: Int = 400) {
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
        nalQueue = ArrayBlockingQueue(NAL_QUEUE_CAPACITY)
        coverArtBitmap = null

        // Capture a frame from the live SurfaceView for cover art thumbnail
        val sw = surfaceView.width
        val sh = surfaceView.height
        val thumbnailLatch = java.util.concurrent.CountDownLatch(1)
        if (sw > 0 && sh > 0) {
            val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            PixelCopy.request(surfaceView, bmp, { result ->
                if (result == PixelCopy.SUCCESS) {
                    coverArtBitmap = bmp
                    Log.d(TAG, "Cover art captured: ${sw}x${sh}")
                } else {
                    bmp.recycle()
                    Log.w(TAG, "PixelCopy for cover art failed (code $result)")
                }
                thumbnailLatch.countDown()
            }, uiHandler)
        } else {
            Log.w(TAG, "Surface dimensions unavailable for cover art")
            thumbnailLatch.countDown()
        }
        // Wait briefly — PixelCopy is fast (~1 frame)
        thumbnailLatch.await(500, TimeUnit.MILLISECONDS)

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

                // 3. Init direct muxer
                if (recordStopped.get()) return@Thread
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

                // 4. Start headless RtspClient (second RTSP connection for recording)
                val uri = android.net.Uri.parse(rtspUrl)
                val host = uri.host ?: throw Exception("No host in RTSP URL")
                val port = if (uri.port > 0) uri.port else 554

                rtspClientStopped = AtomicBoolean(false)
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 5000)
                socket.soTimeout = 10000
                rtspClientSocket = socket

                val clientStopped = rtspClientStopped!!
                val client = RtspClient.Builder(socket, rtspUrl, clientStopped, rtspClientListener)
                    .requestVideo(true)
                    .requestAudio(false)
                    .withDebug(false)
                    .build()

                rtspClientThread = Thread({
                    try {
                        client.execute() // Blocks until stopped or error
                    } catch (e: Exception) {
                        if (!clientStopped.get()) {
                            Log.e(TAG, "RtspClient error: ${e.message}")
                        }
                    } finally {
                        try { socket.close() } catch (_: Exception) {}
                    }
                }, "RecordRtspClient")
                rtspClientThread!!.start()
                Log.d(TAG, "Headless RtspClient started for recording")

                // 5. Consume NALs from queue and mux directly to MP4
                while (!recordStopped.get()) {
                    val nal = nalQueue?.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    if (startNanos == 0L) startNanos = System.nanoTime()
                    val pts = (System.nanoTime() - startNanos) / 1000 // μs
                    frameCount++
                    muxNalUnit(nal.data, 0, nal.data.size, pts)
                }

                // 6. Stop headless RtspClient and finalize
                stopRtspClient()
                finalizeRecording()

            } catch (e: Exception) {
                Log.e(TAG, "Recording thread error", e)
                stopRtspClient()
                finalizeRecording()
            }
        }, "RecordMux")
        recordThread!!.start()
        Log.d(TAG, "Recording started → $recordingOutputPath")
    }

    private val rtspClientListener = object : RtspClient.RtspClientListener {
        override fun onRtspConnecting() {
            Log.d(TAG, "Recording RtspClient: connecting")
        }
        override fun onRtspConnected(sdpInfo: SdpInfo) {
            Log.d(TAG, "Recording RtspClient: connected")
        }
        override fun onRtspVideoNalUnitReceived(
            data: ByteArray, offset: Int, length: Int, timestamp: Long
        ) {
            if (recordStopped.get()) return
            if (length <= 4) return
            // Copy data (library may reuse buffer) and enqueue
            val copy = ByteArray(length)
            System.arraycopy(data, offset, copy, 0, length)
            nalQueue?.offer(NalUnit(copy, timestamp)) // non-blocking, drops if full
        }
        override fun onRtspAudioSampleReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {}
        override fun onRtspApplicationDataReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {}
        override fun onRtspDisconnecting() {
            Log.d(TAG, "Recording RtspClient: disconnecting")
        }
        override fun onRtspDisconnected() {
            Log.d(TAG, "Recording RtspClient: disconnected")
        }
        override fun onRtspFailedUnauthorized() {
            Log.e(TAG, "Recording RtspClient: unauthorized")
        }
        override fun onRtspFailed(message: String?) {
            Log.e(TAG, "Recording RtspClient: failed - $message")
        }
    }

    private fun stopRtspClient() {
        rtspClientStopped?.set(true)
        try { rtspClientSocket?.close() } catch (_: Exception) {}
        rtspClientThread?.join(3000)
        rtspClientSocket = null
        rtspClientStopped = null
        rtspClientThread = null
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
        val csd0 = sc + sps
        val csd1 = sc + pps

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
        wroteFirstFrame = false
        muxSps = sps
        muxPps = pps
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

            // Prepend SPS+PPS inline before the first video frame so the
            // decoder has explicit parameter sets at the sync point.
            // Some decoders (tablet) can't cold-start from csd-0/csd-1 alone
            // when all frames are non-IDR (NAL type 1).
            val sampleData: ByteArray
            if (!wroteFirstFrame && muxSps != null && muxPps != null) {
                wroteFirstFrame = true
                // Use Annex B start codes so MediaMuxer converts each NAL
                // to a separate AVCC length-prefixed entry in the sample
                val sc = byteArrayOf(0, 0, 0, 1)
                sampleData = sc + muxSps!! + sc + muxPps!! + sc + nalPayload
                Log.d(TAG, "First frame: SC+SPS(${muxSps!!.size})+SC+PPS(${muxPps!!.size})+SC+NAL(${nalPayload.size}) = ${sampleData.size}B")
            } else {
                sampleData = nalPayload
            }

            val buf = ByteBuffer.wrap(sampleData)
            val info = MediaCodec.BufferInfo().apply {
                this.offset = 0
                this.size = sampleData.size
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
        nalQueue = null
    }

    private fun saveVideoToGallery(tempPath: String, wifiName: String): String? {
        val filename = "VID_${System.currentTimeMillis()}.mp4"
        val subfolder = sanitizeFolderName(wifiName)
        val tempFile = File(tempPath)

        // Save thumbnail JPEG bytes before embedCoverArt recycles the bitmap
        var thumbnailJpeg: ByteArray? = null
        val artBmp = coverArtBitmap
        if (artBmp != null) {
            val stream = java.io.ByteArrayOutputStream()
            artBmp.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            thumbnailJpeg = stream.toByteArray()
        }

        // Embed cover art JPEG in MP4 metadata BEFORE copying to MediaStore
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
                context, arrayOf(destFile.absolutePath), arrayOf("video/mp4")
            ) { _, uri ->
                // After scan completes, insert custom thumbnail into MediaStore
                if (uri != null && thumbnailJpeg != null) {
                    insertMediaStoreThumbnail(uri, thumbnailJpeg, destFile.absolutePath)
                }
            }
        }

        return galleryPath
    }

    /**
     * Insert a custom thumbnail into MediaStore for a video (API <29 only).
     * Samsung Gallery on older devices uses MediaStore thumbnails instead of cover art.
     */
    @Suppress("DEPRECATION")
    private fun insertMediaStoreThumbnail(videoUri: android.net.Uri, jpegBytes: ByteArray, videoPath: String) {
        try {
            // Query the video's _ID from the content URI
            val videoId = android.content.ContentUris.parseId(videoUri)
            if (videoId <= 0) {
                Log.w(TAG, "Could not get video ID from URI: $videoUri")
                return
            }

            // Write thumbnail JPEG to a file in the thumbnails directory
            val thumbDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                ".thumbnails"
            )
            thumbDir.mkdirs()
            val thumbFile = File(thumbDir, "thumb_${videoId}.jpg")
            FileOutputStream(thumbFile).use { it.write(jpegBytes) }

            // Insert into MediaStore.Video.Thumbnails
            val values = ContentValues().apply {
                put(MediaStore.Video.Thumbnails.DATA, thumbFile.absolutePath)
                put(MediaStore.Video.Thumbnails.VIDEO_ID, videoId)
                put(MediaStore.Video.Thumbnails.KIND, MediaStore.Video.Thumbnails.MINI_KIND)
            }
            context.contentResolver.insert(
                MediaStore.Video.Thumbnails.EXTERNAL_CONTENT_URI, values
            )
            Log.d(TAG, "Inserted MediaStore thumbnail for video ID $videoId: ${thumbFile.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore thumbnail insertion failed: ${e.message}")
        }
    }

    /**
     * Embed cover art as MP4 iTunes metadata (moov/udta/meta/ilst/covr atom).
     * Uses PixelCopy frame captured at recording start if available, falls back to Q2.png.
     */
    private fun embedCoverArt(mp4Path: String) {
        try {
            val jpegStream = java.io.ByteArrayOutputStream()
            val artBitmap = coverArtBitmap
            if (artBitmap != null) {
                artBitmap.compress(Bitmap.CompressFormat.JPEG, 90, jpegStream)
                artBitmap.recycle()
                coverArtBitmap = null
                Log.d(TAG, "Using PixelCopy frame as cover art")
            } else {
                val logoBitmap = BitmapFactory.decodeStream(
                    context.assets.open("Q2.png")
                )
                if (logoBitmap == null) {
                    Log.w(TAG, "Failed to decode Q2.png from assets")
                    return
                }
                logoBitmap.compress(Bitmap.CompressFormat.JPEG, 90, jpegStream)
                logoBitmap.recycle()
                Log.d(TAG, "Using Q2.png fallback as cover art")
            }
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
            stopRtspClient()
            recordThread?.join(3000)
        }
        cleanupMuxer()
        recordingOutputPath?.let { File(it).delete() }
        recordingOutputPath = null
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
