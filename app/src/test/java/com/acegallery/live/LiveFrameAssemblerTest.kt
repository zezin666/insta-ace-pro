package com.acegallery.live

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LiveFrameAssemblerTest {

    @Test
    fun `AVC parameter sets are collected and prepended across packets`() {
        val assembler = LiveFrameAssembler()
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val firstSlice = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte())
        val continuation = byteArrayOf(0x65, 0x08, 0x22)

        assertNull(assembler.feed(annexB(sps, pps)))
        assertEquals("video/avc", assembler.mime())
        assertArrayEquals(annexB(sps, pps), assembler.parameterSets())

        assertNull(assembler.feed(annexB(firstSlice, continuation)))
        val completed = assembler.feed(annexB(byteArrayOf(0x41, 0x88.toByte(), 0x33)))

        assertNotNull(completed)
        assertEquals("video/avc", completed!!.mime)
        assertArrayEquals(
            annexB(sps, pps, firstSlice, continuation),
            completed.data,
        )
        assertNull(assembler.pollFrame())
    }

    @Test
    fun `AVC first slice starts a new frame`() {
        val assembler = configuredAvcAssembler()

        val first = assembler.feed(annexB(byteArrayOf(0x65, 0x88.toByte(), 0x11)))
        assertNull(first)

        val boundary = assembler.feed(
            annexB(
                byteArrayOf(0x41, 0x88.toByte(), 0x22),
                byteArrayOf(0x41, 0x88.toByte(), 0x33),
            ),
        )

        assertNotNull(boundary)
        assertEquals(0x11, boundary!!.data.last().toInt() and 0xFF)
        val secondBoundary = assembler.pollFrame()
        assertNotNull(secondBoundary)
        assertEquals(0x22, secondBoundary!!.data.last().toInt() and 0xFF)
        assertNull(assembler.pollFrame())

        val third = assembler.feed(annexB(byteArrayOf(0x41, 0x88.toByte(), 0x44)))
        assertNotNull(third)
        assertEquals(0x33, third!!.data.last().toInt() and 0xFF)
    }

    @Test
    fun `HEVC needs VPS SPS and PPS before exposing csd`() {
        val assembler = LiveFrameAssembler()
        val vps = byteArrayOf(0x40, 0x01, 0x0C)
        val sps = byteArrayOf(0x42, 0x01, 0x01)
        val pps = byteArrayOf(0x44, 0x01, 0xC0.toByte())

        assertNull(assembler.feed(annexB(vps)))
        assertNull(assembler.feed(annexB(sps)))
        assertEquals("video/hevc", assembler.mime())
        assertNull(assembler.parameterSets())

        assertNull(assembler.feed(annexB(pps)))
        assertArrayEquals(annexB(vps, sps, pps), assembler.parameterSets())
    }

    @Test
    fun `HEVC cross packet frame is split on the next first slice`() {
        val assembler = LiveFrameAssembler()
        val vps = byteArrayOf(0x40, 0x01, 0x0C)
        val sps = byteArrayOf(0x42, 0x01, 0x01)
        val pps = byteArrayOf(0x44, 0x01, 0xC0.toByte())
        val idrFirstSlice = byteArrayOf(0x26, 0x01, 0x80.toByte(), 0x55)
        val idrContinuation = byteArrayOf(0x26, 0x01, 0x00, 0x66)

        assembler.feed(annexB(vps, sps, pps))
        assertNull(assembler.feed(annexB(idrFirstSlice, idrContinuation)))

        val completed = assembler.feed(annexB(byteArrayOf(0x02, 0x01, 0x80.toByte(), 0x77)))

        assertNotNull(completed)
        assertEquals("video/hevc", completed!!.mime)
        assertArrayEquals(
            annexB(vps, sps, pps, idrFirstSlice, idrContinuation),
            completed.data,
        )
    }

    private fun configuredAvcAssembler(): LiveFrameAssembler = LiveFrameAssembler().also {
        it.feed(annexB(byteArrayOf(0x67, 0x42), byteArrayOf(0x68, 0xCE.toByte())))
    }

    private fun annexB(vararg nals: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            nals.forEach { nal ->
                write(byteArrayOf(0, 0, 0, 1))
                write(nal)
            }
        }.toByteArray()
}
