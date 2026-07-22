package com.otakustream.feature.tracking

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AniListParsingTest {

    private val fullMedia = """
        {
          "id": 1234,
          "episodes": 12,
          "title": {"romaji": "Romaji Name", "english": "English Name", "native": "ネイティブ"},
          "coverImage": {"extraLarge": "https://x/xl.jpg", "large": "https://x/l.jpg"},
          "bannerImage": "https://x/banner.jpg",
          "description": "A show.",
          "genres": ["Action", "Comedy"],
          "averageScore": 84,
          "format": "TV",
          "status": "RELEASING",
          "season": "WINTER",
          "seasonYear": 2024,
          "nextAiringEpisode": {"episode": 5, "airingAt": 1700000000}
        }
    """.trimIndent()

    @Test
    fun `parseMedia reads all fields and prefers english title`() {
        val media = parseMedia(JSONObject(fullMedia))
        assertEquals(1234L, media.id)
        assertEquals(12, media.episodes)
        assertEquals("English Name", media.displayTitle)
        assertEquals("Romaji Name", media.romajiTitle)
        assertEquals("https://x/xl.jpg", media.coverImageUrl)
        assertEquals("https://x/banner.jpg", media.bannerImageUrl)
        assertEquals(listOf("Action", "Comedy"), media.genres)
        assertEquals(84, media.averageScore)
        assertEquals("TV", media.format)
        assertEquals("WINTER", media.season)
        assertEquals(2024, media.seasonYear)
        assertEquals(5, media.nextAiringEpisode)
        assertEquals(1700000000L, media.nextAiringAtSeconds)
    }

    @Test
    fun `parseMedia falls back to romaji when english missing and drops zero fields`() {
        val json = """
            {"id": 7, "title": {"romaji": "Only Romaji"}, "episodes": 0, "averageScore": 0}
        """.trimIndent()
        val media = parseMedia(JSONObject(json))
        assertEquals("Only Romaji", media.displayTitle)
        assertNull(media.episodes)
        assertNull(media.averageScore)
        assertNull(media.coverImageUrl)
    }

    @Test
    fun `parseMedia reads relations and recommendations`() {
        val json = """
            {
              "id": 1,
              "title": {"romaji": "Parent"},
              "relations": {"edges": [
                {"relationType": "SEQUEL", "node": {"id": 2, "title": {"romaji": "Sequel"}}}
              ]},
              "recommendations": {"nodes": [
                {"mediaRecommendation": {"id": 3, "title": {"romaji": "Rec"}}}
              ]}
            }
        """.trimIndent()
        val media = parseMedia(JSONObject(json))
        assertEquals(1, media.relations.size)
        assertEquals("SEQUEL", media.relations.first().relationType)
        assertEquals(2L, media.relations.first().media.id)
        assertEquals(1, media.recommendations.size)
        assertEquals(3L, media.recommendations.first().id)
    }

    @Test
    fun `parsePage reads pageInfo and media list`() {
        val json = """
            {
              "pageInfo": {"currentPage": 2, "hasNextPage": true},
              "media": [
                {"id": 1, "title": {"romaji": "A"}},
                {"id": 2, "title": {"romaji": "B"}}
              ]
            }
        """.trimIndent()
        val page = parsePage(JSONObject(json))
        assertEquals(2, page.currentPage)
        assertTrue(page.hasNextPage)
        assertEquals(2, page.media.size)
    }

    @Test
    fun `parseListCollection flattens entries across lists`() {
        val json = """
            {
              "lists": [
                {"name": "Watching", "entries": [
                  {"status": "CURRENT", "score": 8.5, "progress": 4, "media": {"id": 1, "title": {"romaji": "A"}}}
                ]},
                {"name": "Planning", "entries": [
                  {"status": "PLANNING", "score": 0, "progress": 0, "media": {"id": 2, "title": {"romaji": "B"}}}
                ]}
              ]
            }
        """.trimIndent()
        val entries = parseListCollection(JSONObject(json))
        assertEquals(2, entries.size)
        assertEquals("CURRENT", entries[0].status)
        assertEquals(8.5, entries[0].score!!, 0.001)
        assertEquals(4, entries[0].progress)
        assertNull(entries[1].score) // score 0 -> null
    }

    @Test
    fun `parseViewerEntry returns null when not on a list`() {
        assertNull(parseViewerEntry(JSONObject("""{"id": 1}""")))
        val onList = parseViewerEntry(JSONObject("""{"mediaListEntry": {"status": "CURRENT", "score": 7.0, "progress": 3}}"""))
        assertEquals("CURRENT", onList!!.status)
        assertEquals(3, onList.progress)
    }

    @Test
    fun `currentSeasonAndYear maps months to seasons and rolls December forward`() {
        assertEquals("WINTER" to 2024, seasonFor(2024, Calendar.JANUARY))
        assertEquals("SPRING" to 2024, seasonFor(2024, Calendar.APRIL))
        assertEquals("SUMMER" to 2024, seasonFor(2024, Calendar.JULY))
        assertEquals("FALL" to 2024, seasonFor(2024, Calendar.OCTOBER))
        // December belongs to the next year's WINTER on AniList.
        assertEquals("WINTER" to 2025, seasonFor(2024, Calendar.DECEMBER))
    }

    @Test
    fun `stringOrNull treats blanks and json-null as null`() {
        val obj = JSONObject("""{"a": "value", "b": "", "c": null}""")
        assertEquals("value", obj.stringOrNull("a"))
        assertNull(obj.stringOrNull("b"))
        assertNull(obj.stringOrNull("c"))
        assertFalse(obj.has("missing"))
    }

    private fun seasonFor(year: Int, month: Int): Pair<String, Int> {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 15)
        }
        return currentSeasonAndYear(calendar)
    }
}
