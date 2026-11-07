package com.acegallery.thumbnail

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

class ThumbnailDiskCache(private val dir: File) {

    fun get(key: String): Thumbnail? = runCatching {
        val file = fileFor(key)
        if (!file.exists()) return@runCatching null
        DataInputStream(file.inputStream().buffered()).use { input ->
            val width = input.readInt()
            val height = input.readInt()
            val pixels = IntArray(width * height)
            for (i in pixels.indices) pixels[i] = input.readInt()
            Thumbnail(width, height, pixels)
        }
    }.getOrNull()

    fun put(key: String, thumb: Thumbnail) {
        runCatching {
            dir.mkdirs()
            DataOutputStream(fileFor(key).outputStream().buffered()).use { output ->
                output.writeInt(thumb.width)
                output.writeInt(thumb.height)
                thumb.argb.forEach { output.writeInt(it) }
            }
        }
    }

    private fun fileFor(key: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.thumb")
    }
}
