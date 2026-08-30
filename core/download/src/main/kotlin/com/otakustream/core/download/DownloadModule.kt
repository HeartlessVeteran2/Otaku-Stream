package com.otakustream.core.download

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.Executors
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@androidx.annotation.OptIn(UnstableApi::class)
object DownloadModule {

    @Provides
    @Singleton
    fun provideDownloadManager(
        @ApplicationContext context: Context,
        store: DownloadStore,
        headers: DownloadHeaders,
    ): DownloadManager {
        // The desktop User-Agent, for the same reason the image client carries it: the hosts these
        // streams come from answer the stock OkHttp/ExoPlayer agent with a 403. A download that
        // 403s is indistinguishable to the user from one that simply failed.
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(DOWNLOAD_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)

        // Headers are attached per request rather than baked into the factory. There is one
        // factory for every download this app will ever make, and the headers differ per source —
        // the shared factory was the reason a source needing a Referer would 403.
        val withHeaders = ResolvingDataSource.Factory(httpDataSourceFactory) { dataSpec ->
            val extra = headers.headersFor(dataSpec.uri.toString())
            if (extra.isEmpty()) dataSpec else dataSpec.withRequestHeaders(extra)
        }

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(store.cache)
            .setUpstreamDataSourceFactory(withHeaders)

        val downloaderFactory: DownloaderFactory =
            DefaultDownloaderFactory(cacheDataSourceFactory, Executors.newFixedThreadPool(DOWNLOAD_THREADS))

        return DownloadManager(
            context,
            DefaultDownloadIndex(store.databaseProvider),
            downloaderFactory,
        ).apply {
            // One at a time. These are hundreds of megabytes over a connection the user is probably
            // also streaming on, and three concurrent downloads finish no sooner in total while
            // making each one take three times as long to become watchable.
            maxParallelDownloads = 1
        }
    }
}

// Segment fetches within a single HLS download; unrelated to how many downloads run at once.
private const val DOWNLOAD_THREADS = 4

private const val DOWNLOAD_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36"
