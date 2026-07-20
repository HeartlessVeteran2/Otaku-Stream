package com.otakustream.feature.tracking

// In-app AniList sign-in uses OAuth's implicit grant: the authorize page redirects back to
// REDIRECT_URI with the access token in the URL fragment, which MainActivity captures.
//
// One-time developer setup (replaces every end user having to hand-build a token):
// register a client at https://anilist.co/settings/developer with the redirect URI below,
// then paste its numeric client id into CLIENT_ID. Until then the sign-in button explains
// that sign-in isn't configured in this build instead of failing.
// Full step-by-step guide: docs/anilist-setup.md.
object AniListAuth {
    const val CLIENT_ID = "YOUR_ANILIST_CLIENT_ID"
    const val REDIRECT_URI = "otakustream://anilist-auth"

    val isConfigured: Boolean
        get() = CLIENT_ID.isNotBlank() && CLIENT_ID != "YOUR_ANILIST_CLIENT_ID"

    fun authorizeUrl(): String =
        "https://anilist.co/api/v2/oauth/authorize?client_id=$CLIENT_ID&response_type=token"
}
