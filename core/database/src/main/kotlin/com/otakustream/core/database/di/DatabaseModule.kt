package com.otakustream.core.database.di

import android.content.Context
import androidx.room.Room
import com.otakustream.core.database.AppDatabase
import com.otakustream.core.database.MIGRATION_6_7
import com.otakustream.core.database.MIGRATION_7_8
import com.otakustream.core.database.MIGRATION_8_9
import com.otakustream.core.database.MIGRATION_10_11
import com.otakustream.core.database.MIGRATION_9_10
import com.otakustream.core.database.MIGRATION_11_12
import com.otakustream.core.database.MIGRATION_12_13
import com.otakustream.core.database.download.DownloadDao
import com.otakustream.core.database.download.DownloadRepository
import com.otakustream.core.database.download.DownloadRepositoryImpl
import com.otakustream.core.database.library.LibraryDao
import com.otakustream.core.database.library.LibraryRepository
import com.otakustream.core.database.library.LibraryRepositoryImpl
import com.otakustream.core.database.library.WatchHistoryDao
import com.otakustream.core.database.mangayomi.MangayomiSourceDao
import com.otakustream.core.database.mangayomi.MangayomiSourceRepository
import com.otakustream.core.database.mangayomi.MangayomiSourceRepositoryImpl
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
import com.otakustream.core.database.stremio.StremioAccountStore
import com.otakustream.core.database.stremio.StremioAccountStoreImpl
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
            // Explicit migrations preserve the user's library, addons, and tokens across upgrades;
            // an upgrade with a missing path still fails fast (never a silent wipe). Schema JSONs
            // under core/database/schemas are the baseline migrations are authored against.
            .addMigrations(
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
            )
            // Pre-6 builds shipped before schemas were exported, so no authored migration exists to
            // reach them. Rather than crash those users on update ("migration from N to 9 required
            // but not found"), wipe *only* when coming from those specific old versions; 6→9 keeps
            // real migrations and any future gap still fails fast.
            .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5)
            .fallbackToDestructiveMigrationOnDowngrade(true)
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

    @Provides
    fun provideMangayomiSourceDao(database: AppDatabase): MangayomiSourceDao = database.mangayomiSourceDao()

    @Provides
    fun provideDownloadDao(database: AppDatabase): DownloadDao = database.downloadDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DatabaseBindsModule {

    // @Singleton is load-bearing here, not decoration: the store holds the in-memory authKey
    // StateFlow every screen observes. A second instance would have its own, so a sign-out on one
    // screen would be invisible to another until the process restarted.
    @Binds
    @Singleton
    abstract fun bindStremioAccountStore(impl: StremioAccountStoreImpl): StremioAccountStore

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

    // Scoped, unlike the rest here. LibraryRepositoryImpl holds the history-generation counter that
    // invalidates a pending undo after a Clear history, and a counter handed out per injection point
    // is no counter at all — see the class for the full argument.
    @Binds
    @Singleton
    abstract fun bindLibraryRepository(
        impl: LibraryRepositoryImpl,
    ): LibraryRepository

    @Binds
    abstract fun bindDownloadRepository(
        impl: DownloadRepositoryImpl,
    ): DownloadRepository

    @Binds
    abstract fun bindTrackingRepository(
        impl: TrackingRepositoryImpl,
    ): TrackingRepository

    @Binds
    abstract fun bindStremioRepository(
        impl: StremioRepositoryImpl,
    ): StremioRepository

    @Binds
    abstract fun bindMangayomiSourceRepository(
        impl: MangayomiSourceRepositoryImpl,
    ): MangayomiSourceRepository
}
