package com.otakustream.core.sources.api

// Which URL schemes the player will open, given who supplied the URL.
//
// The app plays local files on purpose — the on-device library, the file picker, "Open with" — so
// file:// and content:// are legitimate. They are legitimate *when the user chose them*. An
// installed source returning file:///data/data/com.otakustream/databases/otaku_stream.db from
// getVideoList is the same scheme meaning something entirely different, and without this the player
// would open it and hand its contents to whatever renders next.
//
// So the question is not "is this scheme safe" but "is this scheme safe from this source".
object PlayableUrl {

    // Anything a source may point the player at: the two network schemes, plus the app's own
    // torrent:// identity, which TorrentDataSource resolves internally and never touches the
    // filesystem outside the torrent cache.
    private val SOURCE_SCHEMES = setOf("http", "https", "torrent")

    // Additionally allowed when the user picked the URL themselves. content:// is what the system
    // file picker returns; file:// is what a MediaStore scan and "Open with" produce.
    private val USER_SCHEMES = SOURCE_SCHEMES + setOf("file", "content", "asset", "rtsp", "rtmp")

    fun isAllowed(url: String, provenance: PendingPlayback.Provenance): Boolean {
        // Schemes are case-insensitive per RFC 3986 and Uri does not normalise them, so a source
        // writing FILE:// must not slip past a lowercase comparison.
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme.isEmpty()) return false
        return when (provenance) {
            PendingPlayback.Provenance.USER -> scheme in USER_SCHEMES
            PendingPlayback.Provenance.SOURCE -> scheme in SOURCE_SCHEMES
        }
    }

    fun rejectionMessage(): String =
        "This source returned a link the player won't open. Sources may only point at web or " +
            "torrent addresses, not at files on your device."
}
