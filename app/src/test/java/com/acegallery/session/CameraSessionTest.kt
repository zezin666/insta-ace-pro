package com.acegallery.session

import com.acegallery.bytes
import com.acegallery.camera.CameraCommands
import com.acegallery.camera.CameraNotification
import com.acegallery.camera.CameraProtocolException
import com.acegallery.camera.CameraProtocolTimeoutException
import com.acegallery.camera.CameraTransport
import com.acegallery.network.CameraNetworkFinder
import com.acegallery.network.CameraNetworkHandle
import com.acegallery.proto.CameraProto
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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

    @Test
    fun `take picture switches to photo mode first`() = runTest {
        val transport = RecordingTransport(initialMode = CameraProto.SyncCaptureMode.SYNC_MODE_NORMAL_VIDEO)
        val session = session(FakeFinder(FakeHandle), transport)
        session.connect()
        session.takePicture()
        assertEquals(
            listOf(
                CameraCommands.GET_SYNC_CAPTURE_MODE,
                CameraCommands.SET_SYNC_CAPTURE_MODE,
                CameraCommands.TAKE_PICTURE,
            ),
            transport.commands.filter { it in setOf(55, 54, 3) },
        )
        assertEquals(CaptureMode.PHOTO, session.captureMode.value)
    }

    @Test
    fun `start recording switches to video mode first`() = runTest {
        val transport = RecordingTransport(initialMode = CameraProto.SyncCaptureMode.SYNC_MODE_NORMAL_IMAGE)
        val session = session(FakeFinder(FakeHandle), transport)
        session.connect()
        session.startRecording()
        assertEquals(CaptureMode.VIDEO, session.captureMode.value)
        assertTrue(CameraCommands.START_CAPTURE in transport.commands)
    }

    @Test
    fun `mode query timeout keeps session connected`() = runTest {
        val transport = RecordingTransport(failModeQuery = true)
        val session = session(FakeFinder(FakeHandle), transport)
        session.connect()
        assertTrue(session.state.value is CameraState.Connected)
        assertEquals(CaptureMode.UNKNOWN, session.captureMode.value)
    }

    @Test
    fun `mode switch timeout keeps session connected and mode unchanged`() = runTest {
        val transport = RecordingTransport(failModeSwitch = true)
        val session = session(FakeFinder(FakeHandle), transport)
        session.connect()
        val error = runCatching { session.setCaptureMode(CaptureMode.PHOTO) }.exceptionOrNull()
        assertTrue(error is CameraProtocolTimeoutException)
        assertTrue(error?.message?.contains("无法切换相机模式") == true)
        assertTrue(session.state.value is CameraState.Connected)
        assertEquals(CaptureMode.UNKNOWN, session.captureMode.value)
    }

    @Test
    fun `live stream notification prefers visible crop size`() = runTest {
        val transport = RecordingTransport()
        val session = session(FakeFinder(FakeHandle), transport)
        session.connect()
        // encoded 1280x720, visible crop 1920x1080
        transport.emitNotification(
            CameraCommands.NOTIFICATION_UPDATE_LIVE_STREAM_PARAMS,
            bytes("10800a18d005201e320618800f20b808"),
        )
        assertEquals(1920, session.liveStreamParams.value?.width)
        assertEquals(1080, session.liveStreamParams.value?.height)
    }

    @Test
    fun `mode switch rejection prevents the capture command`() = runTest {
        val transport = RecordingTransport(failModeSwitchWith = 400)
        val session = session(FakeFinder(FakeHandle), transport)
        session.connect()
        val error = runCatching { session.takePicture() }.exceptionOrNull()
        assertTrue(error is CameraProtocolException)
        assertTrue(error?.message?.contains("无法切换相机模式") == true)
        assertTrue(CameraCommands.TAKE_PICTURE !in transport.commands)
        assertTrue(session.state.value is CameraState.Connected)
    }

    @Test
    fun `live stream notification falls back to encoded size`() = runTest {
        val transport = RecordingTransport()
        val session = session(FakeFinder(FakeHandle), transport)
        session.connect()
        // no window_crop_info: 1280x720 encoded size only
        transport.emitNotification(
            CameraCommands.NOTIFICATION_UPDATE_LIVE_STREAM_PARAMS,
            bytes("10800a18d005201e"),
        )
        assertEquals(1280, session.liveStreamParams.value?.width)
        assertEquals(720, session.liveStreamParams.value?.height)
    }

    private fun session(
        finder: CameraNetworkFinder,
        transport: CameraTransport = FakeTransport(),
    ): CameraSession = CameraSession(
        networkFinder = finder,
        transportFactory = CameraTransportFactory { transport },
        socketConnector = CameraSocketConnector { _, _, _ -> Socket() },
        httpClientFactory = CameraHttpClientProvider { OkHttpClient() },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
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

        override fun request(
            command: Int,
            payload: ByteArray,
            timeoutMs: Long,
            fatalOnTimeout: Boolean,
        ): ByteArray =
            CameraProto.GetFileListResp.newBuilder()
                .addUri("/DCIM/a.mp4")
                .setTotalCount(1)
                .build()
                .toByteArray()

        override fun close() {
            alive = false
        }
    }

    private class RecordingTransport(
        private val initialMode: CameraProto.SyncCaptureMode =
            CameraProto.SyncCaptureMode.SYNC_MODE_UNKNOWN,
        private val failModeQuery: Boolean = false,
        private val failModeSwitch: Boolean = false,
        private val failModeSwitchWith: Int? = null,
    ) : CameraTransport {
        val commands = mutableListOf<Int>()
        private val _notifications = MutableSharedFlow<CameraNotification>(extraBufferCapacity = 8)
        override val notifications: Flow<CameraNotification> = _notifications
        private var alive = true

        override fun start() {
            alive = true
        }

        override fun isAlive(): Boolean = alive

        fun emitNotification(code: Int, body: ByteArray) {
            _notifications.tryEmit(CameraNotification(code, body))
        }

        override fun request(
            command: Int,
            payload: ByteArray,
            timeoutMs: Long,
            fatalOnTimeout: Boolean,
        ): ByteArray {
            commands += command
            return when (command) {
                CameraCommands.GET_SYNC_CAPTURE_MODE -> {
                    if (failModeQuery) throw CameraProtocolTimeoutException("mode query timed out")
                    CameraProto.GetSyncCaptureModeResp.newBuilder()
                        .setCaptureMode(initialMode)
                        .build()
                        .toByteArray()
                }

                CameraCommands.SET_SYNC_CAPTURE_MODE -> {
                    failModeSwitchWith?.let { throw CameraProtocolException(it) }
                    if (failModeSwitch) throw CameraProtocolTimeoutException("mode switch timed out")
                    ByteArray(0)
                }

                CameraCommands.GET_FILE_LIST -> CameraProto.GetFileListResp.newBuilder()
                    .addUri("/DCIM/a.mp4")
                    .setTotalCount(1)
                    .build()
                    .toByteArray()

                else -> ByteArray(0)
            }
        }

        override fun close() {
            alive = false
        }
    }
}
