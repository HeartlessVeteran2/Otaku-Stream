package com.otakustream.core.sources.scripting.di

import android.content.Context
import com.otakustream.core.sources.scripting.net.CloudflareInterceptor
import com.otakustream.core.sources.scripting.net.CloudflareSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Response
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ScriptingModule {

    // The app-wide shared OkHttp client (SingletonComponent) reused by every network client —
    // AniList, AniSkip, Stremio, scripted sources, source catalog. Timeouts bound any slow or
    // hung endpoint so it can't occupy a thread indefinitely. A default desktop User-Agent and a
    // cookie jar make it behave like a real browser: many anime hosts return 403/Cloudflare pages
    // to the stock OkHttp UA, and streams often gate on cookies set during the same session.
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

    // Adds a desktop-Chrome User-Agent only when the caller hasn't already set one, so a
    // scripted source / Stremio add-on that specifies its own UA still wins.
    private class UserAgentInterceptor(private val userAgent: String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (request.header("User-Agent") != null) return chain.proceed(request)
            return chain.proceed(request.newBuilder().header("User-Agent", userAgent).build())
        }
    }

    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
}
