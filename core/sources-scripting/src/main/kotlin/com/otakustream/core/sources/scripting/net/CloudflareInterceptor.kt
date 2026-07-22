package com.otakustream.core.sources.scripting.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.net.CookieManager
import java.net.HttpCookie
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "CloudflareInterceptor"
private const val CHALLENGE_TIMEOUT_SECONDS = 15L
private const val CLEARANCE_COOKIE = "cf_clearance"

// Transparently clears Cloudflare's "Just a moment…" JS challenge for third-party stream hosts.
// A real request to a gated host comes back 403/503 with a challenge; a headless WebView loads the
// same URL, runs the challenge JS, and Cloudflare sets the cf_clearance cookie. We copy that cookie
// into OkHttp's cookie jar and retry, so the original caller (scripted source / Stremio / etc.)
// just sees the real page.
//
// Bounded and best-effort: one solve in flight per host (a mutex), a hard timeout, and any failure
// falls back to returning the original response — playback never hangs on this. It's a no-op unless
// a challenge is actually detected, so non-Cloudflare traffic is untouched.
class CloudflareInterceptor(
    private val context: Context,
    // OkHttp's cookie store (the one behind its JavaNetCookieJar) — where solved cookies must land.
    private val cookieManager: CookieManager,
    private val userAgent: String,
    private val settings: CloudflareSettings,
) : Interceptor {

    private val hostLocks = ConcurrentHashMap<String, Any>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!settings.isEnabled() || !isCloudflareChallenge(response)) return response

        val url = request.url
        Log.i(TAG, "Cloudflare challenge on ${url.host}; attempting WebView clearance")
        // Close the challenge response before retrying so its body/connection is released.
        response.close()

        val lock = hostLocks.getOrPut(url.host) { Any() }
        synchronized(lock) {
            // A concurrent request to the same host may have already solved it while we waited.
            if (!hasClearanceCookie(url)) {
                runCatching { solveChallenge(url.toString()) }
                    .onFailure { Log.w(TAG, "WebView clearance failed for ${url.host}", it) }
                copyWebViewCookies(url)
            }
        }
        return chain.proceed(request.newBuilder().build())
    }

    private fun isCloudflareChallenge(response: Response): Boolean {
        if (response.code != 403 && response.code != 503) return false
        val server = response.header("Server").orEmpty()
        if (!server.contains("cloudflare", ignoreCase = true)) return false
        // Modern managed challenges set cf-mitigated: challenge; the classic interstitial is a 503.
        return response.header("cf-mitigated") != null || response.code == 503
    }

    private fun hasClearanceCookie(url: HttpUrl): Boolean =
        runCatching { android.webkit.CookieManager.getInstance().getCookie(url.toString()) }
            .getOrNull()
            ?.contains(CLEARANCE_COOKIE) == true

    // Loads the URL in an off-screen WebView on the main thread and waits (bounded) for the
    // challenge to clear, signalled by the cf_clearance cookie appearing.
    private fun solveChallenge(url: String) {
        val latch = CountDownLatch(1)
        val mainHandler = Handler(Looper.getMainLooper())
        var webViewRef: WebView? = null

        mainHandler.post {
            runCatching {
                val webView = WebView(context)
                webViewRef = webView
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = userAgent
                val cookies = android.webkit.CookieManager.getInstance()
                cookies.setAcceptCookie(true)
                cookies.setAcceptThirdPartyCookies(webView, true)
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        // The interstitial reloads to the real page once solved; that reload's
                        // onPageFinished carries the cf_clearance cookie.
                        if (cookies.getCookie(url)?.contains(CLEARANCE_COOKIE) == true) {
                            latch.countDown()
                        }
                    }
                }
                webView.loadUrl(url)
            }.onFailure {
                Log.w(TAG, "Could not start WebView challenge solve", it)
                latch.countDown()
            }
        }

        val solved = latch.await(CHALLENGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!solved) Log.w(TAG, "Cloudflare challenge timed out for $url")
        // Always tear the WebView down on the main thread, whether or not we solved it.
        mainHandler.post {
            runCatching {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
            }
        }
    }

    // Bridges the WebView's cookie store (android.webkit.CookieManager) into OkHttp's java.net
    // cookie store, so the retried request carries cf_clearance and any session cookies.
    private fun copyWebViewCookies(url: HttpUrl) {
        val cookieString = runCatching {
            android.webkit.CookieManager.getInstance().getCookie(url.toString())
        }.getOrNull() ?: return
        val uri = URI(url.toString())
        cookieString.split(";").forEach { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) return@forEach
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            runCatching {
                val cookie = HttpCookie(name, value).apply {
                    domain = uri.host
                    path = "/"
                    version = 0
                }
                cookieManager.cookieStore.add(uri, cookie)
            }
        }
    }
}
