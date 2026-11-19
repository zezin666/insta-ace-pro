package com.acegallery.ui.viewer

import com.acegallery.MainDispatcherRule
import com.acegallery.camera.CameraTransport
import com.acegallery.download.DownloadManager
import com.acegallery.download.DownloadQueue
import com.acegallery.model.CameraFile
import com.acegallery.model.CameraFileKind
import com.acegallery.network.CameraFileSource
import com.acegallery.network.CameraFileSourceProvider
import com.acegallery.repository.CameraFileRepository
import com.acegallery.session.CameraGateway
import com.acegallery.session.CameraState
import com.acegallery.session.CaptureMode
import com.acegallery.session.LiveStreamParams
import com.acegallery.thumbnail.ThumbnailLoader
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ViewerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("viewer-view-model").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `download enqueues file`() {
        val queue = FakeQueue()
        val viewModel = viewModel(cache = cache(FakeSource()), queue = queue)

        viewModel.download(file)

        assertEquals(listOf(file), queue.enqueued)
    }

    @Test
    fun `prepare export exposes busy and photo source state`() = runTest {
        val cache = cache(FakeSource())
        val viewModel = viewModel(cache)

        viewModel.prepareExport(file, live = false)

        assertTrue(viewModel.exportState.value.busy)
        advanceUntilIdle()
        val state = viewModel.exportState.value
        assertFalse(state.busy)
        assertEquals(file, state.exportFile)
        assertEquals(cache.cacheFileFor(file), state.exportSource)
        assertTrue(state.exportSource?.exists() == true)
        assertNull(state.message)
    }

    @Test
    fun `prepare live export exposes live source state`() = runTest {
        val cache = cache(FakeSource())
        val viewModel = viewModel(cache)

        viewModel.prepareExport(file, live = true)

        assertTrue(viewModel.exportState.value.busy)
        advanceUntilIdle()
        val state = viewModel.exportState.value
        assertFalse(state.busy)
        assertEquals(file, state.liveFile)
        assertEquals(cache.cacheFileFor(file), state.liveSource)
        assertNull(state.exportFile)
    }

    @Test
    fun `prepare export failure shows message and clears busy`() = runTest {
        val viewModel = viewModel(cache(FakeSource(failure = IOException("相机断开"))))

        viewModel.prepareExport(file, live = false)

        assertTrue(viewModel.exportState.value.busy)
        advanceUntilIdle()
        val state = viewModel.exportState.value
        assertFalse(state.busy)
        assertEquals("相机断开", state.message)
        assertNull(state.exportSource)
        assertNull(state.exportFile)
    }

    @Test
    fun `photo export completion clears source and shows message`() = runTest {
        val viewModel = viewModel(cache(FakeSource()))

        viewModel.prepareExport(file, live = false)
        advanceUntilIdle()
        viewModel.onPhotoExported(downscaled = false)

        val state = viewModel.exportState.value
        assertNull(state.exportSource)
        assertNull(state.exportFile)
        assertEquals("已导出带水印照片", state.message)

        viewModel.clearExportMessage("已导出带水印照片")
        assertNull(viewModel.exportState.value.message)
    }

    private fun viewModel(
        cache: PhotoCache,
        queue: FakeQueue = FakeQueue(),
    ): ViewerViewModel = ViewerViewModel(
        session = FakeSession(),
        downloadQueue = queue,
        thumbnailLoader = ThumbnailLoader(emptyList()),
        photoCache = cache,
        ioDispatcher = mainDispatcherRule.dispatcher,
    )

    private fun cache(source: FakeSource): PhotoCache =
        PhotoCache(FakeProvider(source), tempDir)

    private fun advanceUntilIdle() {
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    }

    private class FakeProvider(private val source: CameraFileSource) : CameraFileSourceProvider {
        override suspend fun get(): CameraFileSource = source
    }

    private class FakeSource(
        private val failure: Throwable? = null,
    ) : CameraFileSource {
        private val bytes = ByteArray(64) { it.toByte() }

        override fun size(path: String): Long? = bytes.size.toLong()

        override fun download(
            path: String,
            dest: File,
            offset: Long,
            onProgress: (Long) -> Unit,
        ): Long {
            failure?.let { throw it }
            dest.writeBytes(bytes)
            onProgress(bytes.size.toLong())
            return bytes.size.toLong()
        }

        override fun readRange(path: String, offset: Long, length: Int): ByteArray = ByteArray(0)
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
