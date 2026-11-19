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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    private val viewModels = mutableListOf<LiveViewViewModel>()

    @After
    fun stopViewModels() {
        viewModels.forEach { it.stop() }
        runCurrent()
    }

    @Test
    fun `photo is sent directly while recording`() = runTest {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        vm.start()
        runCurrent()
        vm.toggleRecording()
        runCurrent()
        vm.takePhoto()
        runCurrent()
        assertEquals(1, gateway.takePictureCalls)
    }

    @Test
    fun `record is sent directly while capturing`() = runTest {
        val gateway = FakeGateway(blockTakePicture = true)
        val vm = viewModel(gateway)
        vm.start()
        runCurrent()
        vm.takePhoto()
        runCurrent()
        vm.toggleRecording()
        runCurrent()
        assertEquals(1, gateway.startRecordingCalls)
        gateway.releaseTakePicture()
        runCurrent()
    }

    @Test
    fun `mode and live params are exposed`() = runTest {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        gateway.captureMode.value = CaptureMode.VIDEO
        gateway.liveStreamParams.value = LiveStreamParams(1920, 1080, 30)
        runCurrent()
        assertEquals(CaptureMode.VIDEO, vm.state.value.captureMode)
        assertEquals(1920, vm.state.value.liveParams?.width)
    }

    @Test
    fun `decoded size is recorded even when stream params exist`() = runTest {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        gateway.liveStreamParams.value = LiveStreamParams(1920, 1080, 30)
        runCurrent()
        vm.onVideoSize(1280, 720)
        runCurrent()
        assertEquals(1280, vm.state.value.decodedParams?.width)
        assertEquals(720, vm.state.value.decodedParams?.height)
    }

    @Test
    fun `watchdog recovers once then exits after the two second grace period`() = runTest {
        val clock = FakeClock(10_000L)
        val gateway = FakeGateway()
        val vm = viewModel(gateway, clock)

        vm.start()
        runCurrent()
        assertEquals(1, gateway.startLiveStreamCalls)
        assertTrue(vm.state.value.streaming)

        advanceClockAndScheduler(clock, 1_000L)
        assertEquals(2, gateway.startLiveStreamCalls)
        assertEquals("画面中断，正在恢复…", vm.state.value.message)

        advanceClockAndScheduler(clock, 1_500L)
        assertEquals("画面中断，正在恢复…", vm.state.value.message)
        assertEquals(2, gateway.startLiveStreamCalls)

        advanceClockAndScheduler(clock, 500L)
        assertEquals("画面中断，请退出重进", vm.state.value.message)
        assertEquals(2, gateway.startLiveStreamCalls)
        vm.stop()
    }

    @Test
    fun `stop commands are delivered after the view model is cleared`() = runTest {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        vm.start()
        runCurrent()
        vm.stop()
        clear(vm)
        runCurrent()
        assertEquals(1, gateway.stopRecordingCalls)
        assertEquals(1, gateway.stopLiveStreamCalls)
    }

    private fun viewModel(
        gateway: FakeGateway,
        clock: Clock = FakeClock(advancePerRead = 1_000L),
    ): LiveViewViewModel = LiveViewViewModel(
        session = gateway,
        hostProvider = CameraHostProvider { "192.168.42.1" },
        ioDispatcher = mainDispatcherRule.dispatcher,
        clock = clock,
    ).also { viewModels += it }

    private fun clear(vm: LiveViewViewModel) {
        LiveViewViewModel::class.java
            .getDeclaredMethod("onCleared")
            .apply { isAccessible = true }
            .invoke(vm)
    }

    private fun runCurrent() {
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
    }

    private fun advanceClockAndScheduler(clock: FakeClock, durationMs: Long) {
        var remainingMs = durationMs
        while (remainingMs > 0) {
            val stepMs = minOf(remainingMs, WATCHDOG_TEST_STEP_MS)
            clock.advance(stepMs)
            mainDispatcherRule.dispatcher.scheduler.advanceTimeBy(stepMs)
            runCurrent()
            remainingMs -= stepMs
        }
    }

    private class FakeClock(
        private var now: Long = 0L,
        private val advancePerRead: Long = 0L,
    ) : Clock {
        override fun nowMs(): Long = now.also { now += advancePerRead }

        fun advance(durationMs: Long) {
            now += durationMs
        }
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
        var startLiveStreamCalls = 0
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

        override suspend fun startLiveStream() {
            startLiveStreamCalls++
        }

        override suspend fun stopLiveStream() {
            stopLiveStreamCalls++
        }

        fun releaseTakePicture() {
            takePictureGate.complete(Unit)
        }
    }

    private companion object {
        const val WATCHDOG_TEST_STEP_MS = 250L
    }
}
