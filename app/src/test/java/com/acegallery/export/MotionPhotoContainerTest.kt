package com.acegallery.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPhotoContainerTest {
    @Test
    fun `xmp is oplus only and has no xiaomi legacy fields`() {
        val xmp = MotionPhotoContainer.buildXmp(videoLength = 1_000, presentationUs = 0)
            .toString(Charsets.UTF_8)
        assertTrue(xmp.contains("GCamera:MotionPhoto=\"1\""))
        assertTrue(xmp.contains("OpCamera:MotionPhotoOwner=\"oplus\""))
        assertTrue(xmp.contains("OpCamera:VideoLength=\"1000\""))
        assertFalse(xmp.contains("MiCamera"))
        assertFalse(xmp.contains("MicroVideo"))
    }

    @Test
    fun `exif user comment is type seven oplus marker`() {
        val exif = MotionPhotoContainer.oppoExif(1920, 1080)
        assertEquals(124, exif.size)
        assertEquals(0xE1, exif[1].toInt() and 0xFF)
        // UserComment tag 0x9286, type 7, count 22, value starts at offset 92 of TIFF
        val tiff = exif.copyOfRange(10, exif.size)
        assertEquals(0x9286, ((tiff[64].toInt() and 0xFF) shl 8) or (tiff[65].toInt() and 0xFF))
        assertEquals(7, ((tiff[66].toInt() and 0xFF) shl 8) or (tiff[67].toInt() and 0xFF))
        val value = tiff.copyOfRange(92, 114).toString(Charsets.US_ASCII)
        assertEquals("ASCII\u0000\u0000\u0000oplus_10485792", value)
    }

    @Test
    fun `inserts exif before xmp after app0`() {
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x04, 0x00, 0x00, 0xFF.toByte(), 0xD9.toByte(),
        )
        val exif = MotionPhotoContainer.oppoExif(1, 1)
        val xmp = MotionPhotoContainer.buildXmp(5, 0)
        val out = MotionPhotoContainer.insertSegments(jpeg, exif, xmp)
        assertArrayEquals(exif, out.copyOfRange(8, 8 + exif.size))
        assertArrayEquals(xmp, out.copyOfRange(8 + exif.size, 8 + exif.size + xmp.size))
    }

    @Test
    fun `assemble appends the video`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val video = byteArrayOf(1, 2, 3)
        assertArrayEquals(jpeg + video, MotionPhotoContainer.assemble(jpeg, video))
    }
}
