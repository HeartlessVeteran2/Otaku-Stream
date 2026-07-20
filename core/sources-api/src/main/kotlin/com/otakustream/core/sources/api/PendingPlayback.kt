package com.otakustream.core.sources.api

// A time range the player can offer to skip (intro/outro/recap). Kept dependency-free here so it
// can ride the PendingPlayback hand-off; the player maps it to its own typed model.
data class SkipMark(val startMs: Long, val endMs: Long, val type: String) {
    companion object {
        const val TYPE_INTRO = "intro"
        const val TYPE_OUTRO = "outro"
        const val TYPE_RECAP = "recap"
    }
}

// In-memory hand-off for the fully-resolved Video (headers, subtitle tracks, HLS flag) between
// MediaDetailsViewModel (feature:sources) and PlayerController (core:player) — avoids
// serializing headers/subtitles through Compose Navigation args.
object PendingPlayback {

    // historyHandled tells the player whether the stasher already records watch history itself
    // (the catalog flow does); when false — or when nothing was stashed at all — the player
    // records the play as a direct play.
    // skipLookup, when present, resolves AniSkip intro/outro segments once the real duration is
    // known — a suspend closure so core:player stays ignorant of AniList/AniSkip.
    data class Stashed(
        val video: Video,
        val historyHandled: Boolean,
        val skipLookup: (suspend (durationMs: Long) -> List<SkipMark>)? = null,
    )

    @Volatile
    private var pending: Stashed? = null

    fun stash(
        video: Video,
        historyHandled: Boolean = true,
        skipLookup: (suspend (durationMs: Long) -> List<SkipMark>)? = null,
    ) {
        pending = Stashed(video, historyHandled, skipLookup)
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
