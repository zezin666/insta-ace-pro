package com.acegallery.di

import javax.inject.Qualifier

/** Dispatcher used for blocking camera work; injectable so JVM tests stay deterministic. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
