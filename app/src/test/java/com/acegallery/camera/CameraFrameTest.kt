package com.acegallery.camera

import com.acegallery.bytes
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraFrameTest {

    @Test
    fun `frame prefixes little endian length`() =
        assertArrayEquals(bytes("07000000050000"), CameraFrame.encode(bytes("050000")))

    @Test
    fun `read returns one frame then null on eof`() {
        val input = ByteArrayInputStream(bytes("07000000050000"))
        assertArrayEquals(bytes("050000"), CameraFrame.read(input))
        assertNull(CameraFrame.read(input))
    }

    @Test
    fun `header encodes command and 24 bit seq`() =
        assertArrayEquals(bytes("0400000d0002010000800000"), MessageCodec.encodeHeader(13, 1))

    @Test
    fun `header parses response code`() {
        val header = MessageCodec.parseHeader(bytes("040000c80002010000800000"))
        assertEquals(200, header.responseCode)
        assertEquals(1, header.seq)
    }

    @Test(expected = IOException::class)
    fun `rejects frame length below four`() {
        CameraFrame.read(ByteArrayInputStream(bytes("03000000")))
    }
}
