package com.otakustream.feature.tracking

import java.net.URLEncoder

// In-app AniList sign-in uses OAuth's implicit grant: the authorize page redirects back to
// REDIRECT_URI with the access token in the URL fragment, which MainActivity captures.
//
// The client id comes from the build (BuildConfig.ANILIST_CLIENT_ID), supplied via
// local.properties / -PanilistClientId / the ANILIST_CLIENT_ID env var — see docs/anilist-setup.md
// for the one-time registration. It's a public OAuth client id rather than a secret, but keeping it
// out of git means a fork never ships someone else's client. When it's absent the app still builds
// and Settings explains that sign-in isn't configured in this build.
object AniListAuth {
    val CLIENT_ID: String = BuildConfig.ANILIST_CLIENT_ID

    const val REDIRECT_URI = "otakustream://anilist-auth"

    val isConfigured: Boolean
        get() = CLIENT_ID.isNotBlank()

    // Send redirect_uri explicitly: AniList only defaults it when exactly one URI is registered on
    // the client, so an app registered with more than one would otherwise fail the round-trip.
    //
    // `state` is echoed back on the redirect and checked there (see AniListAuthState). AniList
    // returns it in the fragment alongside the token, which is what lets the app tell a redirect it
    // asked for from one any app or web page could have fired at it.
    fun authorizeUrl(state: String): String {
        val redirect = URLEncoder.encode(REDIRECT_URI, "UTF-8")
        return "https://anilist.co/api/v2/oauth/authorize" +
            "?client_id=$CLIENT_ID&response_type=token&redirect_uri=$redirect" +
            "&state=${URLEncoder.encode(state, "UTF-8")}"
    }
}
