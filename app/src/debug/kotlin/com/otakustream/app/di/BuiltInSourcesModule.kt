package com.otakustream.app.di

import com.otakustream.core.sources.api.VideoSource
import com.otakustream.sources.example.ExampleVideoSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

// Debug-only: the Example source (public-domain Blender clips) exists to exercise the source
// pipeline during development. It must never ship — in release the built-in set is empty (see
// VideoSourceSetModule) so a fresh install gets the real guided "add your first source" flow
// instead of demo content.
@Module
@InstallIn(SingletonComponent::class)
object BuiltInSourcesModule {

    @Provides
    @IntoSet
    fun provideExampleVideoSource(): VideoSource = ExampleVideoSource()
}
