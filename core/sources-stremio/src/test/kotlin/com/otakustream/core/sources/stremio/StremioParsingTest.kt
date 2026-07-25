package com.otakustream.core.sources.stremio

import com.otakustream.core.sources.stremio.model.AddonListOrigin
import com.otakustream.core.sources.stremio.model.parseAddonCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioParsingTest {

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
