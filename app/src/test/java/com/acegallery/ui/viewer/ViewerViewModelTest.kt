package com.acegallery.ui.viewer

import com.acegallery.camera.CameraTransport
import com.acegallery.download.DownloadManager
import com.acegallery.download.DownloadQueue
import com.acegallery.model.CameraFile
import com.acegallery.model.CameraFileKind
import com.acegallery.network.CameraFileSource
import com.acegallery.repository.CameraFileRepository
import com.acegallery.session.CameraGateway
import com.acegallery.session.CameraState
import com.acegallery.session.CaptureMode
import com.acegallery.session.LiveStreamParams
import com.acegallery.thumbnail.ThumbnailLoader
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerViewModelTest {

    @Test
    fun `download enqueues file`() {
        val queue = FakeQueue()
        val viewModel = ViewerViewModel(FakeSession(), queue, ThumbnailLoader(emptyList()))

        viewModel.download(file)

        assertEquals(listOf(file), queue.enqueued)
    }

    private class FakeSession : CameraGateway {
        private val _state = MutableStateFlow<CameraState>(CameraState.Connected("192.168.42.1"))
        override val state: StateFlow<CameraState> = _state
        override val captureMode = MutableStateFlow(CaptureMode.UNKNOWN)
        override val liveStreamParams = MutableStateFlow<LiveStreamParams?>(null)
        override suspend fun setCaptureMode(mode: CaptureMode) = Unit
        override suspend fun connect(host: String) = Unit
        override fun disconnect() = Unit
        override suspend fun listFiles(): List<CameraFile> = emptyList()
        override fun repository(): CameraFileRepository = error("not used")
        override fun transport(): CameraTransport = error("not used")
        override fun httpClient(): OkHttpClient = error("not used")
        override suspend fun get(): CameraFileSource = error("not used")
    }

    private class FakeQueue : DownloadQueue {
        private val _items = MutableStateFlow<List<DownloadManager.Item>>(emptyList())
        override val items: StateFlow<List<DownloadManager.Item>> = _items
        val enqueued = mutableListOf<CameraFile>()

        override fun enqueue(files: List<CameraFile>) {
            enqueued += files
        }

        override fun cancelAll() = Unit
        override suspend fun isDownloaded(file: CameraFile): Boolean = false
    }

    private companion object {
        val file = CameraFile(
            uri = "/DCIM/a.jpg",
            path = "/DCIM/a.jpg",
            name = "a.jpg",
            kind = CameraFileKind.PHOTO,
            capturedAt = Instant.parse("2026-01-01T10:00:00Z"),
        )
    }
}
