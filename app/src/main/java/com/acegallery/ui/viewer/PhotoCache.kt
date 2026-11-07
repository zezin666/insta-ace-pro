package com.acegallery.ui.viewer

import com.acegallery.model.CameraFile
import com.acegallery.network.CameraFileSourceProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotoCache(
    private val sourceProvider: CameraFileSourceProvider,
    private val cacheDir: File,
) {

    suspend fun get(file: CameraFile): File = withContext(Dispatchers.IO) {
        val target = cacheFileFor(file)
        val source = sourceProvider.get()
        val expected = source.size(file.path)
        if (target.exists() && (expected == null || target.length() == expected)) {
            return@withContext target
        }
        target.parentFile?.mkdirs()
        source.download(file.path, target, offset = 0)
        target
    }

    fun cacheFileFor(file: CameraFile): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(file.path.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val extension = file.name.substringAfterLast('.', "")
        val name = if (extension.isEmpty()) hash else "$hash.$extension"
        return File(File(cacheDir, "photos"), name)
    }
}
