package com.acegallery.ui.viewer

import com.acegallery.model.CameraFile
import com.acegallery.model.CameraFileKind
import com.acegallery.network.CameraFileSource
import com.acegallery.network.CameraFileSourceProvider
import com.acegallery.network.HttpCameraFileSource
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PhotoCacheTest {

    private lateinit var tempDir: File
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("photo-cache").toFile()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
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

    @Test
    fun `interrupted download resumes from part file with range request`() = runTest {
        val content = ByteArray(256) { it.toByte() }
        val firstChunkSize = 73
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", content.size.toString()),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(content, 0, firstChunkSize))
                .setHeader("Content-Length", content.size.toString()),
        )
        val source = HttpCameraFileSource(
            baseUrl = server.url("/").toString().trimEnd('/'),
            client = OkHttpClient(),
        )
        val cache = PhotoCache(FakeProvider(source), tempDir)

        val firstError = runCatching { cache.get(photo) }.exceptionOrNull()
        assertTrue("expected interrupted download, got $firstError", firstError is IOException)

        val target = cache.cacheFileFor(photo)
        val part = File(target.parentFile, "${target.name.substringBeforeLast('.')}.part")
        val resumedFrom = part.length()
        assertTrue("part file should contain partial data", resumedFrom in 1 until content.size.toLong())
        assertFalse(target.exists())

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", content.size.toString()),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setBody(
                    Buffer().write(
                        content,
                        resumedFrom.toInt(),
                        content.size - resumedFrom.toInt(),
                    ),
                ),
        )

        val result = cache.get(photo)

        assertArrayEquals(content, result.readBytes())
        assertFalse(part.exists())
        assertEquals("HEAD", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
        assertEquals("HEAD", server.takeRequest().method)
        assertEquals("bytes=$resumedFrom-", server.takeRequest().getHeader("Range"))
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

