package com.acegallery.thumbnail

import com.acegallery.model.CameraFile
import com.acegallery.model.CameraFileKind
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ThumbnailLoaderTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("thumbs").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `loader falls back to next source`() = runTest {
        val thumb = Thumbnail(1, 1, intArrayOf(0xFF00FF00.toInt()))
        val loader = ThumbnailLoader(
            listOf(FakeSource(null), FakeSource(thumb)),
        )
        assertSame(thumb, loader.load(file))
    }

    @Test
    fun `loader caches second call`() = runTest {
        val source = CountingSource(Thumbnail(1, 1, intArrayOf(0)))
        val loader = ThumbnailLoader(listOf(source))
        loader.load(file)
        loader.load(file)
        assertEquals(1, source.calls)
    }

    @Test
    fun `disk cache round trips thumbnail`() {
        val cache = ThumbnailDiskCache(tempDir)
        val thumb = Thumbnail(2, 2, intArrayOf(1, 2, 3, 4))
        cache.put("k", thumb)
        val loaded = cache.get("k")
        assertEquals(2, loaded?.width)
        assertArrayEquals(intArrayOf(1, 2, 3, 4), loaded?.argb)
    }

    @Test
    fun `loader downscales large thumbnail`() = runTest {
        val big = Thumbnail(512, 512, IntArray(512 * 512))
        val loader = ThumbnailLoader(listOf(FakeSource(big)))

        val result = loader.load(file)

        assertTrue(result!!.width <= 256)
        assertTrue(result.height <= 256)
    }

    @Test
    fun `loader evicts over byte budget`() = runTest {
        val source = PerFileSource()
        val loader = ThumbnailLoader(listOf(source), maxBytes = 4)

        loader.load(file)
        loader.load(fileB)
        loader.load(file)

        assertEquals(3, source.calls)
    }

    @Test
    fun `disk cache evicts oldest`() {
        val cache = ThumbnailDiskCache(tempDir, maxBytes = 40)
        cache.put("a", Thumbnail(2, 2, intArrayOf(1, 2, 3, 4)))
        cache.put("b", Thumbnail(2, 2, intArrayOf(5, 6, 7, 8)))

        assertTrue((tempDir.listFiles()?.size ?: 0) <= 1)
    }

    private class FakeSource(private val thumb: Thumbnail?) : ThumbnailSource {
        override suspend fun load(file: CameraFile): Thumbnail? = thumb
    }

    private class CountingSource(private val thumb: Thumbnail) : ThumbnailSource {
        var calls = 0
            private set

        override suspend fun load(file: CameraFile): Thumbnail? {
            calls++
            return thumb
        }
    }

    private class PerFileSource : ThumbnailSource {
        var calls = 0
            private set

        override suspend fun load(file: CameraFile): Thumbnail? {
            calls++
            return Thumbnail(1, 1, intArrayOf(file.name.length))
        }
    }

    private companion object {
        val file = CameraFile(
            uri = "/DCIM/a.jpg",
            path = "/DCIM/a.jpg",
            name = "a.jpg",
            kind = CameraFileKind.PHOTO,
        )
        val fileB = file.copy(path = "/DCIM/b.jpg", name = "b.jpg")
    }
}
