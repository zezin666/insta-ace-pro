package com.acegallery.network

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

object CameraHttpClientFactory {
    fun create(handle: CameraNetworkHandle): OkHttpClient =
        OkHttpClient.Builder()
            .socketFactory(BoundSocketFactory(handle))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
}
