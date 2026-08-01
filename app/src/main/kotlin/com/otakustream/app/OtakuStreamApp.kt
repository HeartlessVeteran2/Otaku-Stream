package com.otakustream.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.otakustream.app.crash.CrashReporter
import com.otakustream.core.network.di.ImageHttpClient
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

// Providing the app-wide Coil ImageLoader here means every AsyncImage/CoverImage shares one
// memory + disk cache and thread pool (rather than each call site building its own), with
// crossfade on by default.
@HiltAndroidApp
class OtakuStreamApp : Application(), ImageLoaderFactory {

    // Field injection rather than a constructor: an Application is built by the framework. Safe to
    // read from newImageLoader(), which Coil calls lazily on the first image request — long after
    // super.onCreate() has run Hilt's injection.
    @Inject
    @ImageHttpClient
    lateinit var imageHttpClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        // Surface otherwise-silent crashes on a copyable screen instead of the app just closing.
        CrashReporter.install(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(true)
        // Without this Coil builds its own client, so every cover was fetched with the stock OkHttp
        // User-Agent — the one anime hosts answer with a 403 or a challenge page. The desktop UA
        // interceptor exists for exactly that, and images were the traffic missing it.
        .okHttpClient { imageHttpClient }
        .build()
}
