package com.otakustream.core.sources.mangayomi

import com.otakustream.core.sources.api.stableSourceId
import com.otakustream.core.sources.mangayomi.repo.parseMangayomiIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MangayomiIndexTest {

    @Test
    fun `keeps only JS anime entries`() {
        val json = """
            [
              {"name":"AnimeJs","lang":"en","sourceCodeUrl":"https://x/a.js","itemType":1,"sourceCodeLanguage":1,"id":10},
              {"name":"MangaJs","lang":"en","sourceCodeUrl":"https://x/m.js","itemType":0,"sourceCodeLanguage":1,"id":11},
              {"name":"AnimeDart","lang":"en","sourceCodeUrl":"https://x/d.js","itemType":1,"sourceCodeLanguage":0,"id":12}
            ]
        """.trimIndent()
        val parsed = parseMangayomiIndex(json)
        assertEquals(1, parsed.listings.size)
        assertEquals("AnimeJs", parsed.listings.first().name)
        assertEquals(10L, parsed.listings.first().id)
        // The two it dropped are reported rather than vanishing: the screen tells the user how many
        // entries a repo carries that this app cannot run.
        assertEquals(2, parsed.unsupportedCount)
    }

    @Test
    fun `dedupes an id repeated in the same language, keeping the first`() {
        val json = """
            [
              {"name":"First","lang":"en","sourceCodeUrl":"https://x/1.js","itemType":1,"sourceCodeLanguage":1,"id":5},
              {"name":"Second","lang":"en","sourceCodeUrl":"https://x/2.js","itemType":1,"sourceCodeLanguage":1,"id":5}
            ]
        """.trimIndent()
        val listings = parseMangayomiIndex(json).listings
        assertEquals(1, listings.size)
        assertEquals("First", listings.first().name)
    }

    // The other half of that rule, and the reason it is keyed on language too.
    //
    // A repo giving two language variants of one extension the same declared id is not a malformed
    // duplicate — it is two distinct sources, and Swakshan's real index does exactly this for
    // Animeonsen. Deduping on id alone threw one of them away.
    @Test
    fun `keeps two languages that share an id`() {
        val json = """
            [
              {"name":"Dual","lang":"en","sourceCodeUrl":"https://x/en.js","itemType":1,"sourceCodeLanguage":1,"id":7},
              {"name":"Dual","lang":"ja","sourceCodeUrl":"https://x/ja.js","itemType":1,"sourceCodeLanguage":1,"id":7}
            ]
        """.trimIndent()
        val listings = parseMangayomiIndex(json).listings
        assertEquals(2, listings.size)
        assertEquals(setOf("en", "ja"), listings.map { it.lang }.toSet())
        // And with ids of their own: the browse list, the installed set, the registry and the
        // database primary key are all keyed on the id alone, so two rows sharing one would crash
        // Compose and make installing either variant overwrite the other.
        assertEquals(2, listings.map { it.id }.toSet().size)
    }

    @Test
    fun `derives a stable id when none is declared`() {
        val json = """
            [{"name":"NoId","lang":"fr","sourceCodeUrl":"https://x/n.js","itemType":1,"sourceCodeLanguage":1}]
        """.trimIndent()
        val listings = parseMangayomiIndex(json).listings
        assertEquals(1, listings.size)
        assertEquals(stableSourceId("NoId", "fr"), listings.first().id)
    }

    @Test
    fun `drops entries missing required fields`() {
        val json = """
            [
              {"lang":"en","sourceCodeUrl":"https://x/a.js","itemType":1,"sourceCodeLanguage":1},
              {"name":"NoUrl","lang":"en","itemType":1,"sourceCodeLanguage":1}
            ]
        """.trimIndent()
        assertTrue(parseMangayomiIndex(json).listings.isEmpty())
    }
}
