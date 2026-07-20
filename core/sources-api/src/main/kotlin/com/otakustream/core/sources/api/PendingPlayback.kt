package com.otakustream.core.sources.api

// In-memory hand-off for the fully-resolved Video (headers, subtitle tracks, HLS flag) between
// MediaDetailsViewModel (feature:sources) and PlayerController (core:player) — avoids
// serializing headers/subtitles through Compose Navigation args.
object PendingPlayback {

    // historyHandled tells the player whether the stasher already records watch history itself
    // (the catalog flow does); when false — or when nothing was stashed at all — the player
    // records the play as a direct play.
    data class Stashed(val video: Video, val historyHandled: Boolean)

    @Volatile
    private var pending: Stashed? = null

    fun stash(video: Video, historyHandled: Boolean = true) {
        pending = Stashed(video, historyHandled)
    }

    // Consumes and clears the pending video only if its url matches, so a mismatched or
    // already-consumed (e.g. after process death) lookup returns null rather than stale data.
    fun consume(url: String): Stashed? {
        val current = pending ?: return null
        if (current.video.url != url) return null
        pending = null
        return current
    }
}
