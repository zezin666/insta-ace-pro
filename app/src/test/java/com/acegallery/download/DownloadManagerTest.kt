package com.acegallery.download

import com.acegallery.model.CameraFile
import com.acegallery.model.CameraFileKind
import com.acegallery.network.CameraFileSource
import com.acegallery.network.CameraFileSourceProvider
import com.acegallery.storage.MediaStoreCollection
import com.acegallery.storage.MediaStoreWriter
import com.acegallery.storage.PendingMedia
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManagerTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("downloads").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `downloads queued files serially`() = runTest {
        val source = FakeFileSource()
        val manager = manager(FakeProvider(source), FakeWriter(), plenty)

        manager.enqueue(listOf(fileA, fileB))
        advanceUntilIdle()

        assertEquals(listOf(fileA.path, fileB.path), source.downloaded)
        assertTrue(manager.items.value.all { it.state == DownloadManager.State.Done })
    }

    @Test
    fun `resumes from existing part file`() = runTest {
        val source = FakeFileSource()
        val manager = manager(FakeProvider(source), FakeWriter(), plenty)
        File(tempDir, tempNameFor(fileA.path)).writeBytes(ByteArray(10))

        manager.enqueue(listOf(fileA))
        advanceUntilIdle()

        assertEquals(10L, source.lastOffset)
    }

    @Test
    fun `retries three times then fails`() = runTest {
        val source = FakeFileSource(failAlways = true)
        val manager = manager(FakeProvider(source), FakeWriter(), plenty)

        manager.enqueue(listOf(fileA))
        advanceUntilIdle()

        assertEquals(3, source.attempts)
        assertTrue(manager.items.value.single().state is DownloadManager.State.Failed)
    }

    @Test
    fun `retry fetches a fresh source each attempt`() = runTest {
        val source = FakeFileSource()
        val provider = FakeProvider(source, failFirst = true)
        val manager = manager(provider, FakeWriter(), plenty)

        manager.enqueue(listOf(fileA))
        advanceUntilIdle()

        assertEquals(2, provider.gets)
    }

    @Test
    fun `storage full deletes pending row`() = runTest {
        val writer = FakeWriter(failOnCopy = true)
        val manager = manager(FakeProvider(FakeFileSource()), writer, plenty)

        manager.enqueue(listOf(fileA))
        advanceUntilIdle()

        assertTrue(writer.deleted.isNotEmpty())
    }

    @Test
    fun `already downloaded is skipped`() = runTest {
        val source = FakeFileSource()
        val manager = manager(FakeProvider(source), FakeWriter(existing = true), plenty)

        manager.enqueue(listOf(fileA))
        advanceUntilIdle()

        assertTrue(source.downloaded.isEmpty())
        assertEquals(DownloadManager.State.Done, manager.items.value.single().state)
    }

    @Test
    fun `resumes after a partial write and failure`() = runTest {
        val source = PartialFailSource()
        val manager = manager(FakeProvider(source), FakeWriter(), plenty)

        manager.enqueue(listOf(fileA))
        advanceUntilIdle()

        assertEquals(10L, source.secondOffset)
        assertEquals(DownloadManager.State.Done, manager.items.value.single().state)
    }

    @Test
    fun `insufficient space fails without downloading`() = runTest {
        val source = FakeFileSource()
        val manager = manager(FakeProvider(source), FakeWriter(), FreeSpaceChecker { 10 })

        manager.enqueue(listOf(fileA))
        advanceUntilIdle()

        assertTrue(source.downloaded.isEmpty())
        val state = manager.items.value.single().state
        assertEquals("存储空间不足", (state as DownloadManager.State.Failed).reason)
    }

    private fun TestScope.manager(
        provider: CameraFileSourceProvider,
        writer: MediaStoreWriter,
        freeSpace: FreeSpaceChecker,
    ): DownloadManager = DownloadManager(
        sourceProvider = provider,
        writer = writer,
        tempDir = tempDir,
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        freeSpace = freeSpace,
    )

    private class FakeProvider(
        private val source: CameraFileSource,
        private val failFirst: Boolean = false,
    ) : CameraFileSourceProvider {
        var gets = 0
            private set

        override suspend fun get(): CameraFileSource {
            gets++
            if (failFirst && gets == 1) throw IOException("offline")
            return source
        }
    }

    private class FakeFileSource(
        private val failAlways: Boolean = false,
    ) : CameraFileSource {
        val downloaded = mutableListOf<String>()
        var lastOffset = -1L
        var attempts = 0
            private set

        override fun size(path: String): Long? = 100

        override fun download(
            path: String,
            dest: File,
            offset: Long,
            onProgress: (Long) -> Unit,
        ): Long {
            attempts++
            lastOffset = offset
            if (failAlways) throw IOException("offline")
            downloaded += path
            dest.writeBytes(ByteArray(10))
            onProgress(10)
            return 10
        }

        override fun readRange(path: String, offset: Long, length: Int): ByteArray = ByteArray(0)
    }

    private class PartialFailSource : CameraFileSource {
        var attempts = 0
            private set
        var secondOffset = -1L
            private set

        override fun size(path: String): Long? = 20

        override fun download(
            path: String,
            dest: File,
            offset: Long,
            onProgress: (Long) -> Unit,
        ): Long {
            attempts++
            if (attempts == 1) {
                dest.writeBytes(ByteArray(10))
                throw IOException("drop")
            }
            secondOffset = offset
            dest.writeBytes(ByteArray(10))
            onProgress(20)
            return 20
        }

        override fun readRange(path: String, offset: Long, length: Int): ByteArray = ByteArray(0)
    }

    private class FakeWriter(
        private val existing: Boolean = false,
        private val failOnCopy: Boolean = false,
    ) : MediaStoreWriter {        val deleted = mutableListOf<PendingMedia>()
        private var nextId = 1L

        override fun createPending(file: CameraFile): PendingMedia =
            PendingMedia(nextId++, MediaStoreCollection.VIDEO)

        override fun copyInto(pending: PendingMedia, source: File) {
            if (failOnCopy) throw IOException("no space")
        }

        override fun publish(pending: PendingMedia) = Unit

        override fun delete(pending: PendingMedia) {
            deleted += pending
        }

        override fun isDownloaded(file: CameraFile): Boolean = existing
    }

    private companion object {
        val plenty = FreeSpaceChecker { Long.MAX_VALUE }
        val fileA = file("a.mp4")
        val fileB = file("b.mp4")

        fun file(name: String) = CameraFile(
            uri = "/DCIM/$name",
            path = "/DCIM/$name",
            name = name,
            kind = CameraFileKind.VIDEO,
        )
    }
}
