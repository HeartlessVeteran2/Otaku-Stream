package com.otakustream.core.database.di

import android.content.Context
import androidx.room.Room
import com.otakustream.core.database.AppDatabase
import com.otakustream.core.database.playback.PlaybackProgressDao
import com.otakustream.core.database.playback.PlaybackProgressRepository
import com.otakustream.core.database.playback.PlaybackProgressRepositoryImpl
import com.otakustream.core.database.skip.SkipSegmentDao
import com.otakustream.core.database.skip.SkipSegmentRepository
import com.otakustream.core.database.skip.SkipSegmentRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseProvidesModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "otaku_stream.db").build()

    @Provides
    fun providePlaybackProgressDao(database: AppDatabase): PlaybackProgressDao = database.playbackProgressDao()

    @Provides
    fun provideSkipSegmentDao(database: AppDatabase): SkipSegmentDao = database.skipSegmentDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DatabaseBindsModule {

    @Binds
    abstract fun bindPlaybackProgressRepository(
        impl: PlaybackProgressRepositoryImpl,
    ): PlaybackProgressRepository

    @Binds
    abstract fun bindSkipSegmentRepository(
        impl: SkipSegmentRepositoryImpl,
    ): SkipSegmentRepository
}
