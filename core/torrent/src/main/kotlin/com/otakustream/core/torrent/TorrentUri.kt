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

    // "Whichever file in this torrent is the one worth playing" — resolved against the torrent's
    // metadata at open time, which is the first moment anything knows what the files are.
    //
    // A magnet carries no file list, so at the moment a url has to be built there is nothing to
    // choose from. The old answer was to write index 0 and hope; for a season pack that is an .nfo,
    // a sample, or episode 1 when the viewer wanted episode 12. `auto` says "not known yet" instead
    // of asserting a wrong answer, and resolution happens where the file list actually exists.
    //
    // It stays a stable identity despite being deferred: selection is deterministic and the
    // info-hash pins the file list forever, so the same url always resolves to the same file — which
    // is what resume position and watch history require of it.
    const val AUTO_FILE_INDEX = -1

    private const val AUTO_SEGMENT = "auto"

    fun isTorrentUrl(url: String): Boolean = url.startsWith(PREFIX, ignoreCase = true)

    // Returns null rather than throwing for anything malformed: these strings come from add-on
    // responses, so a bad one is expected input, not a programming error.
    // A null fileIdx means the caller genuinely does not know which file it wants — a magnet link,
    // or a Stremio add-on that returned an info-hash without one. That is now recorded as `auto`
    // rather than silently becoming index 0.
    //
    // A negative index is still rejected: AUTO_FILE_INDEX is how `auto` is represented after
    // parsing, not something a caller passes in. Accepting it here would give the same torrent two
    // spellings of the same url.
    fun build(infoHash: String, fileIdx: Int?): String? {
        val normalized = normalizeInfoHash(infoHash) ?: return null
        if (fileIdx == null) return "$PREFIX$normalized/$AUTO_SEGMENT"
        if (fileIdx < 0) return null
        return "$PREFIX$normalized/$fileIdx"
    }

    fun parse(url: String): TorrentRef? {
        if (!isTorrentUrl(url)) return null
        val body = url.substring(PREFIX.length)
        // Exactly one separator: "<hash>/<idx>". Extra path segments mean this isn't ours.
        val parts = body.split('/')
        if (parts.size != 2) return null
        val infoHash = normalizeInfoHash(parts[0]) ?: return null
        // Matched exactly, not case-insensitively. This url *is* the identity that resume position
        // and watch history key on, and they key on the raw string — so every spelling parse accepts
        // is another way for one file to become two entries. build() only ever writes "auto", and
        // nothing outside it constructs these (torrent:// is not among the schemes an external
        // intent can hand us), so accepting "AUTO" would buy no compatibility and cost that.
        val fileIdx = if (parts[1] == AUTO_SEGMENT) {
            AUTO_FILE_INDEX
        } else {
            parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        }
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

// One playable file inside a torrent.
//
// fileIdx is TorrentUri.AUTO_FILE_INDEX when the url did not name one, in which case the reader
// picks against the torrent's real file list. `isAuto` rather than comparing to -1 at each use, so
// the sentinel has exactly one spelling.
data class TorrentRef(val infoHash: String, val fileIdx: Int) {
    val isAuto: Boolean get() = fileIdx == TorrentUri.AUTO_FILE_INDEX
}
