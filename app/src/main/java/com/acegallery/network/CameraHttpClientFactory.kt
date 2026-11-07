package com.acegallery.network

import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

object CameraHttpClientFactory {
    fun create(handle: CameraNetworkHandle): OkHttpClient =
        OkHttpClient.Builder()
            .socketFactory(BoundSocketFactory(handle))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // The camera's embedded HTTP server misbehaves when a connection is
            // reused (it can return a file body where a status line is expected).
            .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
            .retryOnConnectionFailure(true)
            .build()
}
