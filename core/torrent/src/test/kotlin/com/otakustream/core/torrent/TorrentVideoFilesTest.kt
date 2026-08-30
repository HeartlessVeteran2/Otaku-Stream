package com.otakustream.core.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
