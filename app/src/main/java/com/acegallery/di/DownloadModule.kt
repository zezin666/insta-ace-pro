package com.acegallery.di

import android.content.Context
import android.os.StatFs
import com.acegallery.download.DownloadManager
import com.acegallery.download.DownloadQueue
import com.acegallery.download.FreeSpaceChecker
import com.acegallery.network.CameraFileSourceProvider
import com.acegallery.storage.MediaStoreWriter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

@Module
@InstallIn(SingletonComponent::class)
object DownloadModule {

    @Provides
    @Singleton
    fun provideFreeSpace(@ApplicationContext context: Context): FreeSpaceChecker =
        FreeSpaceChecker { StatFs(context.filesDir.absolutePath).availableBytes }

    @Provides
    @Singleton
    fun provideDownloadManager(
        sourceProvider: CameraFileSourceProvider,
        writer: MediaStoreWriter,
        @ApplicationContext context: Context,
        scope: CoroutineScope,
        freeSpace: FreeSpaceChecker,
    ): DownloadManager = DownloadManager(
        sourceProvider = sourceProvider,
        writer = writer,
        tempDir = File(context.filesDir, "downloads"),
        scope = scope,
        freeSpace = freeSpace,
    )

    @Provides
    @Singleton
    fun provideDownloadQueue(manager: DownloadManager): DownloadQueue = manager
}
