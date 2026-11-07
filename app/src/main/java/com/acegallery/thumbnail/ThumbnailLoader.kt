package com.acegallery.thumbnail

import com.acegallery.model.CameraFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ThumbnailLoader(
    private val sources: List<ThumbnailSource>,
    private val diskCache: ThumbnailDiskCache? = null,
    private val maxBytes: Long = 32L * 1024 * 1024,
    private val maxDimension: Int = 256,
) {

    private val memory = LinkedHashMap<String, Thumbnail>(16, 0.75f, true)
    private var memoryBytes = 0L
    private val mutex = Mutex()

    suspend fun load(file: CameraFile): Thumbnail? {
        val key = file.path
        memoryGet(key)?.let { return it }
        diskCache?.get(key)?.let { cached ->
            val thumb = cached.scaledTo(maxDimension)
            memoryPut(key, thumb)
            return thumb
        }
        for (source in sources) {
            val loaded = source.load(file) ?: continue
            val thumb = loaded.scaledTo(maxDimension)
            diskCache?.put(key, thumb)
            memoryPut(key, thumb)
            return thumb
        }
        return null
    }

    private suspend fun memoryGet(key: String): Thumbnail? =
        mutex.withLock { synchronized(memory) { memory[key] } }

    private suspend fun memoryPut(key: String, thumb: Thumbnail) {
        mutex.withLock {
            synchronized(memory) {
                memory.remove(key)?.let { memoryBytes -= it.byteSize() }
                memory[key] = thumb
                memoryBytes += thumb.byteSize()

                val iterator = memory.entries.iterator()
                while (memoryBytes > maxBytes && iterator.hasNext()) {
                    val eldest = iterator.next()
                    memoryBytes -= eldest.value.byteSize()
                    iterator.remove()
                }
            }
        }
    }
}
