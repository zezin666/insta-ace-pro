package com.acegallery.camera

import com.acegallery.proto.CameraProto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraProtocolClientTest {

    @Test
    fun `request returns payload for matching sequence`() = runTest {
        FakeCameraServer().use { server ->
            val client = CameraProtocolClient(server.socket(), backgroundScope)
            try {
                client.start()
                val body = client.request(CameraCommands.GET_FILE_LIST, ByteArray(0))
                val response = CameraProto.GetFileListResp.parseFrom(body)
                assertEquals(listOf("/DCIM/a.mp4"), response.uriList)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `non 200 response throws`() = runTest {
        FakeCameraServer(responseCode = 500).use { server ->
            val client = CameraProtocolClient(server.socket(), backgroundScope)
            try {
                client.start()
                assertThrows(CameraProtocolException::class.java) {
                    client.request(CameraCommands.GET_FILE_LIST, ByteArray(0), timeoutMs = 2_000)
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `timeout when camera never answers`() = runTest {
        FakeCameraServer(respond = false).use { server ->
            val client = CameraProtocolClient(server.socket(), backgroundScope)
            try {
                client.start()
                assertThrows(CameraProtocolTimeoutException::class.java) {
                    client.request(CameraCommands.GET_FILE_LIST, ByteArray(0), timeoutMs = 200)
                }
            } finally {
                client.close()
            }
        }
    }
}
