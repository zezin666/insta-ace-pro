package com.acegallery.ui.viewer

import com.acegallery.model.CameraFile
import com.acegallery.network.CameraFileSourceProvider
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class PhotoCache(
    private val sourceProvider: CameraFileSourceProvider,
    private val cacheDir: File,
) {

    suspend fun get(file: CameraFile): File {
        val target = cacheFileFor(file)
        val source = sourceProvider.get()
        val expected = source.size(file.path)
        if (target.exists() && (expected == null || target.length() == expected)) {
            return target
        }

        target.parentFile?.mkdirs()
        val part = partFileFor(target)
        if (expected != null && part.exists() && part.length() > expected) {
            part.delete()
        }

        if (expected == null || part.length() != expected) {
            val offset = if (part.exists()) part.length() else 0L
            source.download(file.path, part, offset = offset)
        }

        val actual = part.length()
        if (expected != null && actual != expected) {
            throw IOException("照片下载不完整：$actual/$expected B")
        }
        if (!part.exists()) {
            throw IOException("照片下载失败")
        }

        Files.deleteIfExists(target.toPath())
        Files.move(
            part.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
        )
        part.delete()
        return target
    }

    fun cacheFileFor(file: CameraFile): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(file.path.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val extension = file.name.substringAfterLast('.', "")
        val name = if (extension.isEmpty()) hash else "$hash.$extension"
        return File(File(cacheDir, "photos"), name)
    }

    private fun partFileFor(target: File): File {
        val hash = target.name.substringBeforeLast('.', target.name)
        return File(target.parentFile, "$hash.part")
    }
}
