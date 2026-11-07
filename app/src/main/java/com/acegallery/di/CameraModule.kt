package com.acegallery.di

import android.content.Context
import com.acegallery.camera.CameraProtocolClient
import com.acegallery.camera.CameraTransport
import com.acegallery.network.AndroidCameraNetworkFinder
import com.acegallery.network.BoundSocketFactory
import com.acegallery.network.CameraFileSourceProvider
import com.acegallery.network.CameraHttpClientFactory
import com.acegallery.network.CameraNetworkFinder
import com.acegallery.network.CameraNetworkHandle
import com.acegallery.session.CameraGateway
import com.acegallery.session.CameraSession
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object CameraModule {

    @Provides
    @Singleton
    fun provideNetworkFinder(@ApplicationContext context: Context): CameraNetworkFinder =
        AndroidCameraNetworkFinder(context)

    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    fun provideTransportFactory(scope: CoroutineScope): (Socket) -> CameraTransport =
        { socket -> CameraProtocolClient(socket, scope) }

    @Provides
    fun provideSocketConnector(): (CameraNetworkHandle, String, Int) -> Socket =
        { handle, host, port ->
            BoundSocketFactory(handle).createSocket().apply {
                connect(InetSocketAddress(host, port), 5_000)
            }
        }

    @Provides
    fun provideHttpClientFactory(): (CameraNetworkHandle) -> OkHttpClient =
        { handle -> CameraHttpClientFactory.create(handle) }

    @Provides
    @Singleton
    fun provideCameraGateway(session: CameraSession): CameraGateway = session

    @Provides
    @Singleton
    fun provideCameraFileSourceProvider(session: CameraSession): CameraFileSourceProvider = session
}
