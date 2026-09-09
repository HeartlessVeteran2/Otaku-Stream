package com.otakustream.core.sources.stremio.di

import com.otakustream.core.sources.stremio.account.StremioAccountClient
import com.otakustream.core.sources.stremio.account.StremioAccountClientImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class StremioAccountModule {

    // @Singleton because the class it replaces was. The client itself holds no session state — the
    // authKey is passed in per call — but it wraps the shared OkHttpClient, and a new instance per
    // injection point would be pointless churn.
    @Binds
    @Singleton
    abstract fun bindStremioAccountClient(impl: StremioAccountClientImpl): StremioAccountClient
}
