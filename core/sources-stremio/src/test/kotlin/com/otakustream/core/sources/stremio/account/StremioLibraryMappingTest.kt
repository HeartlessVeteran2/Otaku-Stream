package com.otakustream.core.sources.stremio.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioLibraryMappingTest {

    @Test
    fun `maps a valid stremio catalog save`() {
        val item = stremioLibraryItemFor("series|tt1234567", "Some Show", "https://poster")
        assertEquals(StremioLibraryItem("tt1234567", "series", "Some Show", "https://poster", false), item)
    }

    @Test
    fun `accepts addon-namespaced ids`() {
        assertEquals("kitsu:42", stremioLibraryItemFor("anime|kitsu:42", "X", null)?.id)
    }

    @Test
    fun `rejects non-stremio saves so junk never reaches the account`() {
        // A scripted/mangayomi save is a real URL, not "type|id".
        assertNull(stremioLibraryItemFor("https://example.com/anime/123", "X", null))
        // Unknown type.
        assertNull(stremioLibraryItemFor("bogus|tt1", "X", null))
        // Not a Stremio-shaped id.
        assertNull(stremioLibraryItemFor("movie|just-a-slug", "X", null))
        // Missing pieces.
        assertNull(stremioLibraryItemFor("movie|", "X", null))
        assertNull(stremioLibraryItemFor("noseparator", "X", null))
    }

    @Test
    fun `isStremioMetaId distinguishes real ids from slugs`() {
        assertTrue(isStremioMetaId("tt0111161"))
        assertTrue(isStremioMetaId("mal:5678"))
        assertFalse(isStremioMetaId("tt"))
        assertFalse(isStremioMetaId("ttabc"))
        assertFalse(isStremioMetaId("plainslug"))
        assertFalse(isStremioMetaId(":leadingcolon"))
        assertFalse(isStremioMetaId("trailingcolon:"))
    }
}
