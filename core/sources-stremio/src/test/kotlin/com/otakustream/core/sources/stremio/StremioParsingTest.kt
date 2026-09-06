package com.otakustream.core.sources.stremio

import com.otakustream.core.sources.stremio.model.AddonListOrigin
import com.otakustream.core.sources.stremio.model.parseAddonCollection
import com.otakustream.core.sources.stremio.model.parseStreamResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioParsingTest {

    @Test
    fun `parses tracker announce urls from a torrent stream`() {
        val json = """
            {"streams":[{
              "name":"Torrentio 1080p",
              "infoHash":"8c9c2f1e4a5b6d7e8f9a0b1c2d3e4f5a6b7c8d9e",
              "fileIdx":2,
              "sources":[
                "tracker:udp://tracker.opentrackr.org:1337/announce",
                "tracker:http://tracker.example.com/announce",
                "dht:8c9c2f1e4a5b6d7e8f9a0b1c2d3e4f5a6b7c8d9e"
              ]
            }]}
        """.trimIndent()
        val stream = parseStreamResponse(json).streams.single()
        assertEquals("8c9c2f1e4a5b6d7e8f9a0b1c2d3e4f5a6b7c8d9e", stream.infoHash)
        assertEquals(2, stream.fileIdx)
        // dht: entries are dropped — only announce URLs are usable as trackers.
        assertEquals(
            listOf("udp://tracker.opentrackr.org:1337/announce", "http://tracker.example.com/announce"),
            stream.trackers,
        )
    }

    // Torrentio — the add-on that matters most for anime — writes its release line into the
    // protocol's older `title` field rather than `description`. Reading only `description` dropped
    // the size, seeder count and indexer for every stream it returned, which is everything the
    // picker uses to tell two 1080p entries apart.
    @Test
    fun `reads a release line from title when description is absent`() {
        val releaseLine = "Show - 12.mkv\n👤 243 💾 1.4 GB ⚙️ Nyaa"
        val json = """
            {"streams":[{
              "name":"Torrentio\n1080p",
              "infoHash":"8c9c2f1e4a5b6d7e8f9a0b1c2d3e4f5a6b7c8d9e",
              "title":"Show - 12.mkv\n👤 243 💾 1.4 GB ⚙️ Nyaa"
            }]}
        """.trimIndent()
        assertEquals(releaseLine, parseStreamResponse(json).streams.single().description)
    }

    // `description` replaced `title`, so an add-on that sets both is setting `title` for old
    // clients and means the newer field.
    @Test
    fun `description wins over title when both are present`() {
        val json = """{"streams":[{"url":"https://example.test/a.mp4","title":"old","description":"new"}]}"""
        assertEquals("new", parseStreamResponse(json).streams.single().description)
    }

    @Test
    fun `a stream with neither has no description`() {
        val json = """{"streams":[{"url":"https://example.test/a.mp4"}]}"""
        assertNull(parseStreamResponse(json).streams.single().description)
    }

    @Test
    fun `a stream without sources has no trackers`() {
        val json = """{"streams":[{"infoHash":"8c9c2f1e4a5b6d7e8f9a0b1c2d3e4f5a6b7c8d9e"}]}"""
        assertTrue(parseStreamResponse(json).streams.single().trackers.isEmpty())
    }

    @Test
    fun `drops malformed and duplicate source entries`() {
        // A bare "tracker:" contributes a blank announce URL, which only produces a failing tracker;
        // an unprefixed entry is something we don't understand and must not guess at.
        val json = """
            {"streams":[{
              "infoHash":"8c9c2f1e4a5b6d7e8f9a0b1c2d3e4f5a6b7c8d9e",
              "sources":[
                "tracker:",
                "tracker:   ",
                "udp://unprefixed.example.com/announce",
                "tracker:udp://dupe.example.com/announce",
                "tracker:udp://dupe.example.com/announce"
              ]
            }]}
        """.trimIndent()
        assertEquals(
            listOf("udp://dupe.example.com/announce"),
            parseStreamResponse(json).streams.single().trackers,
        )
    }

    @Test
    fun `a sources value of the wrong shape is ignored rather than throwing`() {
        // Add-on responses are third-party input; a non-array "sources" must not sink the stream list.
        val json = """{"streams":[{"infoHash":"8c9c2f1e4a5b6d7e8f9a0b1c2d3e4f5a6b7c8d9e","sources":"nope"}]}"""
        val stream = parseStreamResponse(json).streams.single()
        assertTrue(stream.trackers.isEmpty())
    }

    @Test
    fun `parses an addon collection entry`() {
        val json = """
            [
              {
                "transportUrl":"https://example.com/manifest.json",
                "manifest":{
                  "name":"Cinemeta",
                  "description":"The official addon",
                  "logo":"https://example.com/logo.png",
                  "types":["movie","series"]
                }
              }
            ]
        """.trimIndent()
        val listings = parseAddonCollection(json, AddonListOrigin.OFFICIAL)
        assertEquals(1, listings.size)
        val listing = listings.first()
        assertEquals("Cinemeta", listing.name)
        assertEquals("The official addon", listing.description)
        assertEquals("https://example.com/logo.png", listing.logoUrl)
        assertEquals("https://example.com/manifest.json", listing.transportUrl)
        assertEquals(listOf("movie", "series"), listing.types)
    }

    @Test
    fun `skips entries without a name or transport url`() {
        val json = """
            [
              {"manifest":{"name":"NoTransport"}},
              {"transportUrl":"https://x/manifest.json","manifest":{"description":"no name"}},
              {"transportUrl":"https://ok/manifest.json","manifest":{"name":"Ok"}}
            ]
        """.trimIndent()
        val listings = parseAddonCollection(json, AddonListOrigin.OFFICIAL)
        assertEquals(1, listings.size)
        assertEquals("Ok", listings.first().name)
    }

    // A user-supplied list URL (issue #10) is parsed by this same function, and the client turns an
    // empty result into "no add-ons found" rather than silently showing nothing. That only works if
    // a well-formed JSON array of unrelated objects really does parse to empty.
    @Test
    fun `a json array that is not an addon collection parses to nothing`() {
        val json = """[{"unrelated":true},{"also":"not an addon"}]"""
        assertTrue(parseAddonCollection(json, AddonListOrigin.OFFICIAL).isEmpty())
    }

    // The origin is a required argument rather than a defaulted field, so a caller can't accidentally
    // label a community or user-supplied add-on as vetted Official by forgetting to stamp it.
    @Test
    fun `listings carry the origin the caller declared`() {
        val json = """[{"transportUrl":"https://x/manifest.json","manifest":{"name":"N"}}]"""
        assertEquals(AddonListOrigin.CUSTOM, parseAddonCollection(json, AddonListOrigin.CUSTOM).single().origin)
        assertEquals(AddonListOrigin.COMMUNITY, parseAddonCollection(json, AddonListOrigin.COMMUNITY).single().origin)
    }

    @Test
    fun `treats json-null strings as null`() {
        val json = """
            [{"transportUrl":"https://x/manifest.json","manifest":{"name":"N","description":null,"logo":null}}]
        """.trimIndent()
        val listing = parseAddonCollection(json, AddonListOrigin.OFFICIAL).single()
        assertNull(listing.description)
        assertNull(listing.logoUrl)
        assertTrue(listing.types.isEmpty())
    }

    @Test
    fun `normalizes manifest urls`() {
        assertEquals(
            "https://ex.com/manifest.json",
            normalizeStremioManifestUrl("stremio://ex.com/manifest.json"),
        )
        assertEquals(
            "https://ex.com/manifest.json",
            normalizeStremioManifestUrl("ex.com"),
        )
        assertEquals(
            "http://ex.com/manifest.json",
            normalizeStremioManifestUrl("http://ex.com/"),
        )
        assertEquals(
            "https://ex.com/manifest.json",
            normalizeStremioManifestUrl("  https://ex.com/manifest.json  "),
        )
    }
}
