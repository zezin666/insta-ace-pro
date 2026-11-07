package com.acegallery.camera

import java.io.IOException

data class MessageHeader(
    val command: Int,
    val seq: Int,
    val responseCode: Int,
)

object MessageCodec {

    fun encodeHeader(command: Int, seq: Int): ByteArray = byteArrayOf(
        0x04,
        0x00,
        0x00,
        (command and 0xFF).toByte(),
        ((command ushr 8) and 0xFF).toByte(),
        0x02,
        (seq and 0xFF).toByte(),
        ((seq ushr 8) and 0xFF).toByte(),
        ((seq ushr 16) and 0xFF).toByte(),
        0x80.toByte(),
        0x00,
        0x00,
    )

    fun parseHeader(bytes: ByteArray): MessageHeader {
        if (bytes.size < 12) throw IOException("message header too short: ${bytes.size}")
        val code = u16(bytes, 3)
        val seq = (bytes[6].toInt() and 0xFF) or
            ((bytes[7].toInt() and 0xFF) shl 8) or
            ((bytes[8].toInt() and 0xFF) shl 16)
        return MessageHeader(command = code, seq = seq, responseCode = code)
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
