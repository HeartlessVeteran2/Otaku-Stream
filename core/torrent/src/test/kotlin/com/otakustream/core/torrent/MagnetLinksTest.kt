package com.otakustream.core.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Magnets arrive from other apps — browsers, torrent indexes, share sheets — so malformed input is
// expected traffic rather than a programming error. Every case here is a shape seen in the wild.
class MagnetLinksTest {

    private val hex = "8c9c2f1e4a5b6d7e8f9a0b1c2d3e4f5a6b7c8d9e"

    @Test
    fun `recognizes the scheme`() {
        assertTrue(MagnetLinks.isMagnet("magnet:?xt=urn:btih:$hex"))
        assertTrue(MagnetLinks.isMagnet("MAGNET:?xt=urn:btih:$hex"))
        assertFalse(MagnetLinks.isMagnet("torrent://$hex/0"))
        assertFalse(MagnetLinks.isMagnet("https://example.com/x.torrent"))
    }

    @Test
    fun `parses a hex info hash`() {
        val link = MagnetLinks.parse("magnet:?xt=urn:btih:$hex")
        assertEquals(hex, link?.infoHash)
        assertEquals(emptyList<String>(), link?.trackers)
        assertNull(link?.displayName)
    }

    @Test
    fun `parses trackers and display name`() {
        val link = MagnetLinks.parse(
            "magnet:?xt=urn:btih:$hex" +
                "&dn=Some%20Show%20S01E01" +
                "&tr=udp%3A%2F%2Ftracker.example%3A1337%2Fannounce" +
                "&tr=udp%3A%2F%2Fopen.example%3A80%2Fannounce",
        )
        assertEquals("Some Show S01E01", link?.displayName)
        assertEquals(
            listOf("udp://tracker.example:1337/announce", "udp://open.example:80/announce"),
            link?.trackers,
        )
    }

    @Test
    fun `deduplicates repeated trackers`() {
        // Indexes routinely paste the same public tracker list twice.
        val tracker = "udp%3A%2F%2Ftracker.example%3A1337"
        val link = MagnetLinks.parse("magnet:?xt=urn:btih:$hex&tr=$tracker&tr=$tracker")
        assertEquals(1, link?.trackers?.size)
    }

    @Test
    fun `normalizes hash case`() {
        // Same torrent in two spellings must produce one identity, or it becomes two resume positions.
        assertEquals(hex, MagnetLinks.parse("magnet:?xt=urn:btih:${hex.uppercase()}")?.infoHash)
    }

    @Test
    fun `converts a base32 hash to hex`() {
        // 32 base32 characters carry the same 160 bits as 40 hex characters. "AAAA…" is 20 zero bytes.
        val base32 = "A".repeat(32)
        assertEquals("0".repeat(40), MagnetLinks.parse("magnet:?xt=urn:btih:$base32")?.infoHash)
    }

    @Test
    fun `rejects a base32 hash with characters outside the alphabet`() {
        // '1', '0' and '8' are not in the RFC 4648 base32 alphabet.
        assertNull(MagnetLinks.parse("magnet:?xt=urn:btih:${"1".repeat(32)}"))
    }

    @Test
    fun `rejects a v2-only magnet`() {
        // btmh is a SHA-256 v2 hash. Accepting it would build a url that parses and never resolves.
        val v2 = "magnet:?xt=urn:btmh:1220caf1e1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d"
        assertNull(MagnetLinks.parse(v2))
    }

    @Test
    fun `takes the v1 hash from a hybrid magnet`() {
        // A v1+v2 hybrid carries both; only the v1 hash is usable here.
        val link = MagnetLinks.parse("magnet:?xt=urn:btih:$hex&xt=urn:btmh:1220abcd")
        assertEquals(hex, link?.infoHash)
    }

    @Test
    fun `rejects magnets with no usable hash`() {
        assertNull(MagnetLinks.parse("magnet:?dn=Just%20A%20Name"))
        assertNull(MagnetLinks.parse("magnet:?xt=urn:btih:tooshort"))
        assertNull(MagnetLinks.parse("magnet:"))
        assertNull(MagnetLinks.parse("magnet:?"))
    }

    @Test
    fun `skips malformed parameters instead of failing`() {
        // A bare flag and an empty value must not become a blank tracker url libtorrent would try to
        // contact — but they also must not sink the rest of the link.
        val link = MagnetLinks.parse("magnet:?xt=urn:btih:$hex&tr=&flag&tr=udp%3A%2F%2Fok.example")
        assertEquals(listOf("udp://ok.example"), link?.trackers)
    }

    @Test
    fun `keeps a literal plus in a tracker url`() {
        // URLDecoder implements form encoding, where '+' means space — but a magnet is an RFC 3986 URI,
        // where it is a literal. Decoded naively, a passkey containing '+' gains a space and every
        // announce to that tracker fails.
        val link = MagnetLinks.parse("magnet:?xt=urn:btih:$hex&tr=https%3A%2F%2Ft.example%2Fa%2Bb%2Fannounce")
        assertEquals(listOf("https://t.example/a+b/announce"), link?.trackers)
    }

    @Test
    fun `decodes a percent-encoded space as a space`() {
        // The other half of the same rule: %20 is still a space.
        assertEquals("Some Show", MagnetLinks.parse("magnet:?xt=urn:btih:$hex&dn=Some%20Show")?.displayName)
    }

    @Test
    fun `survives a broken percent escape`() {
        // "%zz" makes URLDecoder throw; that parameter is dropped, the link still parses.
        val link = MagnetLinks.parse("magnet:?xt=urn:btih:$hex&tr=%zz&dn=Fine")
        assertEquals(hex, link?.infoHash)
        assertEquals(emptyList<String>(), link?.trackers)
        assertEquals("Fine", link?.displayName)
    }

    @Test
    fun `converts to the canonical playback url`() {
        val link = MagnetLinks.parse("magnet:?xt=urn:btih:$hex")!!
        assertEquals("torrent://$hex/auto", MagnetLinks.toTorrentUrl(link))
    }
}
