package com.otakustream.core.player

import androidx.media3.common.C

// The two playback decisions that are pure arithmetic, kept out of PlayerController so they can be
// tested without a device.
//
// Both were wrong in ways no reviewer spotted and no test could have caught while they were inline
// in a class that needs an ExoPlayer, an Android Context and a Hilt graph to instantiate.

// Clamps a seek target to the media's bounds, tolerating a duration that isn't known yet.
//
// The bug this replaces: `position.coerceIn(0L, duration.coerceAtLeast(0L))`. Before the container
// header is parsed, ExoPlayer reports C.TIME_UNSET — a large *negative* — so coerceAtLeast(0) made
// the upper bound 0 and coerceIn(0, 0) clamped every seek to the very start. On a torrent that
// window is the thirty to sixty seconds spent finding peers and pulling the first pieces, which is
// exactly when someone double-taps to check whether anything is happening; the reward was being
// sent back to 0:00. Live and unbounded streams never leave that window at all.
//
// With no known duration there is no upper bound to apply, so only the floor is enforced.
internal fun clampSeekPosition(positionMs: Long, durationMs: Long): Long {
    val atLeastZero = positionMs.coerceAtLeast(0L)
    // `< 0`, not `<= 0`: C.TIME_UNSET and any other negative mean "not known yet", but a duration
    // of exactly zero is a real answer for a zero-length item and should still bound the seek to 0.
    return if (durationMs == C.TIME_UNSET || durationMs < 0L) {
        atLeastZero
    } else {
        atLeastZero.coerceAtMost(durationMs)
    }
}

// Why a torrent can't be played right now, or null when it can.
//
// TorrentEngine.isUsable already folded the three conditions into one boolean, and its own comment
// said it was meant to be applied at play time — but its only caller was the code deciding whether
// to *offer* torrent streams. So opening a torrent from Continue Watching, or tapping a magnet
// link, joined the swarm regardless of the switches: on mobile data with "unmetered only" set, and
// with torrents turned off entirely. Both upload, and both expose the user's IP to the swarm.
//
// Returning the reason rather than a boolean because the three need different things from the user:
// one is a device limitation they cannot fix, one is a setting to turn on, one is a network to
// leave. "Can't play this" would have told them none of that.
internal fun torrentRefusalMessage(
    isAvailable: Boolean,
    torrentsEnabled: Boolean,
    unmeteredOnly: Boolean,
    isOnUnmeteredNetwork: Boolean,
): String? = when {
    !isAvailable -> "Torrent playback isn't available on this device."
    !torrentsEnabled -> "Torrent playback is turned off. Turn it on in Settings to play this."
    unmeteredOnly && !isOnUnmeteredNetwork ->
        "Torrents are set to unmetered networks only, and this connection is metered."
    else -> null
}
