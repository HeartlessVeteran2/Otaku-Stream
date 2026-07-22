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
        val listings = parseMangayomiIndex(json)
        assertEquals(1, listings.size)
        assertEquals("AnimeJs", listings.first().name)
        assertEquals(10L, listings.first().id)
    }

    @Test
    fun `dedupes by id keeping the first`() {
        val json = """
            [
              {"name":"First","lang":"en","sourceCodeUrl":"https://x/1.js","itemType":1,"sourceCodeLanguage":1,"id":5},
              {"name":"Second","lang":"en","sourceCodeUrl":"https://x/2.js","itemType":1,"sourceCodeLanguage":1,"id":5}
            ]
        """.trimIndent()
        val listings = parseMangayomiIndex(json)
        assertEquals(1, listings.size)
        assertEquals("First", listings.first().name)
    }

    @Test
    fun `derives a stable id when none is declared`() {
        val json = """
            [{"name":"NoId","lang":"fr","sourceCodeUrl":"https://x/n.js","itemType":1,"sourceCodeLanguage":1}]
        """.trimIndent()
        val listings = parseMangayomiIndex(json)
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
        assertTrue(parseMangayomiIndex(json).isEmpty())
    }
}
