package com.otakustream.core.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The layouts here are the shapes real release groups actually ship, because the ordering rule is
// what decides whether a season pack offers the right episode's subtitles or a confidently wrong one.
class TorrentSubtitlesTest {

    private fun entry(index: Int, path: String, sizeBytes: Long = 40_000) =
        TorrentFileEntry(index, path, sizeBytes)

    @Test
    fun `finds a sidecar beside a single-file video`() {
        val files = listOf(
            entry(0, "Show.S01E01.1080p.mkv", 2_000_000_000),
            entry(1, "Show.S01E01.1080p.srt"),
        )
        val picked = TorrentSubtitles.pick(files, videoIndex = 0)
        assertEquals(listOf(1), picked.map { it.fileIndex })
    }

    @Test
    fun `prefers the playing episode's subtitles in a season pack`() {
        // The whole point of the ranking. Without it a viewer of episode 5 gets episode 1's
        // subtitles — worse than none, because it looks like the feature works.
        val files = listOf(
            entry(0, "Season 1/Show.S01E01.mkv", 2_000_000_000),
            entry(1, "Season 1/Show.S01E01.en.srt"),
            entry(2, "Season 1/Show.S01E05.mkv", 2_000_000_000),
            entry(3, "Season 1/Show.S01E05.en.srt"),
        )
        val picked = TorrentSubtitles.pick(files, videoIndex = 2)
        assertEquals(3, picked.first().fileIndex)
    }

    @Test
    fun `takes same-directory subtitles when nothing matches the name`() {
        val files = listOf(
            entry(0, "Movie/Subs/English.srt"),
            entry(1, "Movie/Movie.2160p.mkv", 8_000_000_000),
            entry(2, "Movie/Other.srt"),
        )
        val picked = TorrentSubtitles.pick(files, videoIndex = 1)
        // Neither shares the video's name, but index 2 is in the video's own directory and index 0 is
        // one level down, so the directory rank is what separates them.
        assertEquals(listOf(2, 0), picked.map { it.fileIndex })
    }

    @Test
    fun `falls back to alphabetical when neither name nor directory matches`() {
        // Everything here is rank 2, so only the tiebreak is under test. It exists so the offered
        // order is the same on every replay of one torrent.
        val files = listOf(
            entry(0, "Subs/zulu.srt"),
            entry(1, "Movie.2160p.mkv", 8_000_000_000),
            entry(2, "elsewhere/alpha.srt"),
        )
        assertEquals(listOf(2, 0), TorrentSubtitles.pick(files, videoIndex = 1).map { it.fileIndex })
    }

    @Test
    fun `does not treat a longer episode number as this episode`() {
        // Shows numbered without zero-padding put "S01E1" and "S01E10" in one pack. A bare
        // startsWith claims episode 10's subtitles while episode 1 is playing — worse than offering
        // none, because it looks like the feature worked.
        val files = listOf(
            entry(0, "Show.S01E1.mkv", 2_000_000_000),
            entry(1, "Show.S01E10.en.srt"),
            entry(2, "Show.S01E1.en.srt"),
        )
        val picked = TorrentSubtitles.pick(files, videoIndex = 0)
        assertEquals(2, picked.first().fileIndex)
    }

    @Test
    fun `matches a subtitle named exactly like the video`() {
        val files = listOf(
            entry(0, "Show.S01E01.mkv", 2_000_000_000),
            entry(1, "Show.S01E01.srt"),
        )
        assertEquals(listOf(1), TorrentSubtitles.pick(files, videoIndex = 0).map { it.fileIndex })
    }

    @Test
    fun `ignores non-subtitle files`() {
        val files = listOf(
            entry(0, "Show.mkv", 2_000_000_000),
            entry(1, "Show.nfo"),
            entry(2, "poster.jpg"),
            entry(3, "RARBG.txt"),
            entry(4, "Show.srt"),
        )
        assertEquals(listOf(4), TorrentSubtitles.pick(files, videoIndex = 0).map { it.fileIndex })
    }

    @Test
    fun `ignores subtitle files too large to be subtitles`() {
        // A multi-megabyte ".sub" is an IDX/SUB bitmap pair. The extension is one we accept, so size is
        // the only thing rejecting it — kept as its own test so that stays true if the filters change.
        val files = listOf(
            entry(0, "Show.mkv", 2_000_000_000),
            entry(1, "Show.sub", 30_000_000),
            entry(2, "Show.fr.srt", 30_000),
        )
        assertEquals(listOf(2), TorrentSubtitles.pick(files, videoIndex = 0).map { it.fileIndex })
    }

    @Test
    fun `ignores empty subtitle files`() {
        // A zero-byte placeholder renders as no subtitles at all, so offering it is worse than nothing.
        val files = listOf(
            entry(0, "Show.mkv", 2_000_000_000),
            entry(1, "Show.en.srt", 0),
            entry(2, "Show.fr.srt", 30_000),
        )
        assertEquals(listOf(2), TorrentSubtitles.pick(files, videoIndex = 0).map { it.fileIndex })
    }

    @Test
    fun `never offers the video itself`() {
        // A video whose own extension somehow matched would otherwise be handed to the player as a
        // subtitle track.
        val files = listOf(entry(0, "Weird.srt", 1_000))
        assertTrue(TorrentSubtitles.pick(files, videoIndex = 0).isEmpty())
    }

    @Test
    fun `caps how many are offered`() {
        val files = listOf(entry(0, "Show.mkv", 2_000_000_000)) +
            (1..20).map { entry(it, "Subs/lang$it.srt") }
        assertEquals(TorrentSubtitles.MAX_FILES, TorrentSubtitles.pick(files, videoIndex = 0).size)
    }

    @Test
    fun `orders stably within a rank`() {
        // Two runs over one torrent must offer the same tracks in the same order, or the track list
        // reshuffles between replays of the same episode.
        val files = listOf(entry(0, "Show.mkv", 2_000_000_000)) +
            listOf(entry(1, "Subs/fr.srt"), entry(2, "Subs/en.srt"), entry(3, "Subs/de.srt"))
        val first = TorrentSubtitles.pick(files, videoIndex = 0).map { it.fileIndex }
        val second = TorrentSubtitles.pick(files.shuffled(), videoIndex = 0).map { it.fileIndex }
        assertEquals(first, second)
    }

    @Test
    fun `labels a matching sidecar by its language suffix`() {
        // "Show.S01E01.eng.srt" beside "Show.S01E01.mkv" is informative only in its suffix; showing
        // the whole filename would push that off the end of the row.
        assertEquals("eng — in torrent", TorrentSubtitles.labelFor("Show.S01E01.eng.srt", "Show.S01E01"))
    }

    @Test
    fun `labels an unrelated subtitle by its filename`() {
        assertEquals("English — in torrent", TorrentSubtitles.labelFor("Subs/English.srt", "Movie.2160p"))
    }

    @Test
    fun `falls back to a readable label when the suffix is empty`() {
        // Exactly the video's name plus the extension leaves no suffix to show.
        assertEquals("Show.S01E01 — in torrent", TorrentSubtitles.labelFor("Show.S01E01.srt", "Show.S01E01"))
    }

    @Test
    fun `reports completion against the candidate count`() {
        val one = TorrentSubtitleFile(fileIndex = 1, path = "a.srt", label = "a")
        assertTrue(TorrentSubtitleProgress(total = 1, ready = listOf(one)).isComplete)
        assertTrue(!TorrentSubtitleProgress(total = 2, ready = listOf(one)).isComplete)
        // No candidates is trivially complete: there is nothing left to wait for.
        assertTrue(TorrentSubtitleProgress(total = 0, ready = emptyList()).isComplete)
    }
}
