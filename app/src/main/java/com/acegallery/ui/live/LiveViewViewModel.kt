package com.acegallery.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acegallery.di.IoDispatcher
import com.acegallery.session.CameraGateway
import com.acegallery.session.CaptureMode
import com.acegallery.session.LiveStreamParams
import com.acegallery.settings.CameraHostProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class LiveViewViewModel @Inject constructor(
    private val session: CameraGateway,
    private val hostProvider: CameraHostProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    data class UiState(
        val busy: Boolean = false,
        val streaming: Boolean = false,
        val recording: Boolean = false,
        val capturing: Boolean = false,
        val captureMode: CaptureMode = CaptureMode.UNKNOWN,
        val liveParams: LiveStreamParams? = null,
        val decodedParams: LiveStreamParams? = null,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    val packets: Flow<ByteArray> get() = session.livePackets

    private val stopScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val lastPacketAt = AtomicLong(0L)
    @Volatile private var restarted = false
    @Volatile private var restartedAt = 0L
    private var watchdogJob: Job? = null

    init {
        viewModelScope.launch {
            session.captureMode.collect { mode ->
                _state.update { it.copy(captureMode = mode) }
            }
        }
        viewModelScope.launch {
            session.liveStreamParams.collect { params ->
                _state.update { it.copy(liveParams = params) }
            }
        }
        viewModelScope.launch {
            session.livePackets.collect {
                lastPacketAt.set(nowMs())
                if (restarted) {
                    restarted = false
                    restartedAt = 0L
                    _state.update { current ->
                        if (current.message == RECOVERING_MESSAGE) {
                            current.copy(message = null)
                        } else {
                            current
                        }
                    }
                }
            }
        }
    }

    fun start() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                withContext(ioDispatcher) {
                    session.connect(hostProvider.host())
                    session.startLiveStream()
                }
                lastPacketAt.set(nowMs())
                _state.update { it.copy(busy = false, streaming = true) }
                startWatchdog()
            } catch (exc: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        streaming = false,
                        message = exc.message ?: "无法打开实时画面",
                    )
                }
            }
        }
    }

    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
        restarted = false
        restartedAt = 0L
        stopScope.launch {
            runCatching { session.stopRecording() }
            runCatching { session.stopLiveStream() }
        }
        _state.update { it.copy(streaming = false, recording = false, capturing = false) }
    }

    fun takePhoto() {
        if (_state.value.recording || _state.value.capturing || _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, capturing = true, message = null) }
            runCatching {
                withContext(ioDispatcher) { session.takePicture() }
            }.onSuccess {
                _state.update { it.copy(busy = false, capturing = false, message = "已拍照") }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        busy = false,
                        capturing = false,
                        message = error.message ?: "拍照失败",
                    )
                }
            }
        }
    }

    fun toggleRecording() {
        if (_state.value.capturing || _state.value.busy) return
        viewModelScope.launch {
            val recording = _state.value.recording
            _state.update { it.copy(busy = true, message = null) }
            runCatching {
                withContext(ioDispatcher) {
                    if (recording) session.stopRecording() else session.startRecording()
                }
            }.onSuccess {
                _state.update {
                    it.copy(
                        busy = false,
                        recording = !recording,
                        message = if (recording) "已停止录制" else "开始录制",
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(busy = false, message = error.message ?: "录制失败") }
            }
        }
    }

    /** Decoder-reported size is a fallback; the camera's 8234 notification wins when present. */
    fun onVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (_state.value.liveParams != null) return
        _state.update { it.copy(decodedParams = LiveStreamParams(width, height, 0)) }
    }

    override fun onCleared() {
        // Do not cancel stopScope: stop() queues the camera's stop commands there, and
        // cancelling before they run would leave the camera recording after the user leaves.
        watchdogJob?.cancel()
        watchdogJob = null
        super.onCleared()
    }

    /**
     * Runs on a real IO scope on purpose: the loop delays forever, and on the test
     * dispatcher it would make advanceUntilIdle never finish.
     */
    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = stopScope.launch {
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                if (!_state.value.streaming) continue
                val now = nowMs()
                if (restarted && lastPacketAt.get() >= restartedAt) {
                    restarted = false
                    restartedAt = 0L
                    continue
                }
                when {
                    !restarted &&
                        shouldRestartLive(true, now, lastPacketAt.get(), false) -> {
                        restarted = true
                        restartedAt = now
                        _state.update { it.copy(message = RECOVERING_MESSAGE) }
                        runCatching { session.startLiveStream() }
                            .onFailure {
                                _state.update { state -> state.copy(message = EXIT_MESSAGE) }
                            }
                    }

                    restarted && now - restartedAt >= WATCHDOG_GRACE_MS -> {
                        _state.update { it.copy(message = EXIT_MESSAGE) }
                        break
                    }
                }
            }
        }
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    private companion object {
        const val WATCHDOG_TICK_MS = 250L
        const val WATCHDOG_GRACE_MS = 2_000L
        const val RECOVERING_MESSAGE = "画面中断，正在恢复…"
        const val EXIT_MESSAGE = "画面中断，请退出重进"
    }
}
