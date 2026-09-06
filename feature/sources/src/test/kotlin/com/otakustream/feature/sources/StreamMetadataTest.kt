package com.otakustream.feature.sources

import com.otakustream.core.sources.api.Episode
import com.otakustream.core.sources.api.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamMetadataTest {

    // The shape Torrentio actually returns: the add-on name and resolution in `name`, and the
    // release filename plus seeders, size and indexer in `title`/`description`, emoji-separated.
    @Test
    fun `parses a Torrentio stream`() {
        val meta = parseStreamMetadata(
            quality = "Torrentio\n1080p",
            description = "[SubsPlease] Frieren - 12 (1080p) [A1B2C3D4].mkv\n👤 243 💾 1.4 GB ⚙️ Nyaa",
        )
        assertEquals(1080, meta.resolution)
        assertEquals(243, meta.seeders)
        assertEquals("Nyaa", meta.releaseGroup)
        // 1.4 GiB, to the byte the parser computes rather than a round number, so a change in the
        // unit base fails here rather than silently mislabelling every size in the picker.
        assertEquals(1503238553L, meta.sizeBytes)
    }

    @Test
    fun `parses a bare quality string with nothing else`() {
        val meta = parseStreamMetadata(quality = "720p", description = null)
        assertEquals(720, meta.resolution)
        assertNull(meta.seeders)
        assertNull(meta.sizeBytes)
        assertNull(meta.releaseGroup)
    }

    @Test
    fun `reads nothing out of nothing`() {
        assertEquals(StreamMetadata(), parseStreamMetadata(quality = "", description = null))
        assertEquals(StreamMetadata(), parseStreamMetadata(quality = null, description = "   "))
    }

    // "4K" and "2160p" are the same file described two ways, and an add-on that says only "4K"
    // would otherwise sort below a 1080p entry that spelled its resolution out.
    @Test
    fun `treats 4K and UHD as 2160`() {
        assertEquals(2160, parseStreamMetadata("4K HDR", null).resolution)
        assertEquals(2160, parseStreamMetadata("UHD BluRay", null).resolution)
        assertEquals(2160, parseStreamMetadata("2160p", null).resolution)
    }

    // A release advertised as 4K that is actually a 1080p downscale is a 1080p file. The explicit
    // "1080p" is a statement about the video; "4K" in that title is marketing.
    @Test
    fun `an explicit resolution beats a 4K alias`() {
        assertEquals(1080, parseStreamMetadata("4K.Remux.1080p.Downscale", null).resolution)
    }

    // 1998 is a year and 24 is a frame rate; neither is a resolution, and both sit next to real
    // ones in release names.
    @Test
    fun `ignores numbers that are not resolutions`() {
        assertNull(parseStreamMetadata("Cowboy.Bebop.1998.Complete", null).resolution)
        assertEquals(1080, parseStreamMetadata("Show.1998.1080p.24fps", null).resolution)
    }

    @Test
    fun `reads every size unit`() {
        assertEquals(700L * 1024 * 1024, parseStreamMetadata(null, "💾 700 MB").sizeBytes)
        assertEquals(2L * 1024 * 1024 * 1024, parseStreamMetadata(null, "Size: 2GiB").sizeBytes)
        assertEquals(1024L * 1024 * 1024 * 1024, parseStreamMetadata(null, "1 TB").sizeBytes)
        // A comma decimal separator, which European indexers use.
        assertEquals(1610612736L, parseStreamMetadata(null, "1,5 GB").sizeBytes)
    }

    @Test
    fun `reads seeders written as words as well as emoji`() {
        assertEquals(42, parseStreamMetadata(null, "42 seeders").seeders)
        assertEquals(7, parseStreamMetadata(null, "Seeds: 7").seeders)
        assertEquals(1, parseStreamMetadata(null, "👤 1 💾 300 MB").seeders)
    }

    // Torrentio's fields are emoji-delimited on one line, and the indexer is not always the last of
    // them. Running the capture to end-of-line put the language in the picker as part of the
    // release group.
    @Test
    fun `stops the release group at the next field marker`() {
        assertEquals("Nyaa", parseStreamMetadata(null, "👤 243 💾 1.4 GB ⚙️ Nyaa 🌐 English").releaseGroup)
        assertEquals("AnimeTosho", parseStreamMetadata(null, "⚙️ AnimeTosho 🌐 Japanese/English").releaseGroup)
        // Last field on the line still reads to the end.
        assertEquals("Nyaa", parseStreamMetadata(null, "👤 243 💾 1.4 GB ⚙️ Nyaa").releaseGroup)
    }

    // A leading "[SubsPlease]" is the release group by convention; a bracket further in is a tag.
    @Test
    fun `takes a release group from a leading bracket only`() {
        assertEquals("Erai-raws", parseStreamMetadata(null, "[Erai-raws] Show - 01 [1080p].mkv").releaseGroup)
        assertNull(parseStreamMetadata(null, "Show - 01 [multi-audio].mkv").releaseGroup)
    }

    // Malformed input is normal here — this text is written by third parties whose only contract is
    // that it is a string.
    @Test
    fun `never throws on junk`() {
        listOf("", "   ", "💾💾💾", "👤", "p", "9999999999999999999 GB", "[", "⚙️").forEach { junk ->
            parseStreamMetadata(junk, junk)
        }
    }

    private fun option(
        url: String,
        resolution: Int? = null,
        seeders: Int? = null,
        sizeBytes: Long? = null,
        sourceName: String = "src",
    ) = StreamOption(
        video = Video(url = url, quality = ""),
        sourceId = 1L,
        sourceName = sourceName,
        metadata = StreamMetadata(resolution = resolution, seeders = seeders, sizeBytes = sizeBytes),
    )

    @Test
    fun `sorts by resolution first, with unknown last`() {
        val sorted = listOf(
            option("https://a", resolution = null),
            option("https://b", resolution = 720),
            option("https://c", resolution = 2160),
            option("https://d", resolution = 1080),
        ).sortedBestFirst()
        assertEquals(listOf("https://c", "https://d", "https://b", "https://a"), sorted.map { it.video.url })
    }

    // At the same resolution a direct link outranks a torrent however many seeders the torrent has:
    // it starts playing without finding peers first, which is not a quality claim but is the
    // difference the user feels.
    @Test
    fun `prefers a direct stream over a torrent at the same resolution`() {
        val sorted = listOf(
            option("torrent://abc/0", resolution = 1080, seeders = 900),
            option("https://direct", resolution = 1080),
        ).sortedBestFirst()
        assertEquals(listOf("https://direct", "torrent://abc/0"), sorted.map { it.video.url })
    }

    @Test
    fun `orders torrents by seeders then size`() {
        val sorted = listOf(
            option("torrent://a/0", resolution = 1080, seeders = 5, sizeBytes = 900),
            option("torrent://b/0", resolution = 1080, seeders = 50, sizeBytes = 100),
            option("torrent://c/0", resolution = 1080, seeders = 50, sizeBytes = 800),
        ).sortedBestFirst()
        assertEquals(
            listOf("torrent://b/0", "torrent://c/0", "torrent://a/0").sorted(),
            sorted.map { it.video.url }.sorted(),
        )
        assertEquals("torrent://c/0", sorted[0].video.url)
        assertEquals("torrent://b/0", sorted[1].video.url)
        assertEquals("torrent://a/0", sorted[2].video.url)
    }

    @Test
    fun `recognises both torrent url forms`() {
        assertTrue(option("torrent://hash/0").isTorrent)
        assertTrue(option("magnet:?xt=urn:btih:abc").isTorrent)
        assertTrue(!option("https://example.test/a.mp4").isTorrent)
    }

    private fun episode(number: Float, season: Int? = null, url: String = "e$number-s$season") =
        Episode(url = url, name = "Episode $number", episodeNumber = number, season = season)

    @Test
    fun `matches an episode by number`() {
        val list = listOf(episode(1f), episode(2f), episode(3f))
        assertEquals("e2.0-snull", list.matchingEpisode(episode(2f))?.url)
        assertNull(list.matchingEpisode(episode(9f)))
    }

    @Test
    fun `prefers the same season when both sides report one`() {
        val list = listOf(episode(3f, season = 1), episode(3f, season = 2))
        assertEquals(2, list.matchingEpisode(episode(3f, season = 2))?.season)
    }

    // A source whose page covers one season reports no season at all. Its unqualified entry is the
    // only match it can offer, and refusing it would exclude every scripted extension from the pool.
    @Test
    fun `falls back to a seasonless entry`() {
        val list = listOf(episode(3f, season = null))
        assertEquals("e3.0-snull", list.matchingEpisode(episode(3f, season = 2))?.url)
    }

    // The case that must not guess: episode 3 exists in seasons 1 and 3, the caller asked for
    // season 2, and any answer plays the wrong episode.
    @Test
    fun `declines to guess when several seasons carry the number and none is the one asked for`() {
        val list = listOf(episode(3f, season = 1), episode(3f, season = 3))
        assertNull(list.matchingEpisode(episode(3f, season = 2)))
    }

    // A lone candidate naming a different season is refused, deliberately.
    //
    // It could be a source numbering its one season from 1 where the target numbers from the
    // series — legitimate — or a source showing a genuinely different season, which is what a
    // two-cour show split in two by one source and left whole by AniList produces. The two are
    // indistinguishable here and only one is safe.
    @Test
    fun `refuses a lone candidate that names a different season`() {
        val list = listOf(episode(3f, season = 1))
        assertNull(list.matchingEpisode(episode(3f, season = 2)))
    }

    // The target naming no season is the ordinary scripted-extension case: there is nothing for a
    // candidate to contradict, so a unique match by number stands.
    @Test
    fun `takes a lone candidate when the target names no season`() {
        val list = listOf(episode(3f, season = 4))
        assertEquals(4, list.matchingEpisode(episode(3f, season = null))?.season)
    }

    @Test
    fun `refuses when the target names no season and several seasons carry the number`() {
        val list = listOf(episode(3f, season = 1), episode(3f, season = 2))
        assertNull(list.matchingEpisode(episode(3f, season = null)))
    }

    @Test
    fun `matches half-numbered specials`() {
        val list = listOf(episode(3f), episode(3.5f), episode(4f))
        assertEquals("e3.5-snull", list.matchingEpisode(episode(3.5f))?.url)
    }
}
