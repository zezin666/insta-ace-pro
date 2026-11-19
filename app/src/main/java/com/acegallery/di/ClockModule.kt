package com.acegallery.di

import android.os.SystemClock
import com.acegallery.ui.live.Clock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ClockModule {

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock { SystemClock.elapsedRealtime() }
}
