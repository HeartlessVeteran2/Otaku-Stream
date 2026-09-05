package com.otakustream.core.sources.stremio

import com.otakustream.core.sources.stremio.model.AddonKind
import com.otakustream.core.sources.stremio.model.AddonListOrigin
import com.otakustream.core.sources.stremio.model.kind
import com.otakustream.core.sources.stremio.model.parseAddonCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Guards the checked-in stremio-addons.net harvest, not the parser.
//
// The data is the fragile part: it was scraped once from a site with no API, so the risk is not
// that parsing breaks but that a bad edit lands entries the app then shows. These assertions are
// about the shape and the safety of that file.
class BundledCommunityAddonsTest {

    private val listings by lazy {
        val json = checkNotNull(javaClass.classLoader?.getResourceAsStream("community_addons.json"))
            .bufferedReader()
            .use { it.readText() }
        parseAddonCollection(json, AddonListOrigin.THIRD_PARTY)
    }

    @Test
    fun `every entry parses and is usable`() {
        assertTrue("expected a substantial catalogue, got ${listings.size}", listings.size >= 30)
        listings.forEach { listing ->
            assertTrue("blank name", listing.name.isNotBlank())
            assertTrue("bad url on ${listing.name}: ${listing.transportUrl}",
                listing.transportUrl.startsWith("https://"))
            assertTrue("not a manifest url on ${listing.name}",
                listing.transportUrl.endsWith("/manifest.json"))
        }
    }

    // The whole point of this tier: Stremio's own lists contain no stream add-ons, so if the
    // harvest ever stops carrying them it has silently become the thing it was added to fix.
    @Test
    fun `carries stream add-ons, which is why it exists`() {
        val streams = listings.filter { it.kind() == AddonKind.STREAMS }
        assertTrue("expected stream add-ons, got ${streams.size}", streams.size >= 15)
    }

    // All four carry behaviorHints.adult in the checked-in file — but two of them only because the
    // harvest put it there. Upstream, OnlyPorn and xxxClub declare no behaviorHints at all and a
    // type of "movie", so nothing in their own manifests says what they are; the type-word fallback
    // does not catch them either. The marking in this file is the only thing standing between them
    // and someone who left the setting off, which is why it is asserted rather than assumed.
    @Test
    fun `known adult entries are marked adult`() {
        val adultNames = listings.filter { it.isAdult }.map { it.name }.toSet()
        listOf("OnlyPorn", "xxxClub", "TPB 4K Porn", "Porn Tube").forEach { name ->
            assertTrue("$name is not marked adult; marked: $adultNames", name in adultNames)
        }
    }

    @Test
    fun `no loopback or placeholder urls survived the harvest`() {
        listings.forEach { listing ->
            val url = listing.transportUrl.lowercase()
            listOf("localhost", "127.0.0.1", "temporary_username", "%7b%7d").forEach { bad ->
                assertTrue("${listing.name} carries a placeholder url: ${listing.transportUrl}",
                    !url.contains(bad))
            }
        }
    }

    @Test
    fun `entries are unique by url`() {
        assertEquals(listings.map { it.transportUrl }.distinct().size, listings.size)
    }
}
