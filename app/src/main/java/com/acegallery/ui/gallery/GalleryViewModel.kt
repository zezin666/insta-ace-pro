package com.acegallery.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acegallery.download.DownloadManager
import com.acegallery.download.DownloadQueue
import com.acegallery.model.CameraFile
import com.acegallery.session.CameraGateway
import com.acegallery.session.CameraState
import com.acegallery.settings.CameraHostProvider
import com.acegallery.thumbnail.ThumbnailLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val cameraHostProvider: CameraHostProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    val thumbnails: ThumbnailLoader get() = thumbnailLoader
    val downloads: StateFlow<List<DownloadManager.Item>> get() = downloadManager.items

    private val _downloadedPaths = MutableStateFlow<Set<String>>(emptySet())
    val downloadedPaths: StateFlow<Set<String>> = _downloadedPaths.asStateFlow()

    init {
        viewModelScope.launch {
            downloadManager.items.collect { items ->
                val done = items
                    .filter { it.state is DownloadManager.State.Done }
                    .map { it.file.path }
                if (done.isNotEmpty()) {
                    _downloadedPaths.value = _downloadedPaths.value + done
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                session.connect(cameraHostProvider.host())
                val files = session.listFiles()
                _state.value = _state.value.copy(
                    connection = session.state.value,
                    files = files,
                    sections = GalleryGrouping.group(files, _state.value.filter),
                    isLoading = false,
                    error = null,
                )
                markDownloaded(files)
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

    fun toggleSelection(path: String) {
        val current = _state.value
        val selected = if (path in current.selected) {
            current.selected - path
        } else {
            current.selected + path
        }
        _state.value = current.copy(
            selected = selected,
            selectionMode = selected.isNotEmpty(),
        )
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selected = emptySet(), selectionMode = false)
    }

    fun downloadSelected() {
        val selected = _state.value.files.filter { it.path in _state.value.selected }
        if (selected.isEmpty()) return
        downloadManager.enqueue(selected)
        clearSelection()
    }

    fun enqueue(files: List<CameraFile>) {
        if (files.isNotEmpty()) downloadManager.enqueue(files)
    }

    fun disconnect() {
        session.disconnect()
        _state.value = _state.value.copy(
            connection = CameraState.Disconnected,
            files = emptyList(),
            sections = emptyList(),
        )
    }

    private fun markDownloaded(files: List<CameraFile>) {
        viewModelScope.launch {
            val downloaded = withContext(Dispatchers.IO) {
                files.filter { downloadManager.isDownloaded(it) }
                    .map { it.path }
                    .toSet()
            }
            _downloadedPaths.value = downloaded
        }
    }
}
