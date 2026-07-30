package com.otakustream.core.network

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "CloudflareInterceptor"
private const val CHALLENGE_TIMEOUT_SECONDS = 15L
private const val CLEARANCE_COOKIE = "cf_clearance"
// Cap the body peek used for challenge detection so a huge page can't be fully buffered.
private const val BODY_PEEK_BYTES = 64L * 1024L
// Upper bound on distinct hosts holding a challenge lock. See hostLocks.
private const val MAX_TRACKED_HOSTS = 64

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

    // One solve in flight per host. Bounded, because the key is a host string taken from URLs that
    // third-party sources supply: an extension that walks a wildcard domain would otherwise add an
    // entry per hostname and never remove one, growing for the life of the process. The cap is far
    // above any real number of gated hosts in a session, so eviction is a backstop, not a mechanism
    // — and evicting a lock is harmless anyway. The worst case is two concurrent solves for the
    // same host, which is what the code did before the lock existed.
    private val hostLocks = object : LinkedHashMap<String, Any>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?): Boolean =
            size > MAX_TRACKED_HOSTS
    }

    // Guards hostLocks itself. A plain LinkedHashMap in access order is not thread-safe, and
    // intercept() runs on every OkHttp dispatcher thread at once. Held only for the map lookup —
    // never across the solve, which would serialise every host behind one challenge.
    private val hostLocksGuard = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!settings.isEnabled() || !isCloudflareChallenge(response)) return response

        val url = request.url
        Log.i(TAG, "Cloudflare challenge on ${url.host}; attempting WebView clearance")
        // Close the challenge response before retrying so its body/connection is released.
        response.close()

        val lock = synchronized(hostLocksGuard) { hostLocks.getOrPut(url.host) { Any() } }
        synchronized(lock) {
            // A concurrent request to the same host may have already solved it while we waited.
            if (!hasClearanceCookie(url)) {
                runCatching { solveChallenge(url.toString()) { chain.call().isCanceled() } }
                    .onFailure { Log.w(TAG, "WebView clearance failed for ${url.host}", it) }
                copyWebViewCookies(url)
            }
        }
        return chain.proceed(request.newBuilder().build())
    }

    private fun isCloudflareChallenge(response: Response): Boolean {
        val server = response.header("Server")
        val cfMitigated = response.header("cf-mitigated")
        // Only a 403/503 warrants a body peek; everything else is decided on headers alone, so a
        // normal 200 from a Cloudflare-fronted site is never buffered.
        val bodySnippet = if (response.code == 403 || response.code == 503) {
            runCatching { response.peekBody(BODY_PEEK_BYTES).string() }.getOrNull().orEmpty()
        } else {
            ""
        }
        return isCloudflareChallengeResponse(response.code, server, cfMitigated, bodySnippet)
    }

    private fun hasClearanceCookie(url: HttpUrl): Boolean =
        runCatching { android.webkit.CookieManager.getInstance().getCookie(url.toString()) }
            .getOrNull()
            ?.contains(CLEARANCE_COOKIE) == true

    // Loads the URL in an off-screen WebView on the main thread and waits (bounded) for the
    // challenge to clear, signalled by the cf_clearance cookie appearing. [isCanceled] lets the wait
    // bail out promptly if OkHttp cancels the call rather than blocking the whole timeout.
    private fun solveChallenge(url: String, isCanceled: () -> Boolean) {
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
                // Everything below is off by default on a modern WebView — but "off by default"
                // has changed with API level before, and this WebView exists to run JavaScript
                // written by a hostile third party (that is the entire point: it is solving their
                // challenge). Stating them makes the boundary independent of what the platform
                // happens to default to on the device the app is installed on.
                //
                // The file ones matter most: with them on, the challenge page could read
                // app-private files off disk and post them anywhere — the app's databases and the
                // encrypted-prefs file included.
                webView.settings.allowFileAccess = false
                webView.settings.allowContentAccess = false
                @Suppress("DEPRECATION")
                webView.settings.allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                webView.settings.allowUniversalAccessFromFileURLs = false
                // The challenge is always fetched over the scheme the request used; a page that
                // pulls http subresources into an https challenge is not something to help along.
                webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                // No reason for a challenge page to ask for a location fix.
                webView.settings.setGeolocationEnabled(false)
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

        // Poll in short slices so a cancelled call doesn't block a dispatcher thread for the full
        // timeout, while still capping the total wait at CHALLENGE_TIMEOUT_SECONDS.
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(CHALLENGE_TIMEOUT_SECONDS)
        var solved = false
        while (System.nanoTime() < deadlineNanos) {
            if (isCanceled()) break
            if (latch.await(250, TimeUnit.MILLISECONDS)) { solved = true; break }
        }
        if (!solved) Log.w(TAG, "Cloudflare challenge unsolved (timeout/cancel) for $url")
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
        // getCookie() exposes no domain/path attributes, so every cookie is registered host-only —
        // the narrowest scope that still satisfies the immediate retry to this exact host.
        //
        // Exactly one cookie is also registered domain-wide, and only because it has to be:
        // cf_clearance is issued for the registrable domain and later requests to sibling
        // subdomains need it, which is the whole reason for solving the challenge once.
        //
        // Every cookie used to get that treatment. That silently widened the scope of cookies the
        // origin had deliberately kept host-only — a session cookie set for `www.example.com` was
        // re-registered against `.example.com` and then sent to every other subdomain, including
        // whatever `user-content.example.com` happens to host. Promoting a session cookie to a
        // wildcard is the app handing a credential to hosts the origin never meant to see it.
        val registrableDomain = ".${url.topPrivateDomain() ?: uri.host}"
        parseCookiePairs(cookieString).forEach { (name, value) ->
            runCatching {
                cookieManager.cookieStore.add(uri, HttpCookie(name, value).apply { domain = uri.host; path = "/"; version = 0 })
                if (name == CLEARANCE_COOKIE) {
                    cookieManager.cookieStore.add(
                        uri,
                        HttpCookie(name, value).apply { domain = registrableDomain; path = "/"; version = 0 },
                    )
                }
            }
        }
    }
}
