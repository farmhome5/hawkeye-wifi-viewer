package com.hawkeye.wifi.viewer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import com.alexvas.rtsp.RtspClient
import com.alexvas.utils.NetUtils
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

interface CaptureCallback {
    fun onPhotoSaved(path: String)
    fun onRecordingStopped(path: String, durationMs: Long)
    fun onCaptureError(message: String)
}

class CaptureHelper(private val context: Context) {

    companion object {
        private const val TAG = "CaptureHelper"
        private const val ENCODER_BITRATE = 4_000_000 // 4 Mbps
        private const val ENCODER_FRAME_RATE = 10
        private const val ENCODER_IDR_INTERVAL = 1 // IDR every 1 second
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

    // Transcode pipeline: RTSP NALs → decoder → (Surface) → encoder → muxer
    private var decoder: MediaCodec? = null
    private var encoder: MediaCodec? = null
    private var encoderInputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var pipelineReady = false

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
    // VIDEO RECORDING — MediaCodec transcode pipeline
    //
    // Camera sends all-intra H.264 with NAL type 1 (non-IDR), which MP4
    // players can't decode from a cold start. We decode the camera's NALs
    // through MediaCodec, then re-encode through a hardware encoder that
    // produces proper IDR frames, and mux the encoder output to MP4.
    // =====================================================================

    fun startRecording(rtspUrl: String, wifiName: String, width: Int = 400, height: Int = 400) {
        if (isRecording) {
            postError("Already recording")
            return
        }

        recordStopped.set(false)
        recordingWifiName = wifiName
        pipelineReady = false
        muxerStarted = false
        videoTrackIndex = -1
        startNanos = 0L
        frameCount = 0

        val tempFile = File(context.cacheDir, "recording_${System.currentTimeMillis()}.mp4")
        recordingOutputPath = tempFile.absolutePath

        val listener = object : RtspClient.RtspClientListener {
            override fun onRtspConnecting() {
                Log.d(TAG, "Recording RTSP: connecting")
            }

            override fun onRtspConnected(sdpInfo: RtspClient.SdpInfo) {
                Log.d(TAG, "Recording RTSP: connected, videoTrack=${sdpInfo.videoTrack}")
                val sps = sdpInfo.videoTrack?.sps
                val pps = sdpInfo.videoTrack?.pps
                if (sps == null || pps == null) {
                    Log.e(TAG, "No SPS/PPS in SDP — cannot record")
                    postError("No video parameters from camera")
                    recordStopped.set(true)
                    return
                }

                val rawSps = stripStartCode(sps)
                val rawPps = stripStartCode(pps)
                val spsDims = parseSpsResolution(rawSps)
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

                try {
                    initTranscodePipeline(rawSps, rawPps, vidW, vidH)
                    recordingStartTimeMs = System.currentTimeMillis()
                } catch (e: Exception) {
                    Log.e(TAG, "Pipeline init failed", e)
                    postError("Recording init failed: ${e.message}")
                    recordStopped.set(true)
                }
            }

            override fun onRtspVideoNalUnitReceived(
                data: ByteArray, offset: Int, length: Int, timestamp: Long
            ) {
                if (!pipelineReady || recordStopped.get()) return
                if (length <= 4) return

                if (startNanos == 0L) startNanos = System.nanoTime()
                val pts = (System.nanoTime() - startNanos) / 1000 // μs

                frameCount++

                // Feed raw data directly (with original start codes) — same as library
                feedDecoderAndDrain(data, offset, length, pts)
            }

            override fun onRtspAudioSampleReceived(
                data: ByteArray, offset: Int, length: Int, timestamp: Long
            ) {}

            override fun onRtspApplicationDataReceived(
                data: ByteArray, offset: Int, length: Int, timestamp: Long
            ) {}

            override fun onRtspDisconnecting() {
                Log.d(TAG, "Recording RTSP: disconnecting")
            }

            override fun onRtspDisconnected() {
                Log.d(TAG, "Recording RTSP: disconnected")
                finalizeRecording()
            }

            override fun onRtspFailedUnauthorized() {
                Log.e(TAG, "Recording RTSP: unauthorized")
                postError("Recording connection unauthorized")
                finalizeRecording()
            }

            override fun onRtspFailed(message: String?) {
                Log.e(TAG, "Recording RTSP: failed — $message")
                postError("Recording connection failed: $message")
                finalizeRecording()
            }
        }

        recordThread = Thread({
            try {
                val uri = Uri.parse(rtspUrl)
                val host = uri.host ?: return@Thread
                val port = if (uri.port > 0) uri.port else 554
                val socket = NetUtils.createSocketAndConnect(host, port, 5000)
                val client = RtspClient.Builder(socket, rtspUrl, recordStopped, listener)
                    .requestVideo(true)
                    .requestAudio(false)
                    .withDebug(false)
                    .withUserAgent("HawkEye Recorder")
                    .build()
                client.execute()
                NetUtils.closeSocket(socket)
            } catch (e: Exception) {
                Log.e(TAG, "Recording thread error", e)
                postError("Recording failed: ${e.message}")
                finalizeRecording()
            }
        }, "RtspRecorder")
        recordThread!!.start()
        Log.d(TAG, "Recording started: $rtspUrl → $recordingOutputPath")
    }

    private fun initTranscodePipeline(sps: ByteArray, pps: ByteArray, width: Int, height: Int) {
        // 1. Create encoder — produces proper H.264 with real IDR frames
        val encoderFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, width, height
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, ENCODER_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, ENCODER_FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, ENCODER_IDR_INTERVAL)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { enc ->
            enc.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderInputSurface = enc.createInputSurface()
            enc.start()
        }
        Log.d(TAG, "Encoder started: ${width}x${height}, ${ENCODER_BITRATE / 1000}kbps")

        // 2. Create decoder — outputs decoded frames to encoder's input Surface.
        // Must set low-latency options (same as rtsp-client-android library)
        // to allow the decoder to start from non-IDR (type 1) NALs.
        val sc = byteArrayOf(0, 0, 0, 1)
        val decoderFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, width, height
        ).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(sc + sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(sc + pps))
            // Low-latency mode (Android 11+) — output frames immediately
            try { setInteger("low-latency", 1) } catch (_: Exception) {}
            // Qualcomm: max operating rate + realtime priority
            try { setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt()) } catch (_: Exception) {}
            try { setInteger(MediaFormat.KEY_PRIORITY, 0) } catch (_: Exception) {}
        }
        val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        dec.configure(decoderFormat, encoderInputSurface, null, 0)

        // Qualcomm vendor extensions for low-latency decode
        try {
            val params = android.os.Bundle()
            params.putInt("vendor.qti-ext-dec-picture-order.enable", 1)
            params.putInt("vendor.qti-ext-dec-low-latency.enable", 1)
            dec.setParameters(params)
            Log.d(TAG, "Set Qualcomm low-latency decoder params")
        } catch (e: Exception) {
            Log.d(TAG, "Qualcomm params not supported: ${e.message}")
        }

        dec.start()
        decoder = dec
        Log.d(TAG, "Decoder started with low-latency options")

        // 3. Create muxer — started later when encoder outputs its format
        muxer = MediaMuxer(recordingOutputPath!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        Log.d(TAG, "Muxer created: $recordingOutputPath")

        pipelineReady = true
    }

    private fun feedDecoderAndDrain(data: ByteArray, offset: Int, length: Int, pts: Long) {
        val dec = decoder ?: return

        // Feed raw data to decoder exactly as received (with original start codes)
        val inputIndex = dec.dequeueInputBuffer(10_000) // 10ms timeout
        if (inputIndex >= 0) {
            val inputBuf = dec.getInputBuffer(inputIndex)!!
            inputBuf.clear()
            inputBuf.put(data, offset, length)
            dec.queueInputBuffer(inputIndex, 0, length, pts, 0)
        } else {
            Log.w(TAG, "Decoder input full (frame $frameCount)")
        }

        // Drain decoder — wait for output then render to encoder's input Surface.
        // Hardware decoder needs a few ms to process each frame. Use a small
        // timeout to give it time, then drain any remaining non-blocking.
        val decInfo = MediaCodec.BufferInfo()
        for (attempt in 0..3) {
            val outIdx = dec.dequeueOutputBuffer(decInfo, 8_000) // 8ms timeout
            if (outIdx >= 0) {
                val render = decInfo.size > 0
                dec.releaseOutputBuffer(outIdx, render)
                // Drain any additional ready frames (non-blocking)
                while (true) {
                    val moreIdx = dec.dequeueOutputBuffer(decInfo, 0)
                    if (moreIdx >= 0) {
                        dec.releaseOutputBuffer(moreIdx, decInfo.size > 0)
                    } else break
                }
                break
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.d(TAG, "Decoder output format changed: ${dec.outputFormat}")
                continue
            }
        }

        // Drain encoder — write encoded frames to muxer
        drainEncoder(false)
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val enc = encoder ?: return
        val mux = muxer ?: return

        if (endOfStream) {
            enc.signalEndOfInputStream()
        }

        val encInfo = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = enc.dequeueOutputBuffer(encInfo, if (endOfStream) 10_000 else 0)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = enc.outputFormat
                    videoTrackIndex = mux.addTrack(fmt)
                    mux.start()
                    muxerStarted = true
                    Log.d(TAG, "Muxer started with encoder format")
                }
                outIdx >= 0 -> {
                    // Skip codec config buffers (SPS/PPS) — already in muxer track
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        enc.releaseOutputBuffer(outIdx, false)
                        continue
                    }
                    if (muxerStarted && encInfo.size > 0) {
                        val encodedBuf = enc.getOutputBuffer(outIdx)!!
                        encodedBuf.position(encInfo.offset)
                        encodedBuf.limit(encInfo.offset + encInfo.size)
                        mux.writeSampleData(videoTrackIndex, encodedBuf, encInfo)
                    }
                    val isEos = encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    enc.releaseOutputBuffer(outIdx, false)
                    if (isEos) return
                }
                else -> {
                    if (endOfStream) {
                        // Keep trying until we get EOS
                        Thread.sleep(5)
                        continue
                    }
                    break
                }
            }
        }
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
            // Flush decoder — send EOS and drain remaining frames
            val dec = decoder
            if (dec != null && pipelineReady) {
                try {
                    val eosIdx = dec.dequeueInputBuffer(5_000)
                    if (eosIdx >= 0) {
                        dec.queueInputBuffer(eosIdx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                    val decInfo = MediaCodec.BufferInfo()
                    var draining = true
                    while (draining) {
                        val outIdx = dec.dequeueOutputBuffer(decInfo, 10_000)
                        if (outIdx >= 0) {
                            val eos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            dec.releaseOutputBuffer(outIdx, decInfo.size > 0)
                            if (eos) draining = false
                        } else {
                            draining = false
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Decoder flush error", e)
                }
            }

            // Flush encoder — drain remaining frames and signal EOS to muxer
            if (pipelineReady) {
                try {
                    drainEncoder(true)
                } catch (e: Exception) {
                    Log.w(TAG, "Encoder flush error", e)
                }
            }

            // Release codec resources
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            decoder = null

            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            encoder = null

            encoderInputSurface?.release()
            encoderInputSurface = null

            if (muxerStarted) {
                try { muxer?.stop() } catch (_: Exception) {}
                try { muxer?.release() } catch (_: Exception) {}
                muxer = null
                muxerStarted = false
                pipelineReady = false

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
                muxer?.release()
                muxer = null
                pipelineReady = false
                File(outputPath).delete()
                Log.w(TAG, "Muxer was never started — no video saved")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording finalize error", e)
            pipelineReady = false
        }
        recordingOutputPath = null
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
            recordThread?.join(3000)
        }
        try { decoder?.stop() } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        decoder = null
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        encoderInputSurface?.release()
        encoderInputSurface = null
        if (muxerStarted) {
            try { muxer?.stop() } catch (_: Exception) {}
        }
        try { muxer?.release() } catch (_: Exception) {}
        muxer = null
        muxerStarted = false
        pipelineReady = false
        recordingOutputPath?.let { File(it).delete() }
        recordingOutputPath = null
        callback = null
    }

    // =====================================================================
    // UTILITIES
    // =====================================================================

    private fun findNalUnitStart(data: ByteArray, offset: Int, length: Int, maxScan: Int = 32): Int {
        val end = offset + minOf(length, maxScan)
        var lastNalStart = -1
        var i = offset
        while (i < end - 2) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                lastNalStart = i + 3
                i = lastNalStart
            } else {
                i++
            }
        }
        return if (lastNalStart >= 0) lastNalStart else offset
    }

    private fun stripStartCode(data: ByteArray): ByteArray {
        val nalStart = findNalUnitStart(data, 0, data.size)
        return if (nalStart > 0) data.copyOfRange(nalStart, data.size) else data
    }

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
