package com.otakustream.feature.sources.di

import com.otakustream.core.sources.api.VideoSource
import com.otakustream.feature.sources.SourceRegistry
import com.otakustream.feature.sources.SourceRepository
import dagger.Binds
import dagger.Module
import dagger.multibindings.Multibinds
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SourceRepositoryModule {

    @Binds
    abstract fun bindSourceRepository(impl: SourceRegistry): SourceRepository

    @Multibinds
    abstract fun bindVideoSourceSet(): Set<VideoSource>
}
