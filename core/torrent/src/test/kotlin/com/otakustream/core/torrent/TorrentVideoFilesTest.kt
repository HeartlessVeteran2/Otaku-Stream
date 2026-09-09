package com.otakustream.core.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MB = 1024L * 1024L

class TorrentVideoFilesTest {

    private fun file(index: Int, path: String, mb: Long) = TorrentFileEntry(index, path, mb * MB)

    // The bug this exists to fix: index 0 is an .nfo, so the old code opened a text file as video.
    @Test
    fun `skips non-video files that happen to come first`() {
        val files = listOf(
            file(0, "Show.S01E01/Show.S01E01.nfo", 1),
            file(1, "Show.S01E01/Show.S01E01.mkv", 1400),
        )
        assertEquals(1, TorrentVideoFiles.selectPlayableFile(files))
    }

    @Test
    fun `single file torrent still resolves to that file`() {
        val files = listOf(file(0, "Movie.2019.1080p.mkv", 2000))
        assertEquals(0, TorrentVideoFiles.selectPlayableFile(files))
    }

    // A sample is a real video in a real container. Only its name marks it, so only the name can
    // exclude it — and it must be excluded rather than ranked low, or it shows up in the picker
    // looking like another episode.
    @Test
    fun `sample files are excluded entirely`() {
        val files = listOf(
            file(0, "Show.S01E01/Sample/show-sample.mkv", 40),
            file(1, "Show.S01E01/Show.S01E01.mkv", 1400),
        )
        assertEquals(1, TorrentVideoFiles.selectPlayableFile(files))
    }

    // "Resample" contains "sample". A substring test would drop a legitimate file, and the viewer
    // would be told the torrent has no video at all.
    @Test
    fun `sample matching does not fire on substrings`() {
        val files = listOf(file(0, "Resample.Collection/Resample.mkv", 900))
        assertEquals(0, TorrentVideoFiles.selectPlayableFile(files))
    }

    // Refusing to play is worse than the bug being fixed, so an over-eager heuristic must yield.
    @Test
    fun `a torrent whose only video looks like a sample is still playable`() {
        val files = listOf(file(0, "preview/thing.mkv", 700))
        assertEquals(0, TorrentVideoFiles.selectPlayableFile(files))
    }

    @Test
    fun `a torrent with no video at all reports so rather than guessing`() {
        val files = listOf(
            file(0, "readme.txt", 1),
            file(1, "cover.jpg", 2),
            file(2, "subs/eng.srt", 1),
        )
        assertNull(TorrentVideoFiles.selectPlayableFile(files))
    }

    @Test
    fun `zero byte entries are not candidates`() {
        val files = listOf(
            TorrentFileEntry(0, "placeholder.mkv", 0),
            file(1, "real.mkv", 500),
        )
        assertEquals(1, TorrentVideoFiles.selectPlayableFile(files))
    }

    // The index ends up inside torrent://<hash>/<index>, which resume position and watch history key
    // on. An unstable choice would send the viewer to a different episode on replay.
    @Test
    fun `selection is stable when sizes tie`() {
        val files = listOf(
            file(0, "b.mkv", 350),
            file(1, "a.mkv", 350),
        )
        val first = TorrentVideoFiles.selectPlayableFile(files)
        assertEquals(first, TorrentVideoFiles.selectPlayableFile(files.reversed()))
    }

    @Test
    fun `largest file wins over extras in the same torrent`() {
        val files = listOf(
            file(0, "Extras/interview.mkv", 120),
            file(1, "Feature.mkv", 4000),
            file(2, "Extras/outtakes.mkv", 90),
        )
        assertEquals(1, TorrentVideoFiles.selectPlayableFile(files))
    }

    // A container missing from the list makes a torrent that does contain video report that it
    // contains none, which is indistinguishable from a genuinely videoless one.
    @Test
    fun `less common containers are still recognised as video`() {
        assertEquals(0, TorrentVideoFiles.selectPlayableFile(listOf(file(0, "clip.3gp", 300))))
        assertEquals(0, TorrentVideoFiles.selectPlayableFile(listOf(file(0, "clip.mts", 300))))
    }

    @Test
    fun `unknown containers are not offered to the player`() {
        val files = listOf(file(0, "thing.rar", 1400), file(1, "thing.mkv", 900))
        assertEquals(1, TorrentVideoFiles.selectPlayableFile(files))
    }

    // What the picker offers, and in what order.
    //
    // A season pack is the whole reason this list exists: selectPlayableFile picks the largest, and
    // across twelve episodes encoded the same way that is a coin toss. The user has to be able to
    // say which one.

    // Lexicographic order puts episode 10 before episode 2, and the list still looks sorted, so
    // nothing on screen says it is wrong — it just does not have the episode where it should be.
    @Test
    fun `episodes are listed in the order a person counts them`() {
        // Unpadded on purpose. A pack padded to two digits sorts correctly under plain string
        // comparison, so a fixture built that way would pass with no natural ordering at all — my
        // first version of this test did exactly that.
        val files = (1..13).map { n -> file(n - 1, "Show S01/Show - Episode $n.mkv", 1400) }

        val listed = TorrentVideoFiles.listPlayableFiles(files.shuffled()).map { it.path }

        assertEquals((1..13).map { n -> "Show S01/Show - Episode $n.mkv" }, listed)
    }

    // Packs are not consistent about zero-padding, and a release that mixes the two would otherwise
    // sort every unpadded episode away from its neighbours.
    @Test
    fun `padded and unpadded episode numbers interleave correctly`() {
        // "007" against "8" is the case that needs the leading zeros dropped: three digits against
        // one, so comparing run lengths first would put episode 7 after episode 8. Two-digit padding
        // alone never exercises that, which is why it is here.
        val files = listOf(
            file(0, "Show - 10.mkv", 1400),
            file(1, "Show - 2.mkv", 1400),
            file(2, "Show - 09.mkv", 1400),
            file(3, "Show - 1.mkv", 1400),
            file(4, "Show - 8.mkv", 1400),
            file(5, "Show - 007.mkv", 1400),
        )

        assertEquals(
            listOf(
                "Show - 1.mkv",
                "Show - 2.mkv",
                "Show - 007.mkv",
                "Show - 8.mkv",
                "Show - 09.mkv",
                "Show - 10.mkv",
            ),
            TorrentVideoFiles.listPlayableFiles(files).map { it.path },
        )
    }

    // The picker and the automatic choice have to agree about what is playable. If they did not, a
    // pack would open on a file the list does not contain, and the row the user is looking at would
    // be the one they cannot see is playing.
    @Test
    fun `the automatic choice is always one of the listed files`() {
        val files = listOf(
            file(0, "Pack/readme.nfo", 1),
            file(1, "Pack/Sample/teaser.mkv", 40),
            file(2, "Pack/Show - 01.mkv", 1400),
            file(3, "Pack/Show - 02.mkv", 1450),
            file(4, "Pack/cover.jpg", 1),
        )

        val listed = TorrentVideoFiles.listPlayableFiles(files).map { it.index }

        assertEquals(listOf(2, 3), listed)
        assertTrue(
            "the file that plays must be one the picker offers",
            TorrentVideoFiles.selectPlayableFile(files) in listed,
        )
    }

    // Same rule as the automatic choice: a torrent whose only video sits in a directory someone
    // named "preview" is still that torrent's video, and an empty picker over a playing file is
    // worse than listing it.
    @Test
    fun `a torrent whose only video looks like a sample still lists it`() {
        val files = listOf(file(0, "Show/preview/show.mkv", 900))

        assertEquals(listOf(0), TorrentVideoFiles.listPlayableFiles(files).map { it.index })
    }

    @Test
    fun `a torrent with no video lists nothing`() {
        val files = listOf(file(0, "Pack/readme.nfo", 1), file(1, "Pack/cover.jpg", 1))

        assertEquals(emptyList<TorrentFileEntry>(), TorrentVideoFiles.listPlayableFiles(files))
        assertNull(TorrentVideoFiles.selectPlayableFile(files))
    }

    // Two files can share a path in a malformed torrent. The order still has to be the same on every
    // run, because the picker's rows are how the user says "that one".
    @Test
    fun `duplicate paths keep a stable order`() {
        val files = listOf(file(3, "Show/01.mkv", 1400), file(1, "Show/01.mkv", 1400))

        assertEquals(listOf(1, 3), TorrentVideoFiles.listPlayableFiles(files).map { it.index })
        assertEquals(listOf(1, 3), TorrentVideoFiles.listPlayableFiles(files.reversed()).map { it.index })
    }
}
