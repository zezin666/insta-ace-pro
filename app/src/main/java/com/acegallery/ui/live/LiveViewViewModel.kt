package com.acegallery.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acegallery.session.CameraGateway
import com.acegallery.settings.CameraHostProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
) : ViewModel() {

    data class UiState(
        val busy: Boolean = false,
        val streaming: Boolean = false,
        val recording: Boolean = false,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    val packets: Flow<ByteArray> get() = session.livePackets

    private val stopScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                withContext(Dispatchers.IO) {
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
        _state.update { it.copy(streaming = false, recording = false) }
    }

    fun takePhoto() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            runCatching {
                withContext(Dispatchers.IO) { session.takePicture() }
            }.onSuccess {
                _state.update { it.copy(busy = false, message = "已拍照") }
            }.onFailure { error ->
                _state.update { it.copy(busy = false, message = error.message ?: "拍照失败") }
            }
        }
    }

    fun toggleRecording() {
        viewModelScope.launch {
            val recording = _state.value.recording
            _state.update { it.copy(busy = true, message = null) }
            runCatching {
                withContext(Dispatchers.IO) {
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
}
