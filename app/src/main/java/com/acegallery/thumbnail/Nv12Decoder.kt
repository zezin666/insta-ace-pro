package com.acegallery.thumbnail

object Nv12Decoder {

    fun decode(data: ByteArray, width: Int, height: Int): Thumbnail {
        require(width > 0 && height > 0) { "invalid dimensions" }
        val ySize = width * height
        val uvSize = ySize / 2
        require(data.size >= ySize + uvSize) { "NV12 payload too small" }

        val argb = IntArray(ySize)
        for (row in 0 until height) {
            for (col in 0 until width) {
                val y = ((data[row * width + col].toInt() and 0xFF) - 16).coerceAtLeast(0)
                val uvIndex = ySize + (row / 2) * width + (col / 2) * 2
                val u = (data[uvIndex].toInt() and 0xFF) - 128
                val v = (data[uvIndex + 1].toInt() and 0xFF) - 128

                val r = ((298 * y + 409 * v + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * y - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * y + 516 * u + 128) shr 8).coerceIn(0, 255)

                argb[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Thumbnail(width, height, argb)
    }
}

object Nv12ThumbnailParser {

    private const val HEADER_BYTES = 36

    fun parse(body: ByteArray): Thumbnail? {
        if (body.size < HEADER_BYTES) return null
        val width = u32(body, 16)
        val height = u32(body, 20)
        if (width <= 0 || height <= 0) return null

        val pixelBytes = width * height * 3 / 2
        if (body.size < HEADER_BYTES + pixelBytes) return null

        val payload = body.copyOfRange(HEADER_BYTES, HEADER_BYTES + pixelBytes)
        return runCatching { Nv12Decoder.decode(payload, width, height) }.getOrNull()
    }

    private fun u32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
}
