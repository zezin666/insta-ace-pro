package com.acegallery.storage

import com.acegallery.model.CameraFile
import java.io.File

data class PendingMedia(
    val rowId: Long,
    val collection: MediaStoreCollection,
)

interface MediaStoreWriter {
    fun createPending(file: CameraFile): PendingMedia
    fun copyInto(pending: PendingMedia, source: File)
    fun publish(pending: PendingMedia)
    fun delete(pending: PendingMedia)
    fun isDownloaded(file: CameraFile): Boolean
}
