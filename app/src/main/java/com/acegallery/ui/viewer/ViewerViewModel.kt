package com.acegallery.ui.viewer

import androidx.lifecycle.ViewModel
import com.acegallery.download.DownloadQueue
import com.acegallery.model.CameraFile
import com.acegallery.session.CameraGateway
import com.acegallery.thumbnail.ThumbnailLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ViewerViewModel @Inject constructor(
    val session: CameraGateway,
    private val downloadQueue: DownloadQueue,
    val thumbnailLoader: ThumbnailLoader,
) : ViewModel() {

    fun download(file: CameraFile) {
        downloadQueue.enqueue(listOf(file))
    }
}
