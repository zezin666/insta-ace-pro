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

    private companion object {
        val file = CameraFile(
            uri = "/DCIM/a.jpg",
            path = "/DCIM/a.jpg",
            name = "a.jpg",
            kind = CameraFileKind.PHOTO,
        )
    }
}
