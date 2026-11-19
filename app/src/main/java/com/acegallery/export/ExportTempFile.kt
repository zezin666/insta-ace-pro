package com.acegallery.export

import java.io.File
import java.io.IOException
import java.security.MessageDigest

private const val TEMP_DIRECTORY = "export"
private const val TEMP_SUFFIX = ".tmp.jpg"

/**
 * Owns a per-attempt JPEG file under `cacheDir/export`. The block runs synchronously and the file
 * is removed on both success and failure, including [OutOfMemoryError].
 */
internal inline fun <T> withExportTempFile(
    cacheDir: File,
    sourcePath: String,
    timestampMillis: Long,
    block: (File) -> T,
): T {
    val tempFile = createExportTempFile(cacheDir, sourcePath, timestampMillis)
    try {
        return block(tempFile)
    } finally {
        deleteExportTempFile(tempFile)
    }
}

/** Creates a unique path whose name is the SHA-256 digest of the source path and attempt time. */
internal fun createExportTempFile(
    cacheDir: File,
    sourcePath: String,
    timestampMillis: Long,
): File {
    val directory = File(cacheDir, TEMP_DIRECTORY)
    if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
        throw IOException("导出失败，无法创建临时目录")
    }
    val digest = sha256Hex(sourcePath + timestampMillis)
    return File(directory, digest + TEMP_SUFFIX)
}

/** Deletes [file], treating an already-missing file as already cleaned up. */
internal fun deleteExportTempFile(file: File): Boolean = try {
    !file.exists() || file.delete()
} catch (_: SecurityException) {
    false
}

private fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val digits = "0123456789abcdef"
    return buildString(digest.size * 2) {
        for (byte in digest) {
            val unsigned = byte.toInt() and 0xff
            append(digits[unsigned ushr 4])
            append(digits[unsigned and 0x0f])
        }
    }
}