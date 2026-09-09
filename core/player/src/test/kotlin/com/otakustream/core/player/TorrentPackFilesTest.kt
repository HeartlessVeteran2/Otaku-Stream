package com.otakustream.core.player

import com.otakustream.core.torrent.TorrentFileEntry
import com.otakustream.core.torrent.TorrentRef
import com.otakustream.core.torrent.TorrentUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HASH = "0123456789abcdef0123456789abcdef01234567"
private const val MB = 1024L * 1024L

// What the in-player picker shows for a season pack.
//
// Both rules here fail quietly. A picker that marks the wrong row still renders — it just tells the
// viewer they are watching something they are not, and the row they would tap to get back to the
// episode they are on is the one that looks already selected. A picker offered over a single file
// is a control that cannot do anything.
class TorrentPackFilesTest {

    private fun file(index: Int, path: String, mb: Long) = TorrentFileEntry(index, path, mb * MB)

    private val pack = listOf(
        file(0, "Show S01 [1080p]/Show - 01.mkv", 1400),
        file(1, "Show S01 [1080p]/Show - 02.mkv", 1450),
        file(2, "Show S01 [1080p]/Show - 03.mkv", 1400),
    )

    @Test
    fun `a pack lists every episode, named by its file and not its folder`() {
        val files = TorrentPackFiles.forPlayback(TorrentRef(HASH, fileIdx = 0), pack)

        assertEquals(listOf("Show - 01.mkv", "Show - 02.mkv", "Show - 03.mkv"), files.map { it.label })
        assertEquals(listOf(0, 1, 2), files.map { it.fileIndex })
    }

    @Test
    fun `the url's own file index is the row marked as playing`() {
        val files = TorrentPackFiles.forPlayback(TorrentRef(HASH, fileIdx = 2), pack)

        assertEquals(listOf(false, false, true), files.map { it.isCurrent })
    }

    // The case the picker exists for. A magnet resolves to `auto`, which names no index at all, so
    // the current row has to be worked out the same way the reader worked it out — largest wins.
    // Episode 2 is the biggest file here, so it is what `auto` opened.
    @Test
    fun `an auto url marks the file the reader would have chosen`() {
        val files = TorrentPackFiles.forPlayback(TorrentRef(HASH, TorrentUri.AUTO_FILE_INDEX), pack)

        assertEquals(listOf(false, true, false), files.map { it.isCurrent })
    }

    // Not "mark nothing": a picker where no row is selected reads as though none of them is what is
    // playing, which for the most common way a magnet arrives would be every time.
    @Test
    fun `an auto url always marks exactly one row`() {
        val files = TorrentPackFiles.forPlayback(TorrentRef(HASH, TorrentUri.AUTO_FILE_INDEX), pack)

        assertEquals(1, files.count { it.isCurrent })
    }

    @Test
    fun `a single file torrent offers no picker`() {
        val single = listOf(file(0, "Movie.2019.1080p.mkv", 2000))

        assertTrue(TorrentPackFiles.forPlayback(TorrentRef(HASH, fileIdx = 0), single).isEmpty())
    }

    // Everything that is not a torrent: an http stream, a downloaded file, a video from the device.
    // There is no url to parse, and the sheet's row must not appear over any of them.
    @Test
    fun `playback that is not a torrent offers no picker`() {
        assertTrue(TorrentPackFiles.forPlayback(ref = null, listed = pack).isEmpty())
    }

    // Before the metadata arrives there is nothing to list, which is every torrent's first seconds.
    @Test
    fun `a torrent whose contents are not known yet offers no picker`() {
        assertTrue(TorrentPackFiles.forPlayback(TorrentRef(HASH, fileIdx = 0), emptyList()).isEmpty())
    }

    // A url naming a file that is not in the playable list — an index pointing at an .nfo, say, or
    // one an add-on made up. The list still has to render, with nothing marked, because the
    // alternative is showing the viewer no way out of a file that will not play.
    @Test
    fun `an index outside the playable files still lists the pack`() {
        val files = TorrentPackFiles.forPlayback(TorrentRef(HASH, fileIdx = 97), pack)

        assertEquals(3, files.size)
        assertEquals(0, files.count { it.isCurrent })
    }

    @Test
    fun `sizes are carried through so a row can say how big it is`() {
        val files = TorrentPackFiles.forPlayback(TorrentRef(HASH, fileIdx = 0), pack)

        assertEquals(listOf(1400 * MB, 1450 * MB, 1400 * MB), files.map { it.sizeBytes })
    }
}
