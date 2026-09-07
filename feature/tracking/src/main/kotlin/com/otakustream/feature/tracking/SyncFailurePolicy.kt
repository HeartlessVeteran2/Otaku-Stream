package com.otakustream.feature.tracking

import kotlinx.coroutines.CancellationException

// What a background AniList sync should do about a failure. Three outcomes, and getting any of them
// wrong is silent on a user's phone, which is why the decision lives here as a pure function rather
// than inline in a catch block.
internal enum class SyncFailureAction {
    // Cancellation is not a failure — it is the caller saying stop, and swallowing it leaves a
    // coroutine running inside a scope that has already been cancelled. This repo has hit that bug
    // often enough to be worth pinning with a test.
    Rethrow,

    // The token is dead. Retrying it forever in silence is the behaviour this replaced: nothing
    // cleared the credential, Settings kept saying "signed in", and every episode watched after the
    // token expired failed to sync with no way to find out.
    SignOut,

    // Everything else — a timeout, an outage, a 500. Genuinely transient, genuinely ignorable: the
    // next episode retries it, and playback never depends on tracking state.
    LogAndIgnore,
}

internal fun syncFailureAction(failure: Throwable): SyncFailureAction = when (failure) {
    is CancellationException -> SyncFailureAction.Rethrow
    is AniListUnauthorizedException -> SyncFailureAction.SignOut
    else -> SyncFailureAction.LogAndIgnore
}
