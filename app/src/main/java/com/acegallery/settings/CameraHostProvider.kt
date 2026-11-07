package com.acegallery.settings

fun interface CameraHostProvider {
    suspend fun host(): String
}
