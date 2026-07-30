package com.otakustream.core.torrent

// Canonical identity for "one file inside one torrent": torrent://<infoHash>/<fileIdx>
//
// Why a custom scheme instead of a local HTTP server, which is the usual way to bridge a torrent
// into a player: the whole playback stack keys off the media URL string. PlayerController looks up
// the resume position, the skip segments, the pending-video stash, and the AniList completion
// handler all by url. A localhost URL carries a port, and a port that differs between sessions (or
// after a collision retry) silently breaks every one of those — as bugs that look unrelated to
// torrents. This URL is derived purely from the torrent's own identity, so it is stable forever.
//
// It also means no listening socket, which would be reachable by any other app on the device.
object TorrentUri {
    const val SCHEME = "torrent"

    private const val PREFIX = "$SCHEME://"

    // BitTorrent v1 info-hash: SHA-1, 40 hex characters. v2 (SHA-256, 64 chars) is deliberately not
    // accepted — Stremio's stream protocol is v1, and silently half-supporting v2 would produce a
    // URL that parses but can never resolve.
    private const val INFO_HASH_LENGTH = 40

    fun isTorrentUrl(url: String): Boolean = url.startsWith(PREFIX, ignoreCase = true)

    // Returns null rather than throwing for anything malformed: these strings come from add-on
    // responses, so a bad one is expected input, not a programming error.
    fun build(infoHash: String, fileIdx: Int?): String? {
        val normalized = normalizeInfoHash(infoHash) ?: return null
        val index = fileIdx ?: 0
        if (index < 0) return null
        return "$PREFIX$normalized/$index"
    }

    fun parse(url: String): TorrentRef? {
        if (!isTorrentUrl(url)) return null
        val body = url.substring(PREFIX.length)
        // Exactly one separator: "<hash>/<idx>". Extra path segments mean this isn't ours.
        val parts = body.split('/')
        if (parts.size != 2) return null
        val infoHash = normalizeInfoHash(parts[0]) ?: return null
        val fileIdx = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return TorrentRef(infoHash = infoHash, fileIdx = fileIdx)
    }

    // Lowercased so the same torrent always produces the same URL. Add-ons return info-hashes in
    // whichever case they please, and without this "ABC…" and "abc…" would be two different history
    // keys for one file — two resume positions, two sets of skip markers.
    //
    // Internal rather than private so magnet parsing shares this exact rule. Two copies would be one
    // change away from disagreeing about what a valid hash is, and the failure mode is a url that
    // parses in one path and not the other.
    internal fun normalizeInfoHash(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.length != INFO_HASH_LENGTH) return null
        // Explicit ASCII ranges, not Char.isDigit(): that accepts any Unicode decimal digit, so an
        // Arabic-Indic numeral would pass as "hex", survive lowercase() unchanged, and produce a
        // torrent:// url that looks valid and can never resolve.
        if (!trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return trimmed.lowercase()
    }
}

// One playable file inside a torrent. fileIdx 0 is both the common case (single-file torrents) and
// the fallback Stremio uses when an add-on doesn't say which file it means.
data class TorrentRef(val infoHash: String, val fileIdx: Int)
