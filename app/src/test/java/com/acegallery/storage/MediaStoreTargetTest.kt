package com.acegallery.storage

import com.acegallery.model.CameraFile
import com.acegallery.model.CameraFileKind
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaStoreTargetTest {

    @Test
    fun `video goes to video collection under DCIM Insta360`() {
        val target = MediaStoreTargets.forFile(file("a.mp4", CameraFileKind.VIDEO))
        assertEquals(MediaStoreCollection.VIDEO, target.collection)
        assertEquals("DCIM/Insta360", target.relativePath)
        assertEquals("video/mp4", target.mimeType)
    }

    @Test
    fun `photo goes to image collection`() {
        val target = MediaStoreTargets.forFile(file("a.jpg", CameraFileKind.PHOTO))
        assertEquals(MediaStoreCollection.IMAGE, target.collection)
        assertEquals("image/jpeg", target.mimeType)
    }

    @Test
    fun `dng uses image dng mime`() {
        assertEquals(
            "image/x-adobe-dng",
            MediaStoreTargets.forFile(file("a.dng", CameraFileKind.PHOTO)).mimeType,
        )
    }

    @Test
    fun `other goes to downloads`() {
        val target = MediaStoreTargets.forFile(file("a.bin", CameraFileKind.OTHER))
        assertEquals(MediaStoreCollection.DOWNLOAD, target.collection)
        assertEquals("Download/Insta360", target.relativePath)
    }

    private fun file(name: String, kind: CameraFileKind) = CameraFile(
        uri = "/DCIM/$name",
        path = "/DCIM/$name",
        name = name,
        kind = kind,
    )
}
