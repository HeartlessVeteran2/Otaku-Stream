package com.otakustream.app.di

import com.otakustream.core.sources.api.VideoSource
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

// Declares the built-in VideoSource multibinding so it exists even when nothing contributes to it.
// Release builds ship no built-in sources (the debug-only Example source is the sole contributor,
// in app/src/debug); without this declaration the release Hilt graph would fail to provide the
// empty Set the SourceRegistry injects.
@Module
@InstallIn(SingletonComponent::class)
abstract class VideoSourceSetModule {

    @Multibinds
    abstract fun builtInVideoSources(): Set<VideoSource>
}
