package com.otakustream.core.torrent

// Which file inside a torrent is the one to play.
//
// Magnet playback used to build `torrent://<hash>/0` unconditionally — index 0, whatever that
// happened to be. For a single-file torrent that is right, and single-file torrents are what most
// magnet links point at, so it looked correct. Anime is not distributed that way: a season pack's
// first entry is as likely to be an .nfo, a sample, a screenshots directory or episode 1 when the
// user wanted episode 12. The failure is silent — the player either shows the wrong episode or
// fails to open a file that was never video.
//
// Pure, so it can be tested against real-world pack layouts without joining a swarm — which matters
// because a swarm is exactly what is unavailable in CI.
object TorrentVideoFiles {

    // Containers Media3 has a chance with. Deliberately not "anything that isn't a subtitle": an
    // .nfo or a .txt scoring as a candidate is how index 0 became the bug in the first place.
    //
    // Erring wide within that: a container missing from this list makes a torrent that does contain
    // video report that it contains none, and that failure is indistinguishable from a genuinely
    // videoless torrent. The 3GP family and the MPEG-TS variants are here for that reason rather
    // than because anime ships in them.
    val EXTENSIONS = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "mts", "m2t",
        "mpg", "mpeg", "wmv", "flv", "ogv", "ogm", "3gp", "3g2",
    )

    // Release groups ship a short teaser next to the real file, conventionally with "sample" as a
    // field in the name or as its own directory. It is a real video in a real container, so nothing
    // but the name distinguishes it — and being small, it would never win on size anyway. It is
    // excluded rather than merely deprioritised so it does not appear in the picker as a decoy
    // episode.
    private val SAMPLE_MARKERS = setOf("sample", "samples", "trailer", "preview")

    // The file to play when nothing asks the user. Largest wins: within one torrent the feature is
    // reliably larger than extras, and across a season pack the episodes are close enough in size
    // that this only decides between equals — which is why a pack still needs the picker below.
    //
    // Returns null only when the torrent contains no video at all, which is a real answer: the
    // caller should say so rather than open index 0 and let the player fail with nothing to explain.
    fun selectPlayableFile(files: List<TorrentFileEntry>): Int? =
        candidates(files).maxWithOrNull(
            // Stable on ties: two runs over the same torrent must pick the same file, or a resume
            // position keyed on torrent://<hash>/<index> would point somewhere else on replay.
            compareBy<TorrentFileEntry> { it.sizeBytes }.thenByDescending { it.path.lowercase() },
        )?.index

    private fun candidates(files: List<TorrentFileEntry>): List<TorrentFileEntry> {
        val videos = files.filter { extensionOf(it.path) in EXTENSIONS && it.sizeBytes > 0 }
        val withoutSamples = videos.filterNot(::isSample)
        // Never let a heuristic leave the caller with nothing. A torrent whose only video sits in a
        // directory someone named "preview" is still that torrent's video, and refusing to play it
        // would be a worse failure than the one this function exists to fix.
        return withoutSamples.ifEmpty { videos }
    }

    // Matched on whole path fields rather than as a substring: "Resample.mkv" and a show legitimately
    // called "Sample" are not samples, and a substring test would silently drop them.
    private fun isSample(file: TorrentFileEntry): Boolean =
        file.path.split('/', '.', '_', '-', ' ', '[', ']', '(', ')')
            .any { it.lowercase() in SAMPLE_MARKERS }

    private fun extensionOf(path: String): String = path.substringAfterLast('.', "").lowercase()
}
