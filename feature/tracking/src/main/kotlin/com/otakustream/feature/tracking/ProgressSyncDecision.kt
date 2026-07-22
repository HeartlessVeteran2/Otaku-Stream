package com.otakustream.feature.tracking

// Pure decision for the two-way AniList progress sync. Given the viewer's current list entry
// (or nulls when the anime isn't on any of their lists yet) and the episode that just finished,
// it returns what to write to AniList — or null for "leave AniList untouched".
//
// Forward-only by construction, which is the whole point: progress must never move backward and a
// COMPLETED/REPEATING entry must never be downgraded to CURRENT. Rewatching episode 1 of a show
// you finished must not wipe your completion (the pre-fix behaviour). Extracted from
// TrackingManager so it's unit-testable without any network.

data class ProgressUpdate(val status: String, val progress: Int)

// AniList statuses that mean "already finished / rewatching" — keep them when nudging progress.
private val TERMINAL_STATUSES = setOf("COMPLETED", "REPEATING")

fun decideProgressUpdate(
    currentStatus: String?,
    currentProgress: Int,
    finishedEpisode: Int,
): ProgressUpdate? {
    // Only whole, positive episode numbers are real AniList progress; the caller filters specials
    // and episode 0, but guard here too so the function is safe on its own.
    if (finishedEpisode < 1) return null
    // Never move backward: rewatching, or jumping to an earlier episode, must not lower progress.
    if (finishedEpisode <= currentProgress) return null
    // Advance progress, but preserve a finished/rewatching status instead of flipping it to CURRENT.
    val status = if (currentStatus in TERMINAL_STATUSES) currentStatus!! else STATUS_CURRENT
    return ProgressUpdate(status = status, progress = finishedEpisode)
}

const val STATUS_CURRENT = "CURRENT"

// A Float episode number is real AniList progress only when it's a whole number ≥ 1. Specials and
// fractional episodes (6.5) and episode 0 return null rather than being truncated into a real
// episode (6.5 → 6 would silently mis-sync and mis-query AniSkip).
fun Float.toWholeEpisodeOrNull(): Int? {
    val whole = toInt()
    return if (whole >= 1 && this == whole.toFloat()) whole else null
}
