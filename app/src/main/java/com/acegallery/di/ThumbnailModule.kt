package com.acegallery.di

import android.content.Context
import com.acegallery.session.CameraSession
import com.acegallery.thumbnail.ExifThumbnailSource
import com.acegallery.thumbnail.HttpThumbnailSource
import com.acegallery.thumbnail.ProtocolThumbnailSource
import com.acegallery.thumbnail.ThumbnailDiskCache
import com.acegallery.thumbnail.ThumbnailLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ThumbnailModule {

    @Provides
    @Singleton
    fun provideThumbnailLoader(
        session: CameraSession,
        @ApplicationContext context: Context,
    ): ThumbnailLoader = ThumbnailLoader(
        sources = listOf(
            ProtocolThumbnailSource { runCatching { session.transport() }.getOrNull() },
            HttpThumbnailSource(session),
            ExifThumbnailSource(session),
        ),
        diskCache = ThumbnailDiskCache(File(context.cacheDir, "thumbs")),
    )
}
