package com.otakustream.core.sources.stremio

import com.otakustream.core.sources.stremio.model.parseOfficialAddonCollection
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
        val listings = parseOfficialAddonCollection(json)
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
        val listings = parseOfficialAddonCollection(json)
        assertEquals(1, listings.size)
        assertEquals("Ok", listings.first().name)
    }

    @Test
    fun `treats json-null strings as null`() {
        val json = """
            [{"transportUrl":"https://x/manifest.json","manifest":{"name":"N","description":null,"logo":null}}]
        """.trimIndent()
        val listing = parseOfficialAddonCollection(json).single()
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
