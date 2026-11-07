package com.acegallery.ui.gallery

import com.acegallery.model.CameraFile
import com.acegallery.model.CameraFileKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryGroupingTest {

    @Test
    fun `groups by date newest first`() {
        val sections = GalleryGrouping.group(
            listOf(
                fileAt("2026-01-01T10:00:00Z"),
                fileAt("2026-01-02T10:00:00Z"),
            ),
            FileFilter.ALL,
            ZoneOffset.UTC,
        )
        assertEquals(
            listOf(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 1)),
            sections.map { it.date },
        )
    }

    @Test
    fun `filters photos`() {
        val sections = GalleryGrouping.group(listOf(photo, video), FileFilter.PHOTOS)
        assertTrue(sections.flatMap { it.files }.all { it.kind == CameraFileKind.PHOTO })
    }

    @Test
    fun `undated files go last`() {
        val sections = GalleryGrouping.group(
            listOf(photo, undated),
            FileFilter.ALL,
            ZoneOffset.UTC,
        )
        assertEquals(null, sections.last().date)
    }

    private fun fileAt(instant: String) = CameraFile(
        uri = "/DCIM/$instant.jpg",
        path = "/DCIM/$instant.jpg",
        name = "$instant.jpg",
        kind = CameraFileKind.PHOTO,
        capturedAt = Instant.parse(instant),
    )

    private val photo = fileAt("2026-01-01T10:00:00Z")
    private val video = CameraFile(
        uri = "/DCIM/v.mp4",
        path = "/DCIM/v.mp4",
        name = "v.mp4",
        kind = CameraFileKind.VIDEO,
        capturedAt = Instant.parse("2026-01-01T11:00:00Z"),
    )
    private val undated = CameraFile(
        uri = "/DCIM/u.bin",
        path = "/DCIM/u.bin",
        name = "u.bin",
        kind = CameraFileKind.OTHER,
    )
}
