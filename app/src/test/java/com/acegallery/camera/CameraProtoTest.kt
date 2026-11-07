package com.acegallery.camera

import com.acegallery.bytes
import com.acegallery.proto.CameraProto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraProtoTest {

    @Test
    fun `encodes get file list`() {
        val message = CameraProto.GetFileList.newBuilder()
            .setMediaType(CameraProto.MediaType.VIDEO_AND_PHOTO)
            .setStart(100)
            .setLimit(100)
            .build()
        assertArrayEquals(bytes("080210641864"), message.toByteArray())
    }

    @Test
    fun `parses file list response`() {
        val response = CameraProto.GetFileListResp.parseFrom(bytes("0a01610a01621002"))
        assertEquals(listOf("a", "b"), response.uriList)
        assertEquals(2, response.totalCount)
    }
}
