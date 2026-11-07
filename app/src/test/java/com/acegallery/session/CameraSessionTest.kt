package com.acegallery.session

import com.acegallery.camera.CameraTransport
import com.acegallery.network.CameraNetworkFinder
import com.acegallery.network.CameraNetworkHandle
import com.acegallery.proto.CameraProto
import java.net.Socket
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSessionTest {

    @Test
    fun `connect without camera network reports error`() = runTest {
        val session = session(FakeFinder(null))
        session.connect()
        assertTrue(session.state.value is CameraState.Error)
    }

    @Test
    fun `connect and list files success`() = runTest {
        val session = session(FakeFinder(FakeHandle), FakeTransport())
        session.connect()
        assertTrue(session.state.value is CameraState.Connected)
        assertEquals(1, session.listFiles().size)
    }

    @Test
    fun `list files before connect throws`() = runTest {
        val session = session(FakeFinder(null))
        var thrown = false
        try {
            session.listFiles()
        } catch (_: IllegalStateException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `get reconnects when transport is dead`() = runTest {
        val transport = FakeTransport()
        val session = session(FakeFinder(FakeHandle), transport)
        session.connect()
        transport.close()

        session.get()

        assertTrue(session.state.value is CameraState.Connected)
    }

    private fun session(
        finder: CameraNetworkFinder,
        transport: CameraTransport = FakeTransport(),
    ): CameraSession = CameraSession(
        networkFinder = finder,
        transportFactory = { transport },
        socketConnector = { _, _, _ -> Socket() },
        httpClientFactory = { OkHttpClient() },
    )

    private class FakeFinder(private val handle: CameraNetworkHandle?) : CameraNetworkFinder {
        override suspend fun find(host: String, port: Int): CameraNetworkHandle? = handle
    }

    private object FakeHandle : CameraNetworkHandle {
        override fun bind(socket: Socket) = Unit
    }

    private class FakeTransport : CameraTransport {
        private var alive = true

        override fun start() {
            alive = true
        }

        override fun isAlive(): Boolean = alive

        override fun request(command: Int, payload: ByteArray, timeoutMs: Long): ByteArray =
            CameraProto.GetFileListResp.newBuilder()
                .addUri("/DCIM/a.mp4")
                .setTotalCount(1)
                .build()
                .toByteArray()

        override fun close() {
            alive = false
        }
    }
}
