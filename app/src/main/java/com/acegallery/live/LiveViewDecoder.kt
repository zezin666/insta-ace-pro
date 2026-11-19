package com.acegallery.live

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * Decodes the camera's raw live preview stream (Annex-B H.264/H.265) onto a Surface.
 * Feed packets in order from [com.acegallery.session.CameraGateway.livePackets].
 */
class LiveViewDecoder(
    private val surface: Surface,
    private val onVideoSize: ((width: Int, height: Int) -> Unit)? = null,
) {

    private val assembler = LiveFrameAssembler()
    private var codec: MediaCodec? = null
    private var ptsUs = 0L
    @Volatile private var frameDurationUs = DEFAULT_FRAME_DURATION_US
    private val queuedFrames = ArrayDeque<ByteArray>(MAX_QUEUED_FRAMES)
    private var released = false
    private var reportedSize = false

    /** Matches the stream's advertised frame rate; invalid values keep the 30 fps default. */
    fun setFps(fps: Int) {
        if (fps <= 0) return
        frameDurationUs = (1_000_000L / fps).coerceAtLeast(1L)
    }

    fun feed(packet: ByteArray) {
        if (released || packet.isEmpty()) return
        val firstFrame = assembler.feed(packet)
        configureIfReady()
        firstFrame?.let { flushFrame(it.data) }
        while (true) {
            val nextFrame = assembler.pollFrame() ?: break
            flushFrame(nextFrame.data)
        }
    }

    private fun configureIfReady() {
        if (codec != null) return
        val currentMime = assembler.mime() ?: return
        val parameterSets = assembler.parameterSets() ?: return
        var created: MediaCodec? = null
        runCatching {
            val format = MediaFormat.createVideoFormat(currentMime, 1920, 1080)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(parameterSets))
            created = MediaCodec.createDecoderByType(currentMime)
            created!!.configure(format, surface, null, 0)
            created!!.start()
            codec = created
            created = null
            Log.i(TAG, "Live decoder started: $currentMime")
        }.onFailure {
            runCatching { created?.release() }
            Log.w(TAG, "Cannot configure live decoder", it)
        }
    }

    private fun flushFrame(frame: ByteArray) {
        if (frame.isEmpty()) return
        val c = codec ?: return
        runCatching {
            drain(c)
            val hadQueuedFrames = queuedFrames.isNotEmpty()

            // Retire frames waiting for a codec slot before accepting this new one.
            if (!flushQueuedFrames(c)) {
                enqueueFrame(frame)
                return@runCatching
            }
            val timeoutUs = if (hadQueuedFrames) 0L else INPUT_TIMEOUT_US
            if (!queueFrame(c, frame, timeoutUs)) {
                enqueueFrame(frame)
            }
            drain(c)
        }.onFailure { Log.w(TAG, "Live frame decode failed", it) }
    }

    private fun flushQueuedFrames(c: MediaCodec): Boolean {
        var timeoutUs = INPUT_TIMEOUT_US
        while (queuedFrames.isNotEmpty()) {
            val frame = queuedFrames.removeFirst()
            if (!queueFrame(c, frame, timeoutUs)) {
                queuedFrames.addFirst(frame)
                return false
            }
            timeoutUs = 0L
        }
        return true
    }

    private fun queueFrame(c: MediaCodec, frame: ByteArray, timeoutUs: Long): Boolean {
        val index = c.dequeueInputBuffer(timeoutUs)
        if (index < 0) return false
        val input = c.getInputBuffer(index) ?: return false
        input.clear()
        input.put(frame)
        c.queueInputBuffer(index, 0, frame.size, ptsUs, 0)
        ptsUs += frameDurationUs
        return true
    }

    private fun enqueueFrame(frame: ByteArray) {
        if (queuedFrames.size >= MAX_QUEUED_FRAMES) {
            queuedFrames.removeFirst()
        }
        queuedFrames.addLast(frame)
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
        queuedFrames.clear()
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
    }

    private companion object {
        const val TAG = "LiveView"
        const val DEFAULT_FRAME_DURATION_US = 33_333L
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_QUEUED_FRAMES = 3
    }
}
