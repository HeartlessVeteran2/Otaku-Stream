package com.otakustream.feature.sources

import com.otakustream.core.sources.api.SourceHttpException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

// Why one source failed, and which one.
//
// The catalog fan-out used to keep a single `failedSourceCount: Int`. Every reason a source can
// fail collapsed into that number, so the user was told "2 sources couldn't load" and given no way
// to tell a dead add-on from a dropped connection — and no way to act, since the two want opposite
// responses (uninstall it vs. try again). The information was never missing; it was caught and
// discarded at the fan-out's catch sites.
data class SourceFailure(
    val sourceId: Long,
    val sourceName: String,
    val reason: FailureReason,
)

sealed interface FailureReason {
    // The host answered and refused. Kept as the status code rather than a message so the UI can
    // say something different about "it is blocking us" and "it is broken".
    data class Http(val code: Int) : FailureReason

    // Exceeded the fan-out's per-source budget. Distinct from Offline: the host may be reachable
    // and simply slow, which is worth retrying where a missing network is not.
    data class Timeout(val afterMs: Long) : FailureReason

    // Never reached the host — no DNS, no route, no network.
    data object Offline : FailureReason

    // A TLS failure, which on these hosts usually means an interception proxy or a expired
    // certificate rather than anything the user can retry away.
    data object Tls : FailureReason

    // The source answered fine and simply does not list this episode.
    //
    // Not a failure in any technical sense, and carried here anyway, because the question the user
    // is asking of this list is "why did nothing come from X" — and a source that is installed,
    // linked, and silently contributed nothing is indistinguishable from one that was never asked.
    // Only reachable through pooled stream resolution, where several sources are queried for one
    // episode and their numbering does not always agree.
    data class NoSuchEpisode(val episodeNumber: Float) : FailureReason

    // Anything else: a parse failure, a JS extension throwing, a malformed response. The message
    // is the only thing that distinguishes these, so it is carried verbatim.
    data class Unknown(val message: String?) : FailureReason
}

// Maps a caught throwable onto a reason.
//
// Deliberately not handling CancellationException: cancellation is not a failure, it is a newer
// search superseding this one, and the fan-out rethrows it before ever reaching here. Classifying
// it would turn every keystroke into a reported "source error".
fun Throwable.toFailureReason(): FailureReason = when (this) {
    is SourceHttpException -> FailureReason.Http(code)
    is UnknownHostException -> FailureReason.Offline
    is SSLException -> FailureReason.Tls
    // SocketTimeoutException is an InterruptedIOException, so it must be matched first.
    is SocketTimeoutException -> FailureReason.Timeout(afterMs = 0)
    is InterruptedIOException -> FailureReason.Timeout(afterMs = 0)
    else -> FailureReason.Unknown(message?.takeIf { it.isNotBlank() })
}

// One short clause naming what went wrong, for display after the source's name:
// "AnimeKai — blocked this request (HTTP 403)".
//
// Written to be read by someone deciding what to do, not by someone debugging: a 403 and a 503 are
// both "the server said no" to a stack trace, but one means try a different source and the other
// means try again later.
fun FailureReason.describe(): String = when (this) {
    is FailureReason.Http -> when (code) {
        401, 403 -> "blocked this request (HTTP $code)"
        404 -> "endpoint not found (HTTP 404)"
        429 -> "rate-limited us (HTTP 429)"
        in 500..599 -> "server error (HTTP $code)"
        else -> "unexpected response (HTTP $code)"
    }
    is FailureReason.Timeout ->
        if (afterMs > 0) "timed out after ${afterMs / 1000}s" else "timed out"
    FailureReason.Offline -> "could not be reached — check your connection"
    FailureReason.Tls -> "failed a secure connection check"
    is FailureReason.NoSuchEpisode -> "has no episode ${formatEpisodeNumber(episodeNumber)}"
    is FailureReason.Unknown -> message ?: "failed for an unknown reason"
}

// Episode numbers are Float so that specials can be .5, but almost all of them are whole — and
// "has no episode 12.0" reads like a bug report rather than a sentence.
private fun formatEpisodeNumber(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()

// "AnimeKai — timed out after 15s"
fun SourceFailure.describe(): String = "$sourceName — ${reason.describe()}"

// The banner headline. Named rather than counted when there is only one, because "1 source couldn't
// load" tells the user strictly less than the name they already recognise.
fun List<SourceFailure>.headline(): String = when (size) {
    0 -> ""
    1 -> "${first().sourceName} couldn't load"
    else -> "$size sources couldn't load"
}

// The named detail under a headline: up to `limit` sources with their reasons, then a count.
//
// Capped because the list is read at a glance and an eight-source pool can have eight things to say
// about one episode. The first few are the ones the user recognises; past that, the count is the
// only part still carrying information.
fun List<SourceFailure>.detail(limit: Int = 3): String {
    if (isEmpty()) return ""
    val named = take(limit).joinToString("; ") { it.describe() }
    val remaining = size - minOf(size, limit)
    return if (remaining == 0) named else "$named; and $remaining more"
}

// True when every failure shares a cause that is about the device rather than the sources — worth
// saying once at the top instead of repeating per source.
fun List<SourceFailure>.allOffline(): Boolean =
    isNotEmpty() && all { it.reason == FailureReason.Offline }
