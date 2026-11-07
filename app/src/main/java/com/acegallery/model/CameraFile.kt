package com.acegallery.model

import java.time.Instant

enum class CameraFileKind { PHOTO, VIDEO, OTHER }

data class CameraFile(
    val uri: String,
    val path: String,
    val name: String,
    val kind: CameraFileKind,
    val sizeBytes: Long? = null,
    val capturedAt: Instant? = null,
)
