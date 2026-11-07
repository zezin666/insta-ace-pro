package com.acegallery.camera

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

object CameraFrame {

    private const val MAX_FRAME = 16 * 1024 * 1024

    fun encode(payload: ByteArray): ByteArray {
        val length = payload.size + 4
        val header = byteArrayOf(
            (length and 0xFF).toByte(),
            ((length ushr 8) and 0xFF).toByte(),
            ((length ushr 16) and 0xFF).toByte(),
            ((length ushr 24) and 0xFF).toByte(),
        )
        return header + payload
    }

    fun read(input: InputStream): ByteArray? {
        val head = ByteArray(4)
        if (!readFully(input, head, allowEofAtStart = true)) return null
        val length = (head[0].toInt() and 0xFF) or
            ((head[1].toInt() and 0xFF) shl 8) or
            ((head[2].toInt() and 0xFF) shl 16) or
            ((head[3].toInt() and 0xFF) shl 24)
        if (length < 4 || length > MAX_FRAME) throw IOException("bad frame length $length")
        val body = ByteArray(length - 4)
        readFully(input, body, allowEofAtStart = false)
        return body
    }

    private fun readFully(
        input: InputStream,
        buffer: ByteArray,
        allowEofAtStart: Boolean,
    ): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) {
                if (offset == 0 && allowEofAtStart) return false
                throw EOFException("unexpected end of stream")
            }
            offset += count
        }
        return true
    }
}
