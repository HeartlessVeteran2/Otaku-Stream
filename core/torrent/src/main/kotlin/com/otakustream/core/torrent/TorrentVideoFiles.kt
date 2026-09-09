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
    // that this only decides between equals — which is why a pack also needs listPlayableFiles, so
    // the player can offer the rest.
    //
    // Returns null only when the torrent contains no video at all, which is a real answer: the
    // caller should say so rather than open index 0 and let the player fail with nothing to explain.
    fun selectPlayableFile(files: List<TorrentFileEntry>): Int? =
        candidates(files).maxWithOrNull(
            // Stable on ties: two runs over the same torrent must pick the same file, or a resume
            // position keyed on torrent://<hash>/<index> would point somewhere else on replay.
            compareBy<TorrentFileEntry> { it.sizeBytes }.thenByDescending { it.path.lowercase() },
        )?.index

    // Every file the picker may offer, in the order a person reads a season: natural, so episode 2
    // comes before episode 10.
    //
    // Same candidate set as selectPlayableFile, deliberately. If the two disagreed, a pack would
    // open on a file the picker does not list — and the row the user is looking at would be the one
    // they cannot see is playing.
    //
    // Ordered by path rather than by the torrent's own file order, which is not reliably episode
    // order, and certainly not by size, which is what selectPlayableFile uses and would interleave a
    // season at random.
    fun listPlayableFiles(files: List<TorrentFileEntry>): List<TorrentFileEntry> =
        candidates(files).sortedWith(NATURAL_ORDER)

    // Ties broken on index so the order is total: two files can share a path in a malformed torrent,
    // and a comparator that called them equal would let sortedWith return them in either order.
    private val NATURAL_ORDER: Comparator<TorrentFileEntry> =
        compareBy(NaturalPathOrder) { entry: TorrentFileEntry -> entry.path }
            .thenBy { entry -> entry.index }

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

// Digit runs compared as numbers, everything else case-insensitively, so "Episode 2" precedes
// "Episode 10". Plain lexicographic ordering puts 10 before 2, which for a season pack is wrong in
// exactly the case this picker exists for — and wrong quietly, since the list still looks sorted.
//
// ASCII digits only, on purpose. Char.isDigit() accepts any Unicode decimal digit, and a filename
// carrying Arabic-Indic numerals would be parsed as a number this comparator cannot then compare
// against an ASCII one. Treating those as ordinary characters is the honest answer.
private object NaturalPathOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isAsciiDigit() && b[j].isAsciiDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isAsciiDigit()) i++
                while (j < b.length && b[j].isAsciiDigit()) j++
                // Leading zeros dropped first, so "07" and "7" are the same episode rather than the
                // string comparison's two different ones. After that a longer run is the larger
                // number, and equal lengths compare as text.
                val numA = a.substring(startA, i).trimStart('0')
                val numB = b.substring(startB, j).trimStart('0')
                if (numA.length != numB.length) return numA.length - numB.length
                val digits = numA.compareTo(numB)
                if (digits != 0) return digits
            } else {
                val chars = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (chars != 0) return chars
                i++
                j++
            }
        }
        // Whatever is left over: the shorter string is the prefix, and prefixes sort first.
        val remainingA = a.length - i
        val remainingB = b.length - j
        return remainingA - remainingB
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}
