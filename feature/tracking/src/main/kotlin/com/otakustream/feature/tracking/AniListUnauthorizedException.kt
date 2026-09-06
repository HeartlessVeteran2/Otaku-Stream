package com.otakustream.feature.tracking

// AniList rejected the token, as opposed to failing the request.
//
// A distinct type because the two need opposite handling and used to get the same. Every AniList
// failure was caught, logged and dropped — reasonable for a timeout, wrong for a dead credential:
// nothing cleared the token, Settings went on saying "signed in", and every episode watched from
// then on silently failed to sync, on a phone with no logcat to explain it. The user's only clue
// was AniList quietly never updating again.
//
// Carries no server text on purpose. What the user needs to know is "your AniList sign-in expired",
// which is true of every instance of this; the underlying message ("Invalid token") explains
// nothing to them and is already in the log.
class AniListUnauthorizedException : RuntimeException("Your AniList sign-in has expired.")

// A dead token, told apart from every other kind of failure so callers can react to it instead
// of logging it forever.
//
// Narrow on purpose. AniList access tokens last a year, so a rejection is almost always a
// genuinely dead credential rather than a blip — but "almost always" is not good enough when
// the reaction is signing the user out, so this matches only the two shapes that actually mean
// "this token is no good": HTTP 401, and AniList's own habit of answering a bad token with a
// 400 whose GraphQL error reads "Invalid token". A 500 or a 503 during an outage matches
// neither and stays an ordinary, retryable failure.
//
// Gated on a token having been sent at all: the unauthenticated browse queries share this code
// path, and nothing they can return should be read as a credential problem.
internal fun isTokenRejection(token: String?, code: Int, message: String?): Boolean {
    if (token == null) return false
    if (code == 401) return true
    val text = message?.lowercase() ?: return false
    return "invalid token" in text || "unauthorized" in text || "unauthenticated" in text
}
