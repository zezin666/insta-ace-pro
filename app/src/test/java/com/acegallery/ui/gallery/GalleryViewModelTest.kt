package com.acegallery.ui.gallery

import com.acegallery.MainDispatcherRule
import com.acegallery.camera.CameraTransport
import com.acegallery.download.DownloadManager
import com.acegallery.download.DownloadQueue
import com.acegallery.model.CameraFile
import com.acegallery.model.CameraFileKind
import com.acegallery.network.CameraFileSource
import com.acegallery.repository.CameraFileRepository
import com.acegallery.session.CameraGateway
import com.acegallery.session.CameraState
import com.acegallery.settings.CameraHostProvider
import com.acegallery.thumbnail.ThumbnailLoader
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GalleryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `refresh exposes files`() = runTest {
        val viewModel = viewModel(listOf(photo))
        viewModel.refresh()
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.state.value.sections.sumOf { it.files.size })
    }

    @Test
    fun `refresh error shows message`() = runTest {
        val viewModel = viewModel(error = "未连接")
        viewModel.refresh()
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals("未连接", viewModel.state.value.error)
    }

    @Test
    fun `filter switches sections`() = runTest {
        val viewModel = viewModel(listOf(photo, video))
        viewModel.refresh()
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        viewModel.setFilter(FileFilter.VIDEOS)
        assertEquals(
            listOf(video.name),
            viewModel.state.value.sections.flatMap { it.files }.map { it.name },
        )
    }

    @Test
    fun `refresh connects to configured host`() = runTest {
        val session = FakeSession(emptyList(), null)
        val viewModel = GalleryViewModel(
            session = session,
            downloadManager = FakeDownloadQueue(),
            thumbnailLoader = ThumbnailLoader(emptyList()),
            cameraHostProvider = CameraHostProvider { "10.0.0.1" },
        )
        viewModel.refresh()
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals("10.0.0.1", session.lastHost)
    }

    private fun viewModel(
        files: List<CameraFile> = emptyList(),
        error: String? = null,
    ): GalleryViewModel = GalleryViewModel(
        session = FakeSession(files, error),
        downloadManager = FakeDownloadQueue(),
        thumbnailLoader = ThumbnailLoader(emptyList()),
        cameraHostProvider = CameraHostProvider { "192.168.42.1" },
    )

    private class FakeSession(
        private val files: List<CameraFile>,
        private val error: String?,
    ) : CameraGateway {
        private val _state = MutableStateFlow<CameraState>(CameraState.Disconnected)
        override val state: StateFlow<CameraState> = _state
        var lastHost: String? = null
            private set

        override suspend fun connect(host: String) {
            lastHost = host
            _state.value = CameraState.Connected(host)
        }

        override fun disconnect() {
            _state.value = CameraState.Disconnected
        }

        override suspend fun listFiles(): List<CameraFile> {
            error?.let { throw IllegalStateException(it) }
            return files
        }

        override fun repository(): CameraFileRepository = error("not used")
        override fun transport(): CameraTransport = error("not used")
        override fun httpClient(): OkHttpClient = error("not used")
        override suspend fun get(): CameraFileSource = error("not used")
    }

    private class FakeDownloadQueue : DownloadQueue {
        override val items: StateFlow<List<DownloadManager.Item>> = MutableStateFlow(emptyList())
        override fun enqueue(files: List<CameraFile>) = Unit
        override fun cancelAll() = Unit
        override suspend fun isDownloaded(file: CameraFile): Boolean = false
    }

    private companion object {
        val photo = CameraFile(
            uri = "/DCIM/a.jpg",
            path = "/DCIM/a.jpg",
            name = "a.jpg",
            kind = CameraFileKind.PHOTO,
            capturedAt = Instant.parse("2026-01-01T10:00:00Z"),
        )
        val video = CameraFile(
            uri = "/DCIM/b.mp4",
            path = "/DCIM/b.mp4",
            name = "b.mp4",
            kind = CameraFileKind.VIDEO,
            capturedAt = Instant.parse("2026-01-02T10:00:00Z"),
        )
    }
}
