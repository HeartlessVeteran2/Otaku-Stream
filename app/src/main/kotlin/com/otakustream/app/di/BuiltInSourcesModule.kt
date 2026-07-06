package com.otakustream.app.di

import com.otakustream.core.sources.api.VideoSource
import com.otakustream.sources.example.ExampleVideoSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
object BuiltInSourcesModule {

    @Provides
    @IntoSet
    fun provideExampleVideoSource(): VideoSource = ExampleVideoSource()
}
