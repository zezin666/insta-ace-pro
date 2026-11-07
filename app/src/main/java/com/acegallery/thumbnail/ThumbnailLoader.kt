package com.acegallery.thumbnail

import com.acegallery.model.CameraFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ThumbnailLoader(
    private val sources: List<ThumbnailSource>,
    private val diskCache: ThumbnailDiskCache? = null,
    maxEntries: Int = 200,
) {

    private val memory = object : LinkedHashMap<String, Thumbnail>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Thumbnail>?,
        ): Boolean = size > maxEntries
    }
    private val mutex = Mutex()

    suspend fun load(file: CameraFile): Thumbnail? {
        val key = file.path
        memoryGet(key)?.let { return it }
        diskCache?.get(key)?.let { thumb ->
            memoryPut(key, thumb)
            return thumb
        }
        for (source in sources) {
            val thumb = source.load(file) ?: continue
            diskCache?.put(key, thumb)
            memoryPut(key, thumb)
            return thumb
        }
        return null
    }

    private suspend fun memoryGet(key: String): Thumbnail? =
        mutex.withLock { synchronized(memory) { memory[key] } }

    private suspend fun memoryPut(key: String, thumb: Thumbnail) {
        mutex.withLock { synchronized(memory) { memory[key] = thumb } }
    }
}
