package com.otakustream.core.sources.scripting.net

// Pure challenge-detection + cookie-parsing logic, split out from CloudflareInterceptor so it can be
// unit-tested without a WebView or the network. Getting this wrong is costly both ways: a false
// positive fires a pointless headless-WebView solve (e.g. on a genuinely-down Cloudflare origin that
// returns a 503 error page), and a false negative lets a gated source keep 403ing.

// Body markers Cloudflare embeds in its interstitial / managed-challenge pages.
private val CHALLENGE_MARKERS = listOf(
    "challenge-platform",
    "cf-browser-verification",
    "cf_chl_",
    "Just a moment",
)

// True when [code]/headers/[bodySnippet] look like a Cloudflare challenge (not a normal page and not
// a genuine origin error served through Cloudflare).
//
// Rules: the response must be served by Cloudflare (Server: cloudflare) AND either carry a
// `cf-mitigated: challenge` header OR (on a 403/503) contain a challenge marker in the body. A bare
// 503 with no marker is treated as an origin error, NOT a challenge — that was the old false positive.
fun isCloudflareChallengeResponse(
    code: Int,
    serverHeader: String?,
    cfMitigated: String?,
    bodySnippet: String,
): Boolean {
    if (serverHeader?.contains("cloudflare", ignoreCase = true) != true) return false
    if (cfMitigated?.contains("challenge", ignoreCase = true) == true) return true
    if (code != 403 && code != 503) return false
    return CHALLENGE_MARKERS.any { bodySnippet.contains(it, ignoreCase = true) }
}

// Parses a WebView `CookieManager.getCookie()` string ("a=b; c=d") into name/value pairs, tolerating
// malformed entries, `=` inside values, and surrounding whitespace. (getCookie exposes no domain/path
// attributes, so those are decided by the caller.)
fun parseCookiePairs(cookieString: String): List<Pair<String, String>> =
    cookieString.split(";").mapNotNull { pair ->
        val eq = pair.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val name = pair.substring(0, eq).trim()
        val value = pair.substring(eq + 1).trim()
        if (name.isEmpty()) null else name to value
    }
