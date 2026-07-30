package com.otakustream.core.torrent

import java.io.File

// Keeps a torrent's own idea of where its files go inside the directory the app chose for it.
//
// Every path here comes out of torrent metadata, which is supplied by whoever made the torrent —
// the same category of input as a script's URL or an add-on's manifest. libtorrent sanitises paths
// before it writes anything, and in practice that is what stops a `../../databases/otaku_stream.db`
// entry from landing on the app's database. But the app has no check of its own, so it is trusting
// a property of a native dependency that nothing in this repo asserts, on a path where being wrong
// means writing to or reading from app-private storage.
//
// This is defence in depth, deliberately: it should never fire, and it costs two canonical-path
// resolutions per playback.
object TorrentPaths {

    // The file at `relativePath` under `saveDir`, or null when it would land outside it.
    //
    // Canonicalised rather than string-matched, because `..` segments and symlinks both resolve at
    // this step and neither is visible in the raw string. The separator is appended to the parent
    // before the prefix test so a sibling directory whose name merely starts with the same
    // characters — `/data/torrents-evil` against `/data/torrents` — is not mistaken for a child.
    fun containedFile(saveDir: File, relativePath: String): File? {
        val root = runCatching { saveDir.canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { File(saveDir, relativePath).canonicalFile }.getOrNull() ?: return null
        val rootPath = root.path.trimEnd(File.separatorChar) + File.separatorChar
        return candidate.takeIf { it.path.startsWith(rootPath) }
    }
}
