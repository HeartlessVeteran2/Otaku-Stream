package com.otakustream.core.torrent

// One file inside a torrent, as the subtitle picker needs to see it.
data class TorrentFileEntry(
    val index: Int,
    val path: String,
    val sizeBytes: Long,
)

// A subtitle file inside a torrent that is worth downloading alongside the video.
//
// `path` is torrent-relative on the way in and absolute once the file exists on disk — the reader
// resolves it against the save directory, because only it knows where that is.
data class TorrentSubtitleFile(
    val fileIndex: Int,
    val path: String,
    val label: String,
)

// How far along the torrent's subtitle files are.
//
// `total` lets a caller tell "this torrent has no subtitle files" from "they haven't downloaded yet",
// which is the difference between waiting pointlessly and waiting for something that is coming.
data class TorrentSubtitleProgress(
    val total: Int,
    val ready: List<TorrentSubtitleFile>,
) {
    val isComplete: Boolean get() = ready.size >= total
}

// Which files in a torrent are subtitles for the video being played.
//
// Release groups very often ship subtitles as separate files next to the video rather than muxed in,
// so without this a torrent that *does* have subtitles plays with none — and the user has no way to
// know they were there. Everything here is pure so it can be tested against real-world torrent
// layouts without a swarm.
object TorrentSubtitles {

    // Extensions Media3 can actually render, plus .sub. Nothing is gained by offering a format the
    // player will fail to parse — that turns a missing subtitle into a broken one.
    val EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub")

    // Subtitles are kilobytes. A multi-megabyte ".sub" is an IDX/SUB bitmap pair, which Media3 can't
    // render anyway, and downloading it would spend real bandwidth mid-playback.
    const val MAX_SIZE_BYTES = 2L * 1024 * 1024

    // Season packs can carry dozens. Beyond a handful the track picker stops being usable, and each
    // one is another file the swarm has to deliver before playback settles.
    const val MAX_FILES = 6

    // Ranked best-first: files whose name matches the video's, then files beside it, then the rest.
    //
    // The ordering matters more than it looks. In a season pack every episode's subtitles are in the
    // same directory, so without the name match the user would be offered episode 1's subtitles
    // while watching episode 5 — worse than no subtitles, because it looks like the feature works.
    fun pick(files: List<TorrentFileEntry>, videoIndex: Int): List<TorrentSubtitleFile> {
        val videoPath = files.firstOrNull { it.index == videoIndex }?.path
        val videoStem = videoPath?.let(::stemOf)
        val videoDir = videoPath?.let(::directoryOf)

        return files
            .asSequence()
            .filter { it.index != videoIndex }
            .filter { extensionOf(it.path) in EXTENSIONS }
            .filter { it.sizeBytes in 1..MAX_SIZE_BYTES }
            .sortedWith(
                compareBy(
                    { rankOf(it, videoStem, videoDir) },
                    // Stable within a rank: two runs over the same torrent must offer the same
                    // tracks in the same order, or the track list would reshuffle on replay.
                    { it.path.lowercase() },
                ),
            )
            .take(MAX_FILES)
            .map { TorrentSubtitleFile(it.index, it.path, labelFor(it.path, videoStem)) }
            .toList()
    }

    // 0 = same name as the video, 1 = same directory, 2 = anywhere else.
    private fun rankOf(file: TorrentFileEntry, videoStem: String?, videoDir: String?): Int = when {
        videoStem != null && stemOf(file.path).startsWith(videoStem, ignoreCase = true) -> 0
        videoDir != null && directoryOf(file.path).equals(videoDir, ignoreCase = true) -> 1
        else -> 2
    }

    // What to call the track. When the filename is the video's name plus a suffix — the usual
    // "Show.S01E01.en.srt" shape — the suffix is the only informative part, and showing the whole
    // filename would push it off the end of the row.
    internal fun labelFor(path: String, videoStem: String?): String {
        val stem = stemOf(path)
        val suffix = if (videoStem != null && stem.startsWith(videoStem, ignoreCase = true)) {
            stem.substring(videoStem.length).trim('.', '_', '-', ' ')
        } else {
            stem
        }
        val name = suffix.ifBlank { stem }.ifBlank { "Subtitles" }
        // Provenance, because add-on-provided subtitles are listed alongside these and an
        // unqualified "eng" in both lists would be two indistinguishable rows.
        return "$name — in torrent"
    }

    private fun extensionOf(path: String): String = path.substringAfterLast('.', "").lowercase()

    private fun stemOf(path: String): String =
        path.substringAfterLast('/').substringBeforeLast('.', path.substringAfterLast('/'))

    // "" for a file at the torrent's root, which is also what a root-level video reports — so the
    // two still compare equal and count as the same directory.
    private fun directoryOf(path: String): String =
        if ('/' in path) path.substringBeforeLast('/') else ""
}
