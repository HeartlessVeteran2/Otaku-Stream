package com.otakustream.app.di

import com.otakustream.core.common.IoDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Satisfies @IoDispatcher, which core/common declares.
//
// Here rather than beside the annotation because a library module declaring an @InstallIn module
// needs the Hilt annotation processor to emit the metadata this component reads — which would mean
// adding the plugin and kapt to core/common, a module deliberately kept without either. The
// qualifier itself needs no processing, so it is shared and this is declared once.
@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
