package com.acegallery.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acegallery.di.IoDispatcher
import com.acegallery.session.CameraGateway
import com.acegallery.session.CaptureMode
import com.acegallery.session.LiveStreamParams
import com.acegallery.settings.CameraHostProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    private val stopScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
    }

    fun start() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                withContext(ioDispatcher) {
                    session.connect(hostProvider.host())
                    session.startLiveStream()
                }
                _state.update { it.copy(busy = false, streaming = true) }
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
}
