package com.acegallery.network

import java.io.File
import java.nio.file.Files
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class HttpCameraFileSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: HttpCameraFileSource
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        source = HttpCameraFileSource(
            baseUrl = server.url("/").toString().trimEnd('/'),
            client = OkHttpClient(),
        )
        tempDir = Files.createTempDirectory("camera-source").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun `head returns content length`() {
        server.enqueue(
            MockResponse().setResponseCode(200).addHeader("Content-Length", "1000"),
        )
        assertEquals(1000L, source.size("/DCIM/a.mp4"))
    }

    @Test
    fun `download resumes with range header`() {
        server.enqueue(MockResponse().setResponseCode(206).setBody("world"))
        val dest = File(tempDir, "a.part").apply { writeText("hello") }

        source.download("/DCIM/a.mp4", dest, offset = 5)

        assertEquals("helloworld", dest.readText())
        assertEquals("bytes=5-", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `server ignoring range restarts from zero`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("abc"))
        val dest = File(tempDir, "a.part").apply { writeText("old") }

        source.download("/DCIM/a.mp4", dest, offset = 3)

        assertEquals("abc", dest.readText())
    }

    @Test
    fun `read range returns requested bytes`() {
        server.enqueue(MockResponse().setResponseCode(206).setBody("0123456789"))
        assertArrayEquals("0123".toByteArray(), source.readRange("/DCIM/a.jpg", 0, 4))
    }

    @Test
    fun `range header supports offsets beyond 2gb`() {
        server.enqueue(MockResponse().setResponseCode(206).setBody("x"))
        val dest = File(tempDir, "big.part")

        source.download("/DCIM/big.mp4", dest, offset = 5_000_000_000L)

        assertEquals("bytes=5000000000-", server.takeRequest().getHeader("Range"))
    }
}
