package com.acegallery.export

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportTempFileTest {

    @Test
    fun `temporary file uses sha256 name under cache export directory`() {
        withCacheDirectory { cacheDir ->
            val sourcePath = "/DCIM/Insta360/IMG_0001.jpg"
            val first = createExportTempFile(cacheDir, sourcePath, 1_234L)
            val same = createExportTempFile(cacheDir, sourcePath, 1_234L)
            val differentTime = createExportTempFile(cacheDir, sourcePath, 1_235L)

            assertEquals(File(cacheDir, "export"), first.parentFile)
            assertTrue(first.parentFile!!.isDirectory)
            assertTrue(first.name.matches(Regex("[0-9a-f]{64}\\.tmp\\.jpg")))
            assertEquals(first.name, same.name)
            assertTrue(first.name != differentTime.name)
        }
    }

    @Test
    fun `temporary file helper deletes file after success`() {
        withCacheDirectory { cacheDir ->
            var tempFile: File? = null
            val value = withExportTempFile(cacheDir, "/photo.jpg", 10L) { file ->
                tempFile = file
                file.writeText("jpeg")
                "saved"
            }

            assertEquals("saved", value)
            assertFalse(tempFile!!.exists())
        }
    }

    @Test
    fun `temporary file helper deletes file after failure`() {
        withCacheDirectory { cacheDir ->
            var tempFile: File? = null
            var failed = false

            try {
                withExportTempFile(cacheDir, "/photo.jpg", 11L) { file ->
                    tempFile = file
                    file.writeText("partial jpeg")
                    throw IllegalStateException("render failed")
                }
            } catch (expected: IllegalStateException) {
                failed = true
                assertEquals("render failed", expected.message)
            }

            assertTrue(failed)
            assertFalse(tempFile!!.exists())
        }
    }

    @Test
    fun `temporary file deletion is idempotent`() {
        withCacheDirectory { cacheDir ->
            val file = createExportTempFile(cacheDir, "/photo.jpg", 12L)
            file.writeText("jpeg")

            assertTrue(deleteExportTempFile(file))
            assertFalse(file.exists())
            assertTrue(deleteExportTempFile(file))
        }
    }

    private inline fun withCacheDirectory(block: (File) -> Unit) {
        val cacheDir = Files.createTempDirectory("acegallery-export-test").toFile()
        try {
            block(cacheDir)
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}