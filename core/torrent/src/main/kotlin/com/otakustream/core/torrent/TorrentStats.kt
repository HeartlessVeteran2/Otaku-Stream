package com.otakustream.core.torrent

// A snapshot of what the active torrent is doing, for the notification.
//
// Deliberately a plain data class with no libtorrent types in it: the notification is rendered
// outside this module's native boundary, and a snapshot is also safe to hold across a poll interval
// in a way a live TorrentHandle is not.
data class TorrentStats(
    val name: String,
    val downloadRateBytesPerSec: Int,
    val peers: Int,
    val seeds: Int,
    val progress: Float,
) {
    // Rendered small rather than exact: this is a glanceable notification line, not a diagnostic.
    fun formattedRate(): String = when {
        downloadRateBytesPerSec >= 1_000_000 -> "%.1f MB/s".format(downloadRateBytesPerSec / 1_000_000f)
        downloadRateBytesPerSec >= 1_000 -> "${downloadRateBytesPerSec / 1_000} kB/s"
        downloadRateBytesPerSec > 0 -> "$downloadRateBytesPerSec B/s"
        // Zero has a cause worth naming — with no peers you are waiting on discovery, not on
        // bandwidth, and those need different things from the user.
        peers == 0 -> "Looking for peers…"
        else -> "Stalled"
    }

    fun formattedPeers(): String = when {
        peers == 0 -> "no peers"
        seeds > 0 -> "${count(peers, "peer")} · ${count(seeds, "seed")}"
        else -> count(peers, "peer")
    }

    // "1 peer", not "1 peers". A single peer is a common state on a cold or unpopular torrent —
    // exactly when the user is reading this line to work out why nothing is happening.
    private fun count(value: Int, noun: String): String =
        if (value == 1) "$value $noun" else "$value ${noun}s"
}
