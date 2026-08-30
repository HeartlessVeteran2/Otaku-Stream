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
    val EXTENSIONS = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "mpg", "mpeg", "wmv", "flv", "ogv",
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

    // Every playable file, in the order a person would expect to choose from — episode order, not
    // size order. A season pack's picker is a list of episodes, and sorting it by size would
    // scramble them into an order with no meaning to the viewer.
    fun listPlayableFiles(files: List<TorrentFileEntry>): List<TorrentFileEntry> =
        candidates(files).sortedWith(compareBy(NATURAL) { it.path })

    // True when the choice is genuinely the user's to make. One candidate is not a choice, and
    // prompting for it would put a dialog in front of every ordinary single-file magnet.
    fun needsPicker(files: List<TorrentFileEntry>): Boolean = candidates(files).size > 1

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

    // Compares digit runs as numbers so "Episode 2" precedes "Episode 10". Lexicographic ordering
    // puts "10" before "2", which in a 12-episode pack is exactly the list the viewer has to scan
    // twice to find what they wanted.
    private val NATURAL = Comparator<String> { left, right ->
        var i = 0
        var j = 0
        while (i < left.length && j < right.length) {
            val a = left[i]
            val b = right[j]
            if (a.isDigit() && b.isDigit()) {
                var iEnd = i
                while (iEnd < left.length && left[iEnd].isDigit()) iEnd++
                var jEnd = j
                while (jEnd < right.length && right[jEnd].isDigit()) jEnd++
                // Compared as text after dropping leading zeros, so arbitrarily long runs can't
                // overflow a numeric parse — a torrent path is untrusted input like any other.
                val aDigits = left.substring(i, iEnd).trimStart('0').ifEmpty { "0" }
                val bDigits = right.substring(j, jEnd).trimStart('0').ifEmpty { "0" }
                if (aDigits.length != bDigits.length) return@Comparator aDigits.length - bDigits.length
                val cmp = aDigits.compareTo(bDigits)
                if (cmp != 0) return@Comparator cmp
                i = iEnd
                j = jEnd
            } else {
                val cmp = a.lowercaseChar().compareTo(b.lowercaseChar())
                if (cmp != 0) return@Comparator cmp
                i++
                j++
            }
        }
        (left.length - i) - (right.length - j)
    }
}
