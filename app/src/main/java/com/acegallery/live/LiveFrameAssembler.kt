package com.acegallery.live

import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

/**
 * Reassembles Annex-B H.264/H.265 access units without depending on Android.
 *
 * A camera packet may contain parameter sets, one or more VCL NALs, or a mix of
 * both. The assembler keeps the current frame open until the next first slice
 * arrives, then returns the completed frame with its parameter sets prepended.
 */
class LiveFrameAssembler {

    data class Frame(val data: ByteArray, val mime: String) {
        val bytes: ByteArray get() = data
        val frame: ByteArray get() = data
    }

    private var detectedMime: String? = null
    private val parameterBuffer = ByteArrayOutputStream()
    private var hasVps = false
    private var hasSps = false
    private var hasPps = false
    private val frameBuffer = ByteArrayOutputStream()
    private var hasVcl = false
    private val completedFrames = ArrayDeque<Frame>()

    /**
     * Consumes one packet and returns the first completed frame, if any.
     * If a packet contains multiple completed frames, use [pollFrame] to drain
     * the remainder before feeding another packet.
     */
    fun feed(packet: ByteArray): Frame? {
        if (packet.isNotEmpty()) {
            val nals = splitNals(packet)
            if (nals.isNotEmpty()) {
                if (detectedMime == null) {
                    detectedMime = detectMime(nals)
                }
                val currentMime = detectedMime
                if (currentMime != null) {
                    for (nal in nals) {
                        if (nal.isEmpty()) continue
                        val type = nalType(nal, currentMime)
                        if (isParameter(type, currentMime)) {
                            if (!parameterSetsReady()) {
                                appendParameter(nal, type, currentMime)
                            }
                            continue
                        }
                        if (!isVcl(type, currentMime)) continue

                        val firstSlice = isFirstSlice(nal, currentMime)
                        if (firstSlice && hasVcl) {
                            completeFrame(currentMime)
                        }
                        appendNal(frameBuffer, nal)
                        hasVcl = true
                    }
                }
            }
        }
        return pollFrame()
    }

    /** Returns the next completed frame left by a previous [feed] call. */
    fun pollFrame(): Frame? = completedFrames.pollFirst()

    /** The codec's configuration data once the complete parameter-set set is known. */
    fun parameterSets(): ByteArray? =
        if (parameterSetsReady()) parameterBuffer.toByteArray() else null

    fun mime(): String? = detectedMime

    private fun parameterSetsReady(): Boolean {
        val currentMime = detectedMime ?: return false
        return if (currentMime == HEVC_MIME) {
            hasVps && hasSps && hasPps
        } else {
            hasSps && hasPps
        }
    }

    private fun completeFrame(currentMime: String) {
        val payload = frameBuffer.toByteArray()
        frameBuffer.reset()
        hasVcl = false
        if (payload.isEmpty()) return
        val parameterSets = parameterSets() ?: return
        val frame = ByteArrayOutputStream(parameterSets.size + payload.size).apply {
            write(parameterSets)
            write(payload)
        }.toByteArray()
        completedFrames.addLast(Frame(frame, currentMime))
    }

    private fun appendParameter(nal: ByteArray, type: Int, currentMime: String) {
        when {
            currentMime == HEVC_MIME && type == 32 -> hasVps = true
            currentMime == HEVC_MIME && type == 33 -> hasSps = true
            currentMime == HEVC_MIME && type == 34 -> hasPps = true
            currentMime == AVC_MIME && type == 7 -> hasSps = true
            currentMime == AVC_MIME && type == 8 -> hasPps = true
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
            if (avcType == 7 || avcType == 8) return AVC_MIME
            if (hevcType in 32..34) return HEVC_MIME
        }
        return null
    }

    private fun nalType(nal: ByteArray, mime: String): Int =
        if (mime == HEVC_MIME) {
            (nal[0].toInt() and 0xFF) shr 1
        } else {
            nal[0].toInt() and 0x1F
        }

    private fun isParameter(type: Int, mime: String): Boolean =
        if (mime == HEVC_MIME) type in 32..34 else type == 7 || type == 8

    private fun isVcl(type: Int, mime: String): Boolean =
        if (mime == HEVC_MIME) type in 0..31 else type in 1..5

    private fun isFirstSlice(nal: ByteArray, mime: String): Boolean =
        if (mime == HEVC_MIME) {
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
        const val AVC_MIME = "video/avc"
        const val HEVC_MIME = "video/hevc"
        val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
