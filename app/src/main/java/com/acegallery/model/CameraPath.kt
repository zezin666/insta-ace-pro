package com.acegallery.model

import java.net.URI

object CameraPath {

    fun pathOf(uri: String): String {
        val path = if (uri.contains("://")) {
            runCatching { URI(uri).path }.getOrNull().orEmpty().ifEmpty { uri }
        } else {
            uri
        }
        return if (path.startsWith("/")) path else "/$path"
    }

    fun fileName(path: String): String = path.substringAfterLast('/')
}
