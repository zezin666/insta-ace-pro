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
import com.acegallery.thumbnail.ThumbnailLoader
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SelectionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `long press enters selection mode`() = runTest {
        val viewModel = viewModelWith(listOf(fileA))

        viewModel.toggleSelection(fileA.path)

        assertTrue(viewModel.state.value.selectionMode)
        assertEquals(setOf(fileA.path), viewModel.state.value.selected)
    }

    @Test
    fun `download selected enqueues only selected`() = runTest {
        val queue = FakeDownloadQueue()
        val viewModel = viewModelWith(listOf(fileA, fileB), queue)
        viewModel.refresh()
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleSelection(fileA.path)
        viewModel.downloadSelected()

        assertEquals(listOf(fileA), queue.enqueued)
    }

    @Test
    fun `clear selection exits mode`() = runTest {
        val viewModel = viewModelWith(listOf(fileA))
        viewModel.toggleSelection(fileA.path)

        viewModel.clearSelection()

        assertFalse(viewModel.state.value.selectionMode)
        assertTrue(viewModel.state.value.selected.isEmpty())
    }

    private fun viewModelWith(
        files: List<CameraFile>,
        queue: FakeDownloadQueue = FakeDownloadQueue(),
    ): GalleryViewModel = GalleryViewModel(
        session = FakeSession(files),
        downloadManager = queue,
        thumbnailLoader = ThumbnailLoader(emptyList()),
    )

    private class FakeSession(private val files: List<CameraFile>) : CameraGateway {
        private val _state = MutableStateFlow<CameraState>(CameraState.Disconnected)
        override val state: StateFlow<CameraState> = _state

        override suspend fun connect(host: String) {
            _state.value = CameraState.Connected(host)
        }

        override fun disconnect() {
            _state.value = CameraState.Disconnected
        }

        override suspend fun listFiles(): List<CameraFile> = files
        override fun repository(): CameraFileRepository = error("not used")
        override fun transport(): CameraTransport = error("not used")
        override fun httpClient(): OkHttpClient = error("not used")
        override suspend fun get(): CameraFileSource = error("not used")
    }

    private class FakeDownloadQueue : DownloadQueue {
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
        val fileA = CameraFile(
            uri = "/DCIM/a.mp4",
            path = "/DCIM/a.mp4",
            name = "a.mp4",
            kind = CameraFileKind.VIDEO,
            capturedAt = Instant.parse("2026-01-01T10:00:00Z"),
        )
        val fileB = CameraFile(
            uri = "/DCIM/b.jpg",
            path = "/DCIM/b.jpg",
            name = "b.jpg",
            kind = CameraFileKind.PHOTO,
            capturedAt = Instant.parse("2026-01-02T10:00:00Z"),
        )
    }
}
