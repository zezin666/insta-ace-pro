package com.acegallery.storage

import com.acegallery.model.CameraFile
import com.acegallery.model.CameraFileKind

enum class MediaStoreCollection { VIDEO, IMAGE, DOWNLOAD }

data class MediaStoreTarget(
    val collection: MediaStoreCollection,
    val relativePath: String,
    val mimeType: String,
)

object MediaStoreTargets {
    fun forFile(file: CameraFile): MediaStoreTarget {
        val extension = file.name.substringAfterLast('.', "").lowercase()
        return when (file.kind) {
            CameraFileKind.VIDEO -> MediaStoreTarget(
                collection = MediaStoreCollection.VIDEO,
                relativePath = "DCIM/Insta360",
                mimeType = videoMime(extension),
            )
            CameraFileKind.PHOTO -> MediaStoreTarget(
                collection = MediaStoreCollection.IMAGE,
                relativePath = "DCIM/Insta360",
                mimeType = photoMime(extension),
            )
            CameraFileKind.OTHER -> MediaStoreTarget(
                collection = MediaStoreCollection.DOWNLOAD,
                relativePath = "Download/Insta360",
                mimeType = "application/octet-stream",
            )
        }
    }

    private fun videoMime(extension: String): String = when (extension) {
        "mp4", "insv", "insp" -> "video/mp4"
        "mov" -> "video/quicktime"
        else -> "video/*"
    }

    private fun photoMime(extension: String): String = when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "dng" -> "image/x-adobe-dng"
        "png" -> "image/png"
        else -> "image/*"
    }
}
