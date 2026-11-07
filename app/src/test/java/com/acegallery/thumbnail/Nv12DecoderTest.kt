package com.acegallery.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Test

class Nv12DecoderTest {

    @Test
    fun `nv12 converts yuv to argb`() {
        // 2x2: Y all 0 (black), UV neutral 128.
        val data = byteArrayOf(0, 0, 0, 0, 128.toByte(), 128.toByte())
        val thumb = Nv12Decoder.decode(data, 2, 2)
        assertEquals(4, thumb.argb.size)
        assertEquals(0xFF000000.toInt(), thumb.argb[0])
    }

    @Test
    fun `parses nv12 thumbnail header`() {
        val width = 2
        val height = 2
        val body = ByteArray(36 + width * height * 3 / 2 + 4)
        fun putInt(offset: Int, value: Int) {
            body[offset] = (value and 0xFF).toByte()
            body[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            body[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            body[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        }
        putInt(0, 1)
        putInt(4, body.size)
        putInt(8, 1)
        putInt(12, 0)
        putInt(16, width)
        putInt(20, height)

        val ySize = width * height
        for (i in 0 until ySize) body[36 + i] = 0
        body[36 + ySize] = 128.toByte()
        body[36 + ySize + 1] = 128.toByte()

        val thumb = Nv12ThumbnailParser.parse(body)
        assertEquals(width, thumb?.width)
        assertEquals(height, thumb?.height)
        assertEquals(4, thumb?.argb?.size)
    }
}
