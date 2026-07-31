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

    // Who chose this URL.
    //
    // It decides which schemes the player will accept, and the two cases genuinely differ. A URL the
    // user picked — a file from the picker, an "Open with" from another app — is legitimately
    // file:// or content://, and refusing those would break on-device playback, which is a real
    // feature. A URL an installed source returned is a different thing wearing the same clothes:
    // nothing stops a source answering getVideoList with
    // file:///data/data/com.otakustream/databases/otaku_stream.db, and the player would dutifully
    // open it.
    //
    // Neither a blanket allow nor a blanket ban is right, which is why this exists rather than a
    // flat scheme list.
    enum class Provenance {
        // Chosen by the user, or by the app itself: the file picker, an ACTION_VIEW intent the user
        // acted on, a sidecar subtitle the app found next to a file already being played.
        USER,

        // Returned by an installed source, add-on or extension. Restricted to schemes that go over
        // the network, plus the app's own torrent:// identity.
        SOURCE,
    }

    // historyHandled tells the player whether the stasher already records watch history itself
    // (the catalog flow does); when false — or when nothing was stashed at all — the player
    // records the play as a direct play.
    // skipLookup, when present, resolves AniSkip intro/outro segments once the real duration is
    // known — a suspend closure so core:player stays ignorant of AniList/AniSkip.
    data class Stashed(
        val video: Video,
        val historyHandled: Boolean,
        val skipLookup: (suspend (durationMs: Long) -> List<SkipMark>)? = null,
        val provenance: Provenance = Provenance.SOURCE,
    )

    @Volatile
    private var pending: Stashed? = null

    // provenance defaults to SOURCE: almost every caller is a source, and a caller that forgets to
    // say gets the restricted treatment rather than the permissive one.
    fun stash(
        video: Video,
        historyHandled: Boolean = true,
        skipLookup: (suspend (durationMs: Long) -> List<SkipMark>)? = null,
        provenance: Provenance = Provenance.SOURCE,
    ) {
        pending = Stashed(video, historyHandled, skipLookup, provenance)
    }

    // Reads the pending video without clearing it, so a caller can decide whether to accept the
    // playback before committing to it. The player uses this to check provenance: consuming first
    // and validating afterwards would discard the stash on a rejection, and the retry — same URL,
    // now with no stash — would be judged by the permissive default and play after all.
    fun peek(url: String): Stashed? = pending?.takeIf { it.video.url == url }

    // Consumes and clears the pending video only if its url matches, so a mismatched or
    // already-consumed (e.g. after process death) lookup returns null rather than stale data.
    fun consume(url: String): Stashed? {
        val current = pending ?: return null
        if (current.video.url != url) return null
        pending = null
        return current
    }
}
