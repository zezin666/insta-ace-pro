package com.acegallery.camera

import com.acegallery.bytes
import com.acegallery.proto.CameraProto
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraModeProtoTest {
    @Test
    fun `encodes set sync capture mode`() {
        val message = CameraProto.SetSyncCaptureMode.newBuilder()
            .setCaptureMode(CameraProto.SyncCaptureMode.SYNC_MODE_NORMAL_IMAGE)
            .build()
        assertEquals("0801", message.toByteArray().joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `parses sync capture mode response`() {
        val response = CameraProto.GetSyncCaptureModeResp.parseFrom(bytes("0802"))
        assertEquals(CameraProto.SyncCaptureMode.SYNC_MODE_NORMAL_VIDEO, response.captureMode)
    }

    @Test
    fun `parses live stream params with visible crop size`() {
        val update = CameraProto.LiveStreamParamsUpdate.parseFrom(
            bytes("10800f18b808201e320618800f20b808"),
        )
        assertEquals(1920, update.resWidth)
        assertEquals(1080, update.resHeight)
        assertEquals(30, update.resFps)
        assertEquals(1920, update.windowCropInfo.dstWidth)
        assertEquals(1080, update.windowCropInfo.dstHeight)
    }
}
