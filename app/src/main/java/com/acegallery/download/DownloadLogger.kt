package com.acegallery.download

fun interface DownloadLogger {
    fun warn(tag: String, message: String, error: Throwable)
}
