package com.acegallery.camera

import com.acegallery.bytes
import com.acegallery.proto.CameraProto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
                assertFalse(client.isAlive())
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `notification is emitted without a pending request`() = runTest {
        FakeCameraServer().use { server ->
            val client = CameraProtocolClient(server.socket(), backgroundScope)
            try {
                client.start()
                val received = CompletableDeferred<CameraNotification>()
                val collector = launch(
                    Dispatchers.Default,
                    start = CoroutineStart.UNDISPATCHED,
                ) {
                    received.complete(client.notifications.first())
                }
                try {
                    server.sendNotification(
                        CameraCommands.NOTIFICATION_UPDATE_LIVE_STREAM_PARAMS,
                        bytes("10800f18b808201e"),
                    )
                    val notification = received.await()
                    assertEquals(8234, notification.code)
                } finally {
                    collector.cancel()
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `notification with a pending sequence does not steal the response`() = runTest {
        FakeCameraServer().use { server ->
            val client = CameraProtocolClient(server.socket(), backgroundScope)
            try {
                client.start()
                server.holdResponses()
                val request = async(Dispatchers.IO) {
                    client.request(CameraCommands.GET_FILE_LIST, ByteArray(0), timeoutMs = 5_000)
                }
                server.awaitRequestReceived()
                val received = CompletableDeferred<CameraNotification>()
                val collector = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    received.complete(client.notifications.first())
                }
                try {
                    server.sendNotification(
                        CameraCommands.NOTIFICATION_UPDATE_LIVE_STREAM_PARAMS,
                        bytes("10800a18d005201e"),
                    )
                    server.releaseResponses()
                    val list = CameraProto.GetFileListResp.parseFrom(request.await())
                    assertEquals(listOf("/DCIM/a.mp4"), list.uriList)
                    assertEquals(8234, received.await().code)
                } finally {
                    collector.cancel()
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `non fatal timeout keeps the transport alive`() = runTest {
        FakeCameraServer(respond = false).use { server ->
            val client = CameraProtocolClient(server.socket(), backgroundScope)
            try {
                client.start()
                assertThrows(CameraProtocolTimeoutException::class.java) {
                    client.request(
                        CameraCommands.GET_SYNC_CAPTURE_MODE,
                        ByteArray(0),
                        timeoutMs = 200,
                        fatalOnTimeout = false,
                    )
                }
                assertTrue(client.isAlive())
            } finally {
                client.close()
            }
        }
    }
}
