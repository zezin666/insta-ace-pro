package com.acegallery.network

import java.io.File

interface CameraFileSource {
    fun size(path: String): Long?
    fun download(
        path: String,
        dest: File,
        offset: Long = 0,
        onProgress: (Long) -> Unit = {},
    ): Long
    fun readRange(path: String, offset: Long, length: Int): ByteArray
}

fun interface CameraFileSourceProvider {
    suspend fun get(): CameraFileSource
}
