package com.acegallery.di

import android.content.Context
import com.acegallery.storage.AndroidMediaStoreWriter
import com.acegallery.storage.MediaStoreWriter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideMediaStoreWriter(@ApplicationContext context: Context): MediaStoreWriter =
        AndroidMediaStoreWriter(context)
}
