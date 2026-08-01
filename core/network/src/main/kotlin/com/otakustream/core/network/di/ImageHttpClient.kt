package com.otakustream.core.network.di

import javax.inject.Qualifier

// The client Coil loads images with.
//
// Coil builds its own OkHttpClient when it isn't given one, and that client has none of this app's
// interceptors — so every cover image was requested with the stock OkHttp User-Agent. The desktop
// UA exists because anime hosts return 403 or a challenge page to that UA, which is exactly the
// traffic covers travel on, so images were failing for the one reason the interceptor was written
// to prevent.
//
// It is a separate qualifier rather than reusing the source client because the Cloudflare
// interceptor must not be in an image's path. Answering a challenge means opening a WebView, and a
// screen of covers is dozens of concurrent requests — the right outcome for an image that needs a
// challenge solved is a missing thumbnail, not a WebView.
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ImageHttpClient
