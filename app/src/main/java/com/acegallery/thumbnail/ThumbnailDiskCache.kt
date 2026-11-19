package com.acegallery.thumbnail

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

class ThumbnailDiskCache(
    private val dir: File,
    private val codec: ThumbnailCodec,
    private val maxBytes: Long = 128L * 1024 * 1024,
) {

    fun get(key: String): Thumbnail? {
        val file = fileFor(key)
        if (!file.exists()) return null
        val thumb = runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val magic = ByteArray(MAGIC.size)
                input.readFully(magic)
                check(magic.contentEquals(MAGIC)) { "invalid magic" }
                val width = input.readInt()
                val height = input.readInt()
                check(width > 0 && height > 0) { "invalid dimensions" }
                val decoded = codec.decode(input.readBytes())
                    ?: error("thumbnail decode failed")
                check(decoded.width == width && decoded.height == height) {
                    "thumbnail dimensions mismatch"
                }
                decoded
            }
        }.getOrNull()
        if (thumb == null) {
            file.delete()
        }
        return thumb
    }

    fun put(key: String, thumb: Thumbnail) {
        runCatching {
            dir.mkdirs()
            val encoded = codec.encode(thumb)
            DataOutputStream(fileFor(key).outputStream().buffered()).use { output ->
                output.write(MAGIC)
                output.writeInt(thumb.width)
                output.writeInt(thumb.height)
                output.write(encoded)
            }
            trim()
        }
    }

    private fun trim() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }

    private fun fileFor(key: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.thumb")
    }

    private companion object {
        val MAGIC = byteArrayOf(0x41, 0x43, 0x54, 0x31)
    }
}