package com.acegallery.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPathTest {

    @Test
    fun `extracts path from absolute url`() =
        assertEquals(
            "/DCIM/Camera01/VID_1.mp4",
            CameraPath.pathOf("http://192.168.42.1/DCIM/Camera01/VID_1.mp4"),
        )

    @Test
    fun `keeps relative path`() =
        assertEquals("/DCIM/Camera01/VID_1.mp4", CameraPath.pathOf("DCIM/Camera01/VID_1.mp4"))

    @Test
    fun `keeps rooted path`() =
        assertEquals("/DCIM/Camera01/VID_1.mp4", CameraPath.pathOf("/DCIM/Camera01/VID_1.mp4"))

    @Test
    fun `extracts filename`() =
        assertEquals("VID_1.mp4", CameraPath.fileName("/DCIM/Camera01/VID_1.mp4"))
}
