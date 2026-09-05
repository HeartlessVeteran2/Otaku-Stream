package com.otakustream.core.sources.stremio

import com.otakustream.core.sources.stremio.model.AddonKind
import com.otakustream.core.sources.stremio.model.AddonListOrigin
import com.otakustream.core.sources.stremio.model.OfficialAddonListing
import com.otakustream.core.sources.stremio.model.kind
import com.otakustream.core.sources.stremio.model.parseAddonCollection
import com.otakustream.core.sources.stremio.model.parseStreamResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // Both forms are current, and the add-ons that matter most here — Comet, MediaFusion — use the
    // object form. Reading only the string form left every one of them classified as "other",
    // which is the bucket the directory filter cannot show under Streams.
    @Test
    fun `reads resource names in both the string and object forms`() {
        val json = """
            [{"transportUrl":"https://x/manifest.json","manifest":{
              "name":"Mixed",
              "resources":["catalog",{"name":"stream","types":["movie"]},{"name":"meta"}]
            }}]
        """.trimIndent()
        val listing = parseAddonCollection(json, AddonListOrigin.COMMUNITY).single()
        assertEquals(listOf("catalog", "stream", "meta"), listing.resources)
        // Streams wins when an add-on does several things: it is the scarce one, and the one
        // someone browsing this screen is looking for.
        assertEquals(AddonKind.STREAMS, listing.kind())
    }

    @Test
    fun `classifies catalogs and subtitles`() {
        fun listingWith(vararg resources: String): OfficialAddonListing {
            val res = resources.joinToString(",") { "\"$it\"" }
            val json = """[{"transportUrl":"https://x/manifest.json","manifest":{"name":"N","resources":[$res]}}]"""
            return parseAddonCollection(json, AddonListOrigin.COMMUNITY).single()
        }
        assertEquals(AddonKind.CATALOGS, listingWith("catalog", "meta").kind())
        assertEquals(AddonKind.SUBTITLES, listingWith("subtitles").kind())
        // No resources at all is what a configuration-required add-on serves until it is
        // configured; it is not a stream add-on yet and must not claim to be.
        assertEquals(AddonKind.OTHER, listingWith().kind())
    }

    // An add-on that serves nothing until configured installs perfectly happily and then returns no
    // streams forever, which looks exactly like a broken app. Both spellings are read because
    // add-ons use both.
    @Test
    fun `reads configurable and configuration-required from either place`() {
        val hinted = """[{"transportUrl":"https://x/manifest.json","manifest":{"name":"N",
            "behaviorHints":{"configurable":true,"configurationRequired":true}}}]"""
        parseAddonCollection(hinted, AddonListOrigin.COMMUNITY).single().let {
            assertTrue(it.isConfigurable)
            assertTrue(it.configurationRequired)
            assertEquals("https://x/configure", it.configureUrl)
        }

        val topLevel = """[{"transportUrl":"https://x/manifest.json","manifest":{"name":"N",
            "configurationRequired":true}}]"""
        assertTrue(parseAddonCollection(topLevel, AddonListOrigin.COMMUNITY).single().configurationRequired)

        val plain = """[{"transportUrl":"https://x/manifest.json","manifest":{"name":"N"}}]"""
        parseAddonCollection(plain, AddonListOrigin.COMMUNITY).single().let {
            assertFalse(it.isConfigurable)
            assertNull(it.configureUrl)
        }
    }

    // The configure page lives at /configure on the same host, by convention and by the official
    // SDK's routing — including for an add-on whose manifest sits under a path.
    @Test
    fun `derives the configure url from the transport url`() {
        val json = """[{"transportUrl":"https://host/stremio/torz/manifest.json","manifest":{"name":"N",
            "behaviorHints":{"configurable":true}}}]"""
        assertEquals(
            "https://host/stremio/torz/configure",
            parseAddonCollection(json, AddonListOrigin.COMMUNITY).single().configureUrl,
        )
    }

    // A configured add-on's manifest URL routinely carries a query, and removeSuffix only matches
    // at the end of the string — so this used to produce "…/manifest.json?token=x/configure": a
    // link to nothing, opened in the browser at the exact moment the user was trying to make the
    // add-on work. The query configures the manifest and means nothing to the page that generates
    // it, so it is dropped rather than carried.
    @Test
    fun `builds a configure url from a manifest carrying a query or fragment`() {
        fun configureUrlOf(transport: String): String? {
            val json = """[{"transportUrl":"$transport","manifest":{"name":"N",
                "behaviorHints":{"configurable":true}}}]"""
            return parseAddonCollection(json, AddonListOrigin.COMMUNITY).single().configureUrl
        }
        assertEquals("https://host/configure", configureUrlOf("https://host/manifest.json?token=x"))
        assertEquals("https://host/configure", configureUrlOf("https://host/manifest.json#frag"))
        assertEquals("https://host/a/b/configure", configureUrlOf("https://host/a/b/manifest.json?c=1"))
        // A trailing slash parses to an empty last segment, which must not become "//configure".
        assertEquals("https://host/a/configure", configureUrlOf("https://host/a/manifest.json/"))
    }

    // stremio:// is the deep-link spelling of the same address and turns up in hand-written lists.
    // A browser cannot open it, so it is mapped to https exactly as installation already does.
    @Test
    fun `maps the stremio scheme to https for the configure url`() {
        val json = """[{"transportUrl":"stremio://host/manifest.json","manifest":{"name":"N",
            "behaviorHints":{"configurable":true}}}]"""
        assertEquals(
            "https://host/configure",
            parseAddonCollection(json, AddonListOrigin.COMMUNITY).single().configureUrl,
        )
    }

    @Test
    fun `an unparseable transport url yields no configure url`() {
        val json = """[{"transportUrl":"not a url","manifest":{"name":"N",
            "behaviorHints":{"configurable":true}}}]"""
        assertNull(parseAddonCollection(json, AddonListOrigin.COMMUNITY).single().configureUrl)
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
