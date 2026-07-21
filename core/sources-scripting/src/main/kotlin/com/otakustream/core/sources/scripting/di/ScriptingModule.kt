package com.otakustream.core.sources.scripting.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ScriptingModule {

    // The app-wide shared OkHttp client (SingletonComponent) reused by every network client —
    // AniList, AniSkip, Stremio, scripted sources, source catalog. Timeouts bound any slow or
    // hung endpoint so it can't occupy a thread indefinitely.
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
}
