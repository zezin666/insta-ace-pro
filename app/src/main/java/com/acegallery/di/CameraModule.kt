package com.acegallery.di

import android.content.Context
import com.acegallery.camera.CameraProtocolClient
import com.acegallery.network.AndroidCameraNetworkFinder
import com.acegallery.network.BoundSocketFactory
import com.acegallery.network.CameraFileSourceProvider
import com.acegallery.network.CameraHttpClientFactory
import com.acegallery.network.CameraNetworkFinder
import com.acegallery.session.CameraGateway
import com.acegallery.session.CameraHttpClientProvider
import com.acegallery.session.CameraSession
import com.acegallery.session.CameraSocketConnector
import com.acegallery.session.CameraTransportFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.net.InetSocketAddress
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    fun provideTransportFactory(scope: CoroutineScope): CameraTransportFactory =
        CameraTransportFactory { socket -> CameraProtocolClient(socket, scope) }

    @Provides
    fun provideSocketConnector(): CameraSocketConnector =
        CameraSocketConnector { handle, host, port ->
            BoundSocketFactory(handle).createSocket().apply {
                connect(InetSocketAddress(host, port), 5_000)
            }
        }

    @Provides
    fun provideHttpClientFactory(): CameraHttpClientProvider =
        CameraHttpClientProvider { handle -> CameraHttpClientFactory.create(handle) }

    @Provides
    @Singleton
    fun provideCameraGateway(session: CameraSession): CameraGateway = session

    @Provides
    @Singleton
    fun provideCameraFileSourceProvider(session: CameraSession): CameraFileSourceProvider = session
}
