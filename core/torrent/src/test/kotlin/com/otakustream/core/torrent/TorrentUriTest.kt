package com.otakustream.core.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The url built here is the identity the whole playback stack keys on — resume position, skip
// markers, watch history, AniList completion. A change in how it is built or normalized silently
// orphans every one of those for existing torrents, so the round-trip and the normalization rules
// are pinned here deliberately.
class TorrentUriTest {

    private val hash = "8c9c2f1e4a5b6d7e8f9a0b1c2d3e4f5a6b7c8d9e"

    @Test
    fun `builds canonical url`() {
        assertEquals("torrent://$hash/0", TorrentUri.build(hash, 0))
        assertEquals("torrent://$hash/3", TorrentUri.build(hash, 3))
    }

    @Test
    fun `null file index means the first file`() {
        // Stremio add-ons omit fileIdx for single-file torrents; 0 is the protocol's own fallback.
        assertEquals("torrent://$hash/auto", TorrentUri.build(hash, null))
    }

    @Test
    fun `round trips`() {
        val url = TorrentUri.build(hash, 7)!!
        assertEquals(TorrentRef(hash, 7), TorrentUri.parse(url))
    }

    @Test
    fun `normalizes hash case on both build and parse`() {
        // Two spellings of one torrent must not become two history keys.
        val upper = hash.uppercase()
        assertEquals("torrent://$hash/1", TorrentUri.build(upper, 1))
        assertEquals(TorrentRef(hash, 1), TorrentUri.parse("torrent://$upper/1"))
        assertEquals(TorrentUri.build(upper, 1), TorrentUri.build(hash, 1))
    }

    @Test
    fun `trims surrounding whitespace in the hash`() {
        assertEquals("torrent://$hash/0", TorrentUri.build("  $hash  ", 0))
    }

    @Test
    fun `rejects a hash of the wrong length`() {
        assertNull(TorrentUri.build(hash.dropLast(1), 0))
        assertNull(TorrentUri.build(hash + "a", 0))
        assertNull(TorrentUri.build("", 0))
    }

    @Test
    fun `rejects a v2 sha256 info hash`() {
        // 64 hex chars is a valid BitTorrent v2 hash, but Stremio's protocol is v1 and the engine
        // resolves v1 — accepting it would produce a url that parses and can never play.
        assertNull(TorrentUri.build("a".repeat(64), 0))
    }

    @Test
    fun `rejects a non-hex hash`() {
        assertNull(TorrentUri.build("z".repeat(40), 0))
        assertNull(TorrentUri.build("8c9c2f1e4a5b6d7e8f9a0b1c2d3e4f5a6b7c8d9-", 0))
    }

    @Test
    fun `rejects non-ASCII digits that Char isDigit would accept`() {
        // Char.isDigit() is true for any Unicode decimal digit, so validating with it would let an
        // Arabic-Indic numeral through as "hex" — producing a url that looks valid and never resolves.
        assertNull(TorrentUri.build("٣".repeat(40), 0))
        assertNull(TorrentUri.build(hash.dropLast(1) + "٣", 0))
        // Fullwidth digits are the same trap.
        assertNull(TorrentUri.build(hash.dropLast(1) + "０", 0))
    }

    @Test
    fun `rejects a negative file index`() {
        assertNull(TorrentUri.build(hash, -1))
        assertNull(TorrentUri.parse("torrent://$hash/-1"))
    }

    @Test
    fun `rejects urls that are not ours`() {
        assertNull(TorrentUri.parse("https://example.com/video.mkv"))
        assertNull(TorrentUri.parse("magnet:?xt=urn:btih:$hash"))
        assertNull(TorrentUri.parse(""))
    }

    @Test
    fun `rejects a malformed path`() {
        assertNull(TorrentUri.parse("torrent://$hash"))
        assertNull(TorrentUri.parse("torrent://$hash/0/extra"))
        assertNull(TorrentUri.parse("torrent://$hash/notanumber"))
        assertNull(TorrentUri.parse("torrent://$hash/"))
    }

    @Test
    fun `recognizes its own scheme case-insensitively`() {
        assertTrue(TorrentUri.isTorrentUrl("torrent://$hash/0"))
        assertTrue(TorrentUri.isTorrentUrl("TORRENT://$hash/0"))
        assertFalse(TorrentUri.isTorrentUrl("https://example.com/a.mkv"))
        assertFalse(TorrentUri.isTorrentUrl("content://media/external/video/1"))
    }

    // A magnet has no file list, so "which file" is unknown until metadata arrives. `auto` records
    // that, where index 0 asserted a wrong answer (#109).
    @Test
    fun `an unknown file index round-trips as auto`() {
        val url = TorrentUri.build(hash, null)!!
        assertEquals("torrent://$hash/auto", url)
        val ref = TorrentUri.parse(url)!!
        assertEquals(hash, ref.infoHash)
        assertTrue(ref.isAuto)
        assertEquals(TorrentUri.AUTO_FILE_INDEX, ref.fileIdx)
    }

    @Test
    fun `a named index is not auto`() {
        val ref = TorrentUri.parse(TorrentUri.build(hash, 4)!!)!!
        assertFalse(ref.isAuto)
        assertEquals(4, ref.fileIdx)
    }

    // Case-insensitive because the url can come back through history, an intent, or an add-on that
    // spelled it differently — and a url that parses one way and not the other is two identities.
    @Test
    fun `auto is recognised whatever its case`() {
        assertTrue(TorrentUri.parse("torrent://$hash/AUTO")!!.isAuto)
        assertTrue(TorrentUri.parse("torrent://$hash/Auto")!!.isAuto)
    }

    // The sentinel is an output of parsing, never an input to build: accepting it here would give
    // one torrent two spellings of the same url, and two resume positions.
    @Test
    fun `the auto sentinel is not accepted as a caller supplied index`() {
        assertNull(TorrentUri.build(hash, TorrentUri.AUTO_FILE_INDEX))
    }

    @Test
    fun `a non numeric segment is still rejected`() {
        assertNull(TorrentUri.parse("torrent://$hash/latest"))
        assertNull(TorrentUri.parse("torrent://$hash/"))
    }
}
