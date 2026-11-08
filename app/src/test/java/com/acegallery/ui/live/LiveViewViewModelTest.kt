package com.acegallery.ui.live

import com.acegallery.MainDispatcherRule
import com.acegallery.camera.CameraTransport
import com.acegallery.model.CameraFile
import com.acegallery.network.CameraFileSource
import com.acegallery.repository.CameraFileRepository
import com.acegallery.session.CameraGateway
import com.acegallery.session.CameraState
import com.acegallery.session.CaptureMode
import com.acegallery.session.LiveStreamParams
import com.acegallery.settings.CameraHostProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LiveViewViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `photo is ignored while recording`() = runTest {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        vm.start()
        advanceUntilIdle()
        vm.toggleRecording()
        advanceUntilIdle()
        vm.takePhoto()
        advanceUntilIdle()
        assertEquals(0, gateway.takePictureCalls)
    }

    @Test
    fun `record is ignored while capturing`() = runTest {
        val gateway = FakeGateway(blockTakePicture = true)
        val vm = viewModel(gateway)
        vm.start()
        advanceUntilIdle()
        vm.takePhoto()
        advanceUntilIdle()
        vm.toggleRecording()
        advanceUntilIdle()
        assertEquals(0, gateway.startRecordingCalls)
        gateway.releaseTakePicture()
        advanceUntilIdle()
    }

    @Test
    fun `mode and live params are exposed`() = runTest {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        gateway.captureMode.value = CaptureMode.VIDEO
        gateway.liveStreamParams.value = LiveStreamParams(1920, 1080, 30)
        advanceUntilIdle()
        assertEquals(CaptureMode.VIDEO, vm.state.value.captureMode)
        assertEquals(1920, vm.state.value.liveParams?.width)
    }

    @Test
    fun `stop commands are delivered after the view model is cleared`() = runTest {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        vm.start()
        advanceUntilIdle()
        vm.stop()
        clear(vm)
        advanceUntilIdle()
        assertEquals(1, gateway.stopRecordingCalls)
        assertEquals(1, gateway.stopLiveStreamCalls)
    }

    private fun viewModel(gateway: FakeGateway): LiveViewViewModel = LiveViewViewModel(
        session = gateway,
        hostProvider = CameraHostProvider { "192.168.42.1" },
        ioDispatcher = mainDispatcherRule.dispatcher,
    )

    private fun clear(vm: LiveViewViewModel) {
        LiveViewViewModel::class.java
            .getDeclaredMethod("onCleared")
            .apply { isAccessible = true }
            .invoke(vm)
    }

    private fun advanceUntilIdle() {
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    }

    private class FakeGateway(
        private val blockTakePicture: Boolean = false,
    ) : CameraGateway {
        override val state = MutableStateFlow<CameraState>(CameraState.Disconnected)
        override val captureMode = MutableStateFlow(CaptureMode.UNKNOWN)
        override val liveStreamParams = MutableStateFlow<LiveStreamParams?>(null)
        var takePictureCalls = 0
            private set
        var startRecordingCalls = 0
            private set
        var stopRecordingCalls = 0
            private set
        var stopLiveStreamCalls = 0
            private set
        private val takePictureGate = CompletableDeferred<Unit>()

        override suspend fun connect(host: String) {
            state.value = CameraState.Connected(host)
        }

        override fun disconnect() = Unit
        override suspend fun listFiles(): List<CameraFile> = emptyList()
        override fun repository(): CameraFileRepository = error("not used")
        override fun transport(): CameraTransport = error("not used")
        override fun httpClient(): OkHttpClient = error("not used")
        override suspend fun get(): CameraFileSource = error("not used")
        override suspend fun setCaptureMode(mode: CaptureMode) = Unit

        override suspend fun takePicture() {
            takePictureCalls++
            if (blockTakePicture) takePictureGate.await()
        }

        override suspend fun startRecording() {
            startRecordingCalls++
        }

        override suspend fun stopRecording() {
            stopRecordingCalls++
        }

        override suspend fun stopLiveStream() {
            stopLiveStreamCalls++
        }

        fun releaseTakePicture() {
            takePictureGate.complete(Unit)
        }
    }
}
