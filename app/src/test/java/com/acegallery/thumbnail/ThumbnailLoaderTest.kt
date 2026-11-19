package com.acegallery.thumbnail

import com.acegallery.model.CameraFile
import com.acegallery.model.CameraFileKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailLoaderTest {

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
    fun `loader stops after job cancellation`() = runTest {
        val thumb = Thumbnail(1, 1, intArrayOf(0xFF00FF00.toInt()))
        val nextSource = CountingSource(thumb)
        lateinit var job: Job
        job = launch {
            val loader = ThumbnailLoader(
                sources = listOf(
                    object : ThumbnailSource {
                        override suspend fun load(file: CameraFile): Thumbnail {
                            job.cancel()
                            return thumb
                        }
                    },
                    nextSource,
                ),
            )
            val failure = runCatching { loader.load(file) }.exceptionOrNull()
            assertTrue(failure is CancellationException)
        }

        job.join()

        assertEquals(0, nextSource.calls)
        assertTrue(job.isCancelled)
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