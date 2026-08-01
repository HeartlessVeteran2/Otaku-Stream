package com.otakustream.core.network.di

import android.content.Context
import com.otakustream.core.network.CloudflareInterceptor
import com.otakustream.core.network.CloudflareSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // The client for untrusted traffic: scripted sources, Mangayomi extensions, Stremio add-ons,
    // the source directory, AniSkip. Unqualified on purpose — see AccountHttpClient for why the
    // restricted client is the default one.
    //
    // Timeouts bound any slow or hung endpoint so it can't occupy a thread indefinitely. A default
    // desktop User-Agent and a cookie jar make it behave like a real browser: many anime hosts
    // return 403/Cloudflare pages to the stock OkHttp UA, and streams often gate on cookies set
    // during the same session.
    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        cloudflareSettings: CloudflareSettings,
    ): OkHttpClient {
        // Keep cookies across requests within the app session (Cloudflare clearance, PHPSESSID,
        // etc.). ACCEPT_ALL because these are third-party streaming hosts, not our own domain.
        val cookieManager = CookieManager().apply { setCookiePolicy(CookiePolicy.ACCEPT_ALL) }
        return OkHttpClient.Builder()
            // Disk cache so repeat GETs of slow-changing metadata (source directories, the
            // Mangayomi extension index, Stremio add-on manifests) are served locally instead of
            // refetched every time a screen opens. Honours the servers' own cache headers; AniList
            // is POST and therefore unaffected, and images have Coil's own cache.
            .cache(Cache(File(context.cacheDir, "http"), HTTP_CACHE_BYTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            // Longer overall budget than the WebView challenge timeout so a bypass has room to run.
            .callTimeout(60, TimeUnit.SECONDS)
            .cookieJar(JavaNetCookieJar(cookieManager))
            // Cloudflare interceptor is outermost so its retry re-runs the UA interceptor below it,
            // and the retried request lands in the same cookie jar the WebView solve populated.
            .addInterceptor(
                CloudflareInterceptor(context, cookieManager, DESKTOP_USER_AGENT, cloudflareSettings),
            )
            .addInterceptor(UserAgentInterceptor(DESKTOP_USER_AGENT))
            .build()
    }

    // The client for the user's own accounts, with its own cookie jar and its own cache.
    //
    // What it deliberately does not have is the Cloudflare interceptor. That interceptor answers a
    // challenge by loading the URL in a WebView and copying whatever cookies come back — a
    // reasonable trade for a streaming mirror, and the wrong shape entirely for a host the app
    // sends a bearer token to. AniList and Stremio serve their APIs directly; if one ever returned
    // a challenge, failing the call is the correct outcome.
    //
    // Derived with newBuilder() so the connection pool and dispatcher are shared — two independent
    // clients would mean two thread pools for no benefit — with the interceptor list cleared, since
    // newBuilder() copies it.
    @Provides
    @Singleton
    @AccountHttpClient
    fun provideAccountOkHttpClient(
        @ApplicationContext context: Context,
        sourceClient: OkHttpClient,
    ): OkHttpClient {
        val cookieManager = CookieManager().apply { setCookiePolicy(CookiePolicy.ACCEPT_ALL) }
        return sourceClient.newBuilder()
            .apply { interceptors().clear() }
            .cache(Cache(File(context.cacheDir, "http-account"), ACCOUNT_CACHE_BYTES))
            .cookieJar(JavaNetCookieJar(cookieManager))
            .addInterceptor(UserAgentInterceptor(DESKTOP_USER_AGENT))
            .build()
    }

    // The client Coil loads images with — see ImageHttpClient for why it is neither of the above.
    //
    // Same newBuilder() sharing as the account client, so this is one more interceptor list rather
    // than a third thread pool. No OkHttp response cache: Coil keeps its own disk cache for decoded
    // images, and a second copy of every cover on disk is exactly the kind of duplication a cache
    // is meant to avoid.
    @Provides
    @Singleton
    @ImageHttpClient
    fun provideImageOkHttpClient(sourceClient: OkHttpClient): OkHttpClient =
        sourceClient.newBuilder()
            .apply { interceptors().clear() }
            .cache(null)
            .addInterceptor(UserAgentInterceptor(DESKTOP_USER_AGENT))
            .build()

    // Adds a desktop-Chrome User-Agent only when the caller hasn't already set one, so a
    // scripted source / Stremio add-on that specifies its own UA still wins.
    private class UserAgentInterceptor(private val userAgent: String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (request.header("User-Agent") != null) return chain.proceed(request)
            return chain.proceed(request.newBuilder().header("User-Agent", userAgent).build())
        }
    }

    private const val HTTP_CACHE_BYTES = 20L * 1024 * 1024

    // Smaller than the source cache: this one only ever holds AniList and Stremio API responses,
    // and AniList is POST and therefore uncacheable anyway.
    private const val ACCOUNT_CACHE_BYTES = 4L * 1024 * 1024

    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
}
