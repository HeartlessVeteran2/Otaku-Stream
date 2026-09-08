package com.otakustream.feature.sources.di

import com.otakustream.core.sources.api.VideoSource
import com.otakustream.feature.sources.SourceBootstrapper
import com.otakustream.feature.sources.SourceBootstrapperImpl
import com.otakustream.feature.sources.SourceRegistry
import com.otakustream.feature.sources.SourceRepository
import dagger.Binds
import dagger.Module
import dagger.multibindings.Multibinds
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SourceRepositoryModule {

    @Binds
    abstract fun bindSourceRepository(impl: SourceRegistry): SourceRepository

    // @Singleton because the class it replaces was: the whole point of SourceBootstrapper is that
    // the rehydrate runs exactly once per process, and a binding without it would hand each
    // injection point its own instance and its own "once".
    @Binds
    @Singleton
    abstract fun bindSourceBootstrapper(impl: SourceBootstrapperImpl): SourceBootstrapper

    @Multibinds
    abstract fun bindVideoSourceSet(): Set<VideoSource>
}
