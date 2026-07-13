package com.otakustream.core.database.di

import android.content.Context
import androidx.room.Room
import com.otakustream.core.database.AppDatabase
import com.otakustream.core.database.library.LibraryDao
import com.otakustream.core.database.library.LibraryRepository
import com.otakustream.core.database.library.LibraryRepositoryImpl
import com.otakustream.core.database.library.WatchHistoryDao
import com.otakustream.core.database.playback.PlaybackProgressDao
import com.otakustream.core.database.playback.PlaybackProgressRepository
import com.otakustream.core.database.playback.PlaybackProgressRepositoryImpl
import com.otakustream.core.database.scripted.ScriptedSourceDao
import com.otakustream.core.database.scripted.ScriptedSourceRepository
import com.otakustream.core.database.scripted.ScriptedSourceRepositoryImpl
import com.otakustream.core.database.skip.SkipSegmentDao
import com.otakustream.core.database.skip.SkipSegmentRepository
import com.otakustream.core.database.skip.SkipSegmentRepositoryImpl
import com.otakustream.core.database.stremio.StremioDao
import com.otakustream.core.database.stremio.StremioRepository
import com.otakustream.core.database.stremio.StremioRepositoryImpl
import com.otakustream.core.database.tracking.TrackingDao
import com.otakustream.core.database.tracking.TrackingRepository
import com.otakustream.core.database.tracking.TrackingRepositoryImpl
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
        Room.databaseBuilder(context, AppDatabase::class.java, "otaku_stream.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun providePlaybackProgressDao(database: AppDatabase): PlaybackProgressDao = database.playbackProgressDao()

    @Provides
    fun provideSkipSegmentDao(database: AppDatabase): SkipSegmentDao = database.skipSegmentDao()

    @Provides
    fun provideScriptedSourceDao(database: AppDatabase): ScriptedSourceDao = database.scriptedSourceDao()

    @Provides
    fun provideLibraryDao(database: AppDatabase): LibraryDao = database.libraryDao()

    @Provides
    fun provideWatchHistoryDao(database: AppDatabase): WatchHistoryDao = database.watchHistoryDao()

    @Provides
    fun provideTrackingDao(database: AppDatabase): TrackingDao = database.trackingDao()

    @Provides
    fun provideStremioDao(database: AppDatabase): StremioDao = database.stremioDao()
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

    @Binds
    abstract fun bindScriptedSourceRepository(
        impl: ScriptedSourceRepositoryImpl,
    ): ScriptedSourceRepository

    @Binds
    abstract fun bindLibraryRepository(
        impl: LibraryRepositoryImpl,
    ): LibraryRepository

    @Binds
    abstract fun bindTrackingRepository(
        impl: TrackingRepositoryImpl,
    ): TrackingRepository

    @Binds
    abstract fun bindStremioRepository(
        impl: StremioRepositoryImpl,
    ): StremioRepository
}
