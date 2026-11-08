package com.acegallery.live

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Decodes the camera's raw live preview stream (Annex-B H.264/H.265) onto a Surface.
 * Feed packets in order from [com.acegallery.session.CameraGateway.livePackets].
 */
class LiveViewDecoder(
    private val surface: Surface,
    private val onVideoSize: ((width: Int, height: Int) -> Unit)? = null,
) {

    private var codec: MediaCodec? = null
    private var mime: String? = null
    private val parameterBuffer = ByteArrayOutputStream()
    private var hasVps = false
    private var hasSps = false
    private var hasPps = false
    private val frameBuffer = ByteArrayOutputStream()
    private var hasVcl = false
    private var ptsUs = 0L
    private var released = false
    private var reportedSize = false

    fun feed(packet: ByteArray) {
        if (released || packet.isEmpty()) return
        val nals = splitNals(packet)
        if (nals.isEmpty()) return

        if (mime == null) {
            mime = detectMime(nals)
        }
        val currentMime = mime ?: return

        for (nal in nals) {
            if (nal.isEmpty()) continue
            val type = nalType(nal, currentMime)
            if (isParameter(type, currentMime)) {
                if (codec == null) {
                    appendParameter(nal, type, currentMime)
                    configureIfReady(currentMime)
                }
                continue
            }
            if (!isVcl(type, currentMime)) continue

            val firstSlice = isFirstSlice(nal, currentMime)
            if (firstSlice && hasVcl) {
                flushFrame()
            }
            appendNal(frameBuffer, nal)
            hasVcl = true
        }
    }

    private fun configureIfReady(currentMime: String) {
        if (codec != null) return
        val ready = if (currentMime == "video/hevc") {
            hasVps && hasSps && hasPps
        } else {
            hasSps && hasPps
        }
        if (!ready) return
        runCatching {
            val format = MediaFormat.createVideoFormat(currentMime, 1920, 1080)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(parameterBuffer.toByteArray()))
            val created = MediaCodec.createDecoderByType(currentMime)
            created.configure(format, surface, null, 0)
            created.start()
            codec = created
            Log.i(TAG, "Live decoder started: $currentMime")
        }.onFailure {
            Log.w(TAG, "Cannot configure live decoder", it)
            mime = null
        }
    }

    private fun flushFrame() {
        if (!hasVcl) return
        val frame = frameBuffer.toByteArray()
        frameBuffer.reset()
        hasVcl = false
        if (frame.isEmpty()) return
        val c = codec ?: return
        runCatching {
            val index = c.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val input = c.getInputBuffer(index) ?: return@runCatching
                input.clear()
                input.put(frame)
                c.queueInputBuffer(index, 0, frame.size, ptsUs, 0)
                ptsUs += 33_333
            }
            drain(c)
        }.onFailure { Log.w(TAG, "Live frame decode failed", it) }
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val index = c.dequeueOutputBuffer(info, 0)) {
                in 0..Int.MAX_VALUE -> {
                    reportVideoSize(c)
                    c.releaseOutputBuffer(index, true)
                }

                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> return
            }
        }
    }

    /** Reads the decoder's real output size once; a format failure must never break decoding. */
    private fun reportVideoSize(c: MediaCodec) {
        if (reportedSize) return
        runCatching {
            val format = c.outputFormat
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            if (width > 0 && height > 0) {
                reportedSize = true
                onVideoSize?.invoke(width, height)
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
        frameBuffer.reset()
        parameterBuffer.reset()
    }

    private fun appendParameter(nal: ByteArray, type: Int, currentMime: String) {
        when {
            currentMime == "video/hevc" && type == 32 -> hasVps = true
            currentMime == "video/hevc" && type == 33 -> hasSps = true
            currentMime == "video/hevc" && type == 34 -> hasPps = true
            currentMime == "video/avc" && type == 7 -> hasSps = true
            currentMime == "video/avc" && type == 8 -> hasPps = true
        }
        appendNal(parameterBuffer, nal)
    }

    private fun appendNal(out: ByteArrayOutputStream, nal: ByteArray) {
        out.write(START_CODE)
        out.write(nal)
    }

    private fun detectMime(nals: List<ByteArray>): String? {
        for (nal in nals) {
            if (nal.isEmpty()) continue
            val avcType = nal[0].toInt() and 0x1F
            val hevcType = (nal[0].toInt() and 0xFF) shr 1
            if (avcType == 7 || avcType == 8) return "video/avc"
            if (hevcType in 32..34) return "video/hevc"
        }
        return null
    }

    private fun nalType(nal: ByteArray, mime: String): Int =
        if (mime == "video/hevc") {
            (nal[0].toInt() and 0xFF) shr 1
        } else {
            nal[0].toInt() and 0x1F
        }

    private fun isParameter(type: Int, mime: String): Boolean =
        if (mime == "video/hevc") type in 32..34 else type == 7 || type == 8

    private fun isVcl(type: Int, mime: String): Boolean =
        if (mime == "video/hevc") type in 0..31 else type in 1..5

    private fun isFirstSlice(nal: ByteArray, mime: String): Boolean =
        if (mime == "video/hevc") {
            nal.size > 2 && (nal[2].toInt() and 0x80) != 0
        } else {
            nal.size > 1 && (nal[1].toInt() and 0x80) != 0
        }

    private fun splitNals(data: ByteArray): List<ByteArray> {
        val ranges = mutableListOf<Pair<Int, Int>>()
        var start = -1
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                when {
                    data[i + 2] == 1.toByte() -> {
                        if (start in 0 until i) ranges += start to i
                        start = i + 3
                        i += 3
                        continue
                    }

                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> {
                        if (start in 0 until i) ranges += start to i
                        start = i + 4
                        i += 4
                        continue
                    }
                }
            }
            i++
        }
        if (start in 0 until data.size) ranges += start to data.size
        return ranges.map { data.copyOfRange(it.first, it.second) }
    }

    private companion object {
        const val TAG = "LiveView"
        val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
