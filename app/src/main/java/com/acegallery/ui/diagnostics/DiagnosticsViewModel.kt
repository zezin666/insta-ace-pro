package com.acegallery.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acegallery.session.CameraGateway
import com.acegallery.thumbnail.ThumbnailLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DiagnosticResult(
    val name: String,
    val detail: String,
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val session: CameraGateway,
    private val thumbnails: ThumbnailLoader,
) : ViewModel() {

    private val _results = MutableStateFlow<List<DiagnosticResult>>(emptyList())
    val results: StateFlow<List<DiagnosticResult>> = _results.asStateFlow()

    fun run() {
        viewModelScope.launch {
            val out = mutableListOf<DiagnosticResult>()
            try {
                session.connect()
                out += DiagnosticResult("连接", session.state.value.toString())
            } catch (exc: Exception) {
                out += DiagnosticResult("连接", exc.message ?: "失败")
                _results.value = out
                return@launch
            }

            try {
                val files = session.listFiles()
                out += DiagnosticResult("文件列表（命令 13）", "${files.size} 个文件")
                val first = files.firstOrNull()
                if (first != null) {
                    val size = runCatching { session.get().size(first.path) }.getOrNull()
                    out += DiagnosticResult("HTTP HEAD", size?.toString() ?: "失败")

                    val thumb = runCatching { thumbnails.load(first) }.getOrNull()
                    out += DiagnosticResult(
                        "缩略图（命令 11）",
                        if (thumb != null) "${thumb.width}×${thumb.height}" else "失败",
                    )
                }
            } catch (exc: Exception) {
                out += DiagnosticResult("文件列表（命令 13）", exc.message ?: "失败")
            }
            _results.value = out
        }
    }
}
