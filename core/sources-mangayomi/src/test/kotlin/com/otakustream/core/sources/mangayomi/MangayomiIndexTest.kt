package com.otakustream.core.sources.mangayomi

import com.otakustream.core.sources.api.stableSourceId
import com.otakustream.core.sources.mangayomi.repo.parseMangayomiIndex
import com.otakustream.core.sources.mangayomi.repo.withUniqueIds
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
        // Derived from the declared id and the language rather than used raw — see listingId.
        // Deterministic, which is the property that matters: the same entry always parses to the
        // same id, whatever else is in the directory.
        assertEquals(parsed.listings.first().id, parseMangayomiIndex(json).listings.first().id)
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

    // Two repositories, each internally consistent, that together crash the browse list.
    //
    // This is the shape the merged directory hits and a single index never does: repo A publishes
    // id 42 in English, repo B publishes id 42 in Japanese. Neither index has a duplicate, so the
    // per-index rule passes both through untouched — and the merge that only deduped on
    // (id, lang) kept both, handing Compose two list items with the same key. Compose throws on
    // that, so the extensions screen crashes on open, from data neither repo did anything wrong to
    // produce. A user's own custom repo makes it reachable with no coordination at all.
    @Test
    fun `two repos claiming the same id under different languages both survive with distinct ids`() {
        val repoA = """[{"id":42,"name":"Animeonsen","lang":"en","sourceCodeUrl":"https://a.example/x.js"}]"""
        val repoB = """[{"id":42,"name":"Animeonsen","lang":"ja","sourceCodeUrl":"https://b.example/x.js"}]"""

        val parsed = parseMangayomiIndex(repoA, "A").listings + parseMangayomiIndex(repoB, "B").listings
        // Distinct at parse time now, not merely after the merge deduped them. The declared id is
        // folded together with the language, so the two variants never share an id in the first
        // place — which is what makes the id independent of what else loaded.
        assertEquals(2, parsed.size)
        assertEquals(2, parsed.map { it.id }.toSet().size)

        val merged = withUniqueIds(parsed)

        assertEquals("both sources must survive", 2, merged.size)
        assertEquals("and must have distinct ids", 2, merged.map { it.id }.toSet().size)
        assertEquals(setOf("en", "ja"), merged.map { it.lang }.toSet())
    }

    // The other half of the rule: a genuine duplicate — same id, same language, same extension
    // carried by two repos — collapses to one row rather than being given a second identity.
    @Test
    fun `the same extension in two repos shows once`() {
        val repoA = """[{"id":7,"name":"AllAnime","lang":"en","sourceCodeUrl":"https://a.example/all.js"}]"""
        val repoB = """[{"id":7,"name":"AllAnime","lang":"en","sourceCodeUrl":"https://b.example/all.js"}]"""

        val merged = withUniqueIds(
            parseMangayomiIndex(repoA, "A").listings + parseMangayomiIndex(repoB, "B").listings,
        )

        assertEquals(1, merged.size)
        // The first repo wins, so ordering decides provenance rather than chance.
        assertEquals("https://a.example/all.js", merged.single().sourceCodeUrl)
    }

    // The property the first version of this rule did not have: an extension's id does not depend
    // on what else loaded.
    //
    // A curated repo being briefly unreachable used to change the id of a *different* repo's
    // listing, because the old rule handed the declared id to whoever claimed it first in fetch
    // order. Since installedIds, the source registry and the database primary key all key on that
    // id, the same extension read as installed, then not installed, then installed again — and
    // installing it twice made two rows.
    @Test
    fun `an id does not change when another repo is unreachable`() {
        val theirs = """[{"id":42,"name":"Animeonsen","lang":"en","sourceCodeUrl":"https://a.example/x.js"}]"""
        val mine = """[{"id":42,"name":"Animeonsen","lang":"ja","sourceCodeUrl":"https://b.example/x.js"}]"""

        // Both reachable.
        val together = withUniqueIds(
            parseMangayomiIndex(theirs, "A").listings + parseMangayomiIndex(mine, "B").listings,
        )
        // Only the user's own reachable, as when a curated repo is down.
        val aloneMine = withUniqueIds(parseMangayomiIndex(mine, "B").listings)
        val aloneTheirs = withUniqueIds(parseMangayomiIndex(theirs, "A").listings)

        assertEquals(2, together.size)
        assertEquals(
            "the ja listing's id must not depend on whether repo A loaded",
            aloneMine.single().id,
            together.first { it.lang == "ja" }.id,
        )
        assertEquals(
            "nor the en listing's on whether repo B loaded",
            aloneTheirs.single().id,
            together.first { it.lang == "en" }.id,
        )
    }

    // Order must not decide identity either — the merge concatenates repos in whatever order their
    // fetches completed.
    @Test
    fun `ids do not depend on the order repos are merged in`() {
        val a = parseMangayomiIndex(
            """[{"id":7,"name":"AllAnime","lang":"en","sourceCodeUrl":"https://a.example/all.js"}]""",
            "A",
        ).listings
        val b = parseMangayomiIndex(
            """[{"id":7,"name":"AllAnime","lang":"ja","sourceCodeUrl":"https://b.example/all.js"}]""",
            "B",
        ).listings

        assertEquals(
            withUniqueIds(a + b).map { it.lang to it.id }.toSet(),
            withUniqueIds(b + a).map { it.lang to it.id }.toSet(),
        )
    }
}
