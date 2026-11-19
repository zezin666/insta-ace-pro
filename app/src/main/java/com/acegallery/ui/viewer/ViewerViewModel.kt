package com.acegallery.ui.viewer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acegallery.di.IoDispatcher
import com.acegallery.download.DownloadQueue
import com.acegallery.model.CameraFile
import com.acegallery.session.CameraGateway
import com.acegallery.thumbnail.ThumbnailLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class ViewerViewModel private constructor(
    val session: CameraGateway,
    private val downloadQueue: DownloadQueue,
    val thumbnailLoader: ThumbnailLoader,
    private val photoCache: PhotoCache,
    private val ioDispatcher: CoroutineDispatcher,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : ViewModel() {

    data class ExportState(
        val busy: Boolean = false,
        val message: String? = null,
        val liveSource: File? = null,
        val liveFile: CameraFile? = null,
        val exportSource: File? = null,
        val exportFile: CameraFile? = null,
    )

    @Inject
    constructor(
        session: CameraGateway,
        downloadQueue: DownloadQueue,
        thumbnailLoader: ThumbnailLoader,
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        session = session,
        downloadQueue = downloadQueue,
        thumbnailLoader = thumbnailLoader,
        photoCache = PhotoCache(session, context.cacheDir),
        ioDispatcher = ioDispatcher,
        marker = Unit,
    )

    internal constructor(
        session: CameraGateway,
        downloadQueue: DownloadQueue,
        thumbnailLoader: ThumbnailLoader,
        photoCache: PhotoCache,
        ioDispatcher: CoroutineDispatcher,
    ) : this(session, downloadQueue, thumbnailLoader, photoCache, ioDispatcher, Unit)

    private val _exportState = MutableStateFlow(ExportState())
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()
    private var prepareJob: Job? = null

    fun download(file: CameraFile) {
        downloadQueue.enqueue(listOf(file))
    }

    fun prepareExport(file: CameraFile, live: Boolean) {
        if (prepareJob?.isActive == true) return
        _exportState.update { it.copy(busy = true, message = null) }
        prepareJob = viewModelScope.launch {
            try {
                val source = withContext(ioDispatcher) { photoCache.get(file) }
                _exportState.update { state ->
                    if (live) {
                        state.copy(
                            busy = false,
                            liveSource = source,
                            liveFile = file,
                        )
                    } else {
                        state.copy(
                            busy = false,
                            exportSource = source,
                            exportFile = file,
                        )
                    }
                }
            } catch (exc: CancellationException) {
                throw exc
            } catch (exc: Exception) {
                _exportState.update {
                    it.copy(
                        busy = false,
                        message = exc.message ?: "准备文件失败",
                    )
                }
            }
        }
    }

    fun dismissPhotoExport() {
        _exportState.update {
            it.copy(exportSource = null, exportFile = null)
        }
    }

    fun dismissLiveExport() {
        _exportState.update {
            it.copy(liveSource = null, liveFile = null)
        }
    }

    fun onPhotoExported(downscaled: Boolean) {
        _exportState.update {
            it.copy(
                exportSource = null,
                exportFile = null,
                message = if (downscaled) {
                    "已导出带水印照片（图片较大，已降低导出分辨率）"
                } else {
                    "已导出带水印照片"
                },
            )
        }
    }

    fun onLiveExported() {
        _exportState.update {
            it.copy(
                liveSource = null,
                liveFile = null,
                message = "已导出 Live 图到相册",
            )
        }
    }

    fun clearExportMessage(expected: String) {
        _exportState.update {
            if (it.message == expected) it.copy(message = null) else it
        }
    }
}
