package com.acegallery.network

import android.net.Network
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

object CameraHttpClientFactory {
    fun create(network: Network): OkHttpClient =
        OkHttpClient.Builder()
            .socketFactory(BoundSocketFactory(network))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
}
