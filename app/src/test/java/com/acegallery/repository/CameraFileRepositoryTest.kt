package com.acegallery.repository

import com.acegallery.camera.CameraCommands
import com.acegallery.camera.CameraTransport
import com.acegallery.proto.CameraProto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFileRepositoryTest {

    @Test
    fun `collects all pages and dedupes`() = runTest {
        val transport = FakeTransport(
            listOf(
                resp(listOf("/DCIM/a.mp4"), total = 3),
                resp(listOf("/DCIM/b.jpg", "/DCIM/a.mp4"), total = 3),
                resp(listOf("/DCIM/c.mp4"), total = 3),
                resp(emptyList(), total = 3),
            ),
        )
        val files = CameraFileRepository(transport, pageSize = 2).listFiles()
        assertEquals(listOf("a.mp4", "b.jpg", "c.mp4"), files.map { it.name })
    }

    @Test
    fun `stops after empty first page`() = runTest {
        val files = CameraFileRepository(FakeTransport(listOf(resp(emptyList(), 0)))).listFiles()
        assertTrue(files.isEmpty())
    }

    @Test
    fun `filters hidden files`() = runTest {
        val files = CameraFileRepository(
            FakeTransport(
                listOf(
                    resp(
                        listOf("/DCIM/a.mp4", "/DCIM/LRV_a.lrv", "/DCIM/THM_a.thm"),
                        total = 3,
                    ),
                ),
            ),
        ).listFiles()
        assertEquals(listOf("a.mp4"), files.map { it.name })
    }

    @Test
    fun `stops after the empty page`() = runTest {
        val transport = FakeTransport(
            listOf(
                resp(listOf("/DCIM/a.mp4"), total = 1),
                resp(emptyList(), total = 1),
            ),
        )
        val files = CameraFileRepository(transport, pageSize = 1).listFiles()
        assertEquals(1, files.size)
        assertEquals(2, transport.calls)
    }

    @Test
    fun `does not truncate when total underestimates`() = runTest {
        val transport = FakeTransport(
            listOf(
                resp(listOf("/DCIM/a.mp4", "/DCIM/b.jpg"), total = 1),
                resp(listOf("/DCIM/c.mp4"), total = 1),
                resp(emptyList(), total = 1),
            ),
        )
        val files = CameraFileRepository(transport, pageSize = 2).listFiles()
        assertEquals(3, files.size)
    }

    private class FakeTransport(
        private val pages: List<CameraProto.GetFileListResp>,
    ) : CameraTransport {
        private var index = 0
        var calls = 0
            private set

        override fun request(command: Int, payload: ByteArray, timeoutMs: Long): ByteArray {
            require(command == CameraCommands.GET_FILE_LIST)
            calls++
            val page = pages.getOrElse(index) { CameraProto.GetFileListResp.getDefaultInstance() }
            index++
            return page.toByteArray()
        }

        override fun close() = Unit
    }

    private fun resp(uris: List<String>, total: Int): CameraProto.GetFileListResp =
        CameraProto.GetFileListResp.newBuilder()
            .addAllUri(uris)
            .setTotalCount(total)
            .build()
}
