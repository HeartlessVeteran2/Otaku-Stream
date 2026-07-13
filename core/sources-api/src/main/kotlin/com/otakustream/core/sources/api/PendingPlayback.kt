package com.otakustream.core.sources.api

// In-memory hand-off for the fully-resolved Video (headers, subtitle tracks, HLS flag) between
// MediaDetailsViewModel (feature:sources) and PlayerController (core:player) — avoids
// serializing headers/subtitles through Compose Navigation args.
object PendingPlayback {
    @Volatile
    private var pending: Video? = null

    fun stash(video: Video) {
        pending = video
    }

    // Consumes and clears the pending video only if its url matches, so a mismatched or
    // already-consumed (e.g. after process death) lookup returns null rather than stale data.
    fun consume(url: String): Video? {
        val current = pending ?: return null
        if (current.url != url) return null
        pending = null
        return current
    }
}
