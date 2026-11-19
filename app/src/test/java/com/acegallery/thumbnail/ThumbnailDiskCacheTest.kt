package com.acegallery.thumbnail

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ThumbnailDiskCacheTest {

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
    fun `disk cache round trips thumbnail`() {
        val cache = ThumbnailDiskCache(tempDir, PassthroughCodec)
        val thumb = Thumbnail(2, 2, intArrayOf(1, 2, 3, 4))

        cache.put("k", thumb)
        val loaded = cache.get("k")

        assertEquals(2, loaded?.width)
        assertEquals(2, loaded?.height)
        assertArrayEquals(intArrayOf(1, 2, 3, 4), loaded?.argb)
    }

    @Test
    fun `invalid magic returns null and deletes file`() {
        val cache = ThumbnailDiskCache(tempDir, PassthroughCodec)
        cache.put("k", Thumbnail(1, 1, intArrayOf(1)))
        val file = tempDir.listFiles()!!.single()
        RandomAccessFile(file, "rw").use { it.writeBytes("NOPE") }

        assertNull(cache.get("k"))
        assertFalse(file.exists())
    }

    @Test
    fun `disk cache evicts least recently modified file`() {
        val cache = ThumbnailDiskCache(tempDir, PassthroughCodec, maxBytes = 40)
        cache.put("a", Thumbnail(2, 2, intArrayOf(1, 2, 3, 4)))
        val firstFile = tempDir.listFiles()!!.single()
        firstFile.setLastModified(System.currentTimeMillis() - 60_000)

        cache.put("b", Thumbnail(2, 2, intArrayOf(5, 6, 7, 8)))

        assertNull(cache.get("a"))
        assertNotNull(cache.get("b"))
        assertEquals(1, tempDir.listFiles()?.size)
    }

    private object PassthroughCodec : ThumbnailCodec {
        override fun encode(thumb: Thumbnail): ByteArray =
            ByteBuffer.allocate(8 + thumb.argb.size * 4).apply {
                putInt(thumb.width)
                putInt(thumb.height)
                thumb.argb.forEach { putInt(it) }
            }.array()

        override fun decode(data: ByteArray): Thumbnail? = runCatching {
            val buffer = ByteBuffer.wrap(data)
            val width = buffer.int
            val height = buffer.int
            val pixels = IntArray(width * height) { buffer.int }
            Thumbnail(width, height, pixels)
        }.getOrNull()
    }
}