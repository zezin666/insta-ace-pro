package com.acegallery.ui.viewer

import com.acegallery.model.CameraFile
import com.acegallery.model.CameraFileKind
import com.acegallery.network.CameraFileSource
import com.acegallery.network.CameraFileSourceProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PhotoCacheTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("photo-cache").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `photo cache downloads once`() = runTest {
        val source = FakeSource()
        val cache = PhotoCache(FakeProvider(source), tempDir)

        val first = cache.get(photo)
        val second = cache.get(photo)

        assertEquals(first, second)
        assertEquals(1, source.downloads)
    }

    @Test
    fun `photo cache redownloads when size differs`() = runTest {
        val source = FakeSource()
        val cache = PhotoCache(FakeProvider(source), tempDir)
        cache.cacheFileFor(photo).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(3))
        }

        cache.get(photo)

        assertEquals(1, source.downloads)
    }

    private class FakeProvider(private val source: CameraFileSource) : CameraFileSourceProvider {
        override suspend fun get(): CameraFileSource = source
    }

    private class FakeSource : CameraFileSource {
        var downloads = 0

        override fun size(path: String): Long? = 100

        override fun download(
            path: String,
            dest: File,
            offset: Long,
            onProgress: (Long) -> Unit,
        ): Long {
            downloads++
            dest.writeBytes(ByteArray(100))
            return 100
        }

        override fun readRange(path: String, offset: Long, length: Int): ByteArray = ByteArray(0)
    }

    private companion object {
        val photo = CameraFile(
            uri = "/DCIM/a.jpg",
            path = "/DCIM/a.jpg",
            name = "a.jpg",
            kind = CameraFileKind.PHOTO,
        )
    }
}
