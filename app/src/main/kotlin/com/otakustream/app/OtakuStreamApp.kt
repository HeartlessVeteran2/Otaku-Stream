package com.otakustream.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp

// Providing the app-wide Coil ImageLoader here means every AsyncImage/CoverImage shares one
// memory + disk cache and thread pool (rather than each call site building its own), with
// crossfade on by default.
@HiltAndroidApp
class OtakuStreamApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(true)
        .build()
}
