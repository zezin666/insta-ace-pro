package com.acegallery.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acegallery.download.DownloadManager
import com.acegallery.download.DownloadQueue
import com.acegallery.model.CameraFile
import com.acegallery.session.CameraGateway
import com.acegallery.session.CameraState
import com.acegallery.thumbnail.ThumbnailLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GalleryUiState(
    val connection: CameraState = CameraState.Disconnected,
    val sections: List<GallerySection> = emptyList(),
    val filter: FileFilter = FileFilter.ALL,
    val isLoading: Boolean = false,
    val error: String? = null,
    val selected: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val files: List<CameraFile> = emptyList(),
)

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val session: CameraGateway,
    private val downloadManager: DownloadQueue,
    private val thumbnailLoader: ThumbnailLoader,
) : ViewModel() {

    private val _state = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    val thumbnails: ThumbnailLoader get() = thumbnailLoader
    val downloads: StateFlow<List<DownloadManager.Item>> get() = downloadManager.items

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                session.connect()
                val files = session.listFiles()
                _state.value = _state.value.copy(
                    connection = session.state.value,
                    files = files,
                    sections = GalleryGrouping.group(files, _state.value.filter),
                    isLoading = false,
                    error = null,
                )
            } catch (exc: Exception) {
                _state.value = _state.value.copy(
                    connection = session.state.value,
                    isLoading = false,
                    error = exc.message ?: "加载失败",
                )
            }
        }
    }

    fun setFilter(filter: FileFilter) {
        _state.value = _state.value.copy(
            filter = filter,
            sections = GalleryGrouping.group(_state.value.files, filter),
        )
    }

    fun disconnect() {
        session.disconnect()
        _state.value = _state.value.copy(
            connection = CameraState.Disconnected,
            files = emptyList(),
            sections = emptyList(),
        )
    }
}
