package com.acegallery.model

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNameParserTest {

    @Test
    fun `parses video name`() {
        val parsed = FileNameParser.parse("VID_20260101_120000_001.mp4", ZoneOffset.UTC)
        assertEquals(CameraFileKind.VIDEO, parsed.kind)
        assertEquals(Instant.parse("2026-01-01T12:00:00Z"), parsed.capturedAt)
        assertFalse(parsed.hidden)
    }

    @Test
    fun `parses photo name with lens segment`() {
        val parsed = FileNameParser.parse("IMG_20260101_120000_00_001.jpg", ZoneOffset.UTC)
        assertEquals(CameraFileKind.PHOTO, parsed.kind)
        assertEquals(Instant.parse("2026-01-01T12:00:00Z"), parsed.capturedAt)
    }

    @Test
    fun `hides lrv and thm`() {
        assertTrue(FileNameParser.parse("LRV_20260101_120000_001.lrv").hidden)
        assertTrue(FileNameParser.parse("THM_20260101_120000_001.thm").hidden)
    }

    @Test
    fun `unknown name is other and not hidden`() {
        val parsed = FileNameParser.parse("something_weird.bin")
        assertEquals(CameraFileKind.OTHER, parsed.kind)
        assertNull(parsed.capturedAt)
        assertFalse(parsed.hidden)
    }

    @Test
    fun `parses real ace pro sample`() {
        val parsed = FileNameParser.parse("IMG_20260726_192311_149.jpg", ZoneOffset.UTC)
        assertEquals(CameraFileKind.PHOTO, parsed.kind)
        assertEquals(Instant.parse("2026-07-26T19:23:11Z"), parsed.capturedAt)
    }
}
