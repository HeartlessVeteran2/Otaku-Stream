package com.otakustream.core.sources.mangayomi

import com.otakustream.core.sources.mangayomi.repo.RecommendedExtensionRepos
import com.otakustream.core.sources.mangayomi.repo.parseMangayomiIndex
import com.otakustream.core.sources.mangayomi.repo.withUniqueIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The parser, run against the three indexes it was written for, captured verbatim.
//
// The hand-written cases next door cover the rules; these cover the thing those rules exist to
// consume. Every bug this file guards against was a real one: entries silently dropped for being
// Dart, and a real extension discarded because two language variants shared a declared id. A repo
// changing shape should fail here rather than showing an empty screen on someone's phone.
class RealExtensionIndexTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader()
            .use { it.readText() }

    // Counts, not a range: if a repo publishes a batch of new extensions this test should be looked
    // at, because the numbers are what the screen tells the user.
    @Test
    fun `the curated indexes parse to the extensions this app can run`() {
        val m2k3a = parseMangayomiIndex(fixture("anime_index_m2k3a.json"), "m2k3a")
        val mallyd = parseMangayomiIndex(fixture("anime_index_mallyd11.json"), "Mallyd11")
        val swak = parseMangayomiIndex(fixture("anime_index_swakshan.json"), "Swakshan")

        assertEquals(24, m2k3a.listings.size)
        assertEquals(13, mallyd.listings.size)
        assertEquals(15, swak.listings.size)

        // 52 across the three, which is the claim the curated list is built on.
        assertEquals(52, m2k3a.listings.size + mallyd.listings.size + swak.listings.size)
    }

    // The reason the screen reports a count at all: two thirds of the largest repo is Dart, so a
    // silent filter looks exactly like a broken index.
    @Test
    fun `unsupported entries are counted rather than silently dropped`() {
        val m2k3a = parseMangayomiIndex(fixture("anime_index_m2k3a.json"), "m2k3a")
        assertEquals(40, m2k3a.unsupportedCount)
        assertEquals(64, m2k3a.listings.size + m2k3a.unsupportedCount)

        // The all-JavaScript repos have nothing to report, so the screen stays quiet for them.
        assertEquals(0, parseMangayomiIndex(fixture("anime_index_mallyd11.json")).unsupportedCount)
        assertEquals(0, parseMangayomiIndex(fixture("anime_index_swakshan.json")).unsupportedCount)
    }

    // The regression that motivated this, and the constraint that makes fixing it non-trivial.
    //
    // Swakshan's index gives Animeonsen `en` and Animeonsen `ja` the same declared id. Deduping on
    // id alone kept one and threw the other away — a real extension, in a real repo, silently
    // missing. But simply keeping both under one id is worse: every consumer is keyed on the id
    // alone, so Compose throws on the duplicate list key and installing either variant marks the
    // other. Both survive *and* get distinct ids.
    @Test
    fun `two language variants sharing an id both survive with distinct ids`() {
        val swak = parseMangayomiIndex(fixture("anime_index_swakshan.json"), "Swakshan")
        val animeonsen = swak.listings.filter { it.name == "Animeonsen" }
        assertEquals(2, animeonsen.size)
        assertEquals(setOf("en", "ja"), animeonsen.map { it.lang }.toSet())
        assertEquals("both variants need their own id", 2, animeonsen.map { it.id }.toSet().size)
    }

    // The invariant the rest of the app depends on, asserted over every curated repo at once:
    // nothing downstream can tell two listings apart by anything but the id.
    @Test
    fun `every id in a parsed index is unique`() {
        listOf("anime_index_m2k3a.json", "anime_index_mallyd11.json", "anime_index_swakshan.json")
            .forEach { name ->
                val listings = parseMangayomiIndex(fixture(name)).listings
                assertEquals(
                    "duplicate ids in $name would crash the list and collide on install",
                    listings.size,
                    listings.map { it.id }.toSet().size,
                )
            }
    }

    @Test
    fun `every listing carries the repo it came from`() {
        val listings = parseMangayomiIndex(fixture("anime_index_mallyd11.json"), "Mallyd11").listings
        assertTrue(listings.isNotEmpty())
        assertTrue(listings.all { it.repoName == "Mallyd11" })
    }

    // Named extensions the user would look for, so a repo quietly dropping them is visible here.
    @Test
    fun `the streaming extensions people actually look for are present`() {
        val all = listOf("anime_index_m2k3a.json", "anime_index_mallyd11.json", "anime_index_swakshan.json")
            .flatMap { parseMangayomiIndex(fixture(it)).listings }
        val names = all.map { it.name }.toSet()
        listOf("AllAnime", "HiAnime", "Miruro", "SubsPlease", "KickAssAnime").forEach { expected ->
            assertTrue("expected $expected in the curated repos", expected in names)
        }
    }

    // Every curated URL is https, because RemoteCodeUrl.require will refuse anything else — these
    // indexes supply the sourceCodeUrl for every extension installed from them.
    @Test
    fun `every curated repo url is https and points at an anime index`() {
        assertTrue(RecommendedExtensionRepos.repos.isNotEmpty())
        RecommendedExtensionRepos.repos.forEach { repo ->
            assertTrue("${repo.name} must be https", repo.indexUrl.startsWith("https://"))
            assertTrue("${repo.name} must be an anime index", repo.indexUrl.endsWith("anime_index.json"))
            assertTrue("${repo.name} needs a description", repo.description.isNotBlank())
        }
        // Distinct, so the merge is not deduping a copy of itself.
        assertEquals(
            RecommendedExtensionRepos.repos.size,
            RecommendedExtensionRepos.repos.map { it.indexUrl }.toSet().size,
        )
    }

    // Guards the install path end to end as far as a JVM can see it: every listing has somewhere to
    // download from, and it is a URL RemoteCodeUrl will accept.
    @Test
    fun `every parsed listing has an https source url and a name`() {
        val all = listOf("anime_index_m2k3a.json", "anime_index_mallyd11.json", "anime_index_swakshan.json")
            .flatMap { parseMangayomiIndex(fixture(it)).listings }
        all.forEach { listing ->
            assertTrue(listing.name.isNotBlank())
            assertNotNull(listing.sourceCodeUrl)
            assertTrue(
                "${listing.name} has a non-https source url: ${listing.sourceCodeUrl}",
                listing.sourceCodeUrl.startsWith("https://"),
            )
        }
    }

    // The invariant the screen actually depends on, asserted the way the screen gets its data.
    //
    // The per-index test above is necessary and is not sufficient: MangayomiRepoClient merges all
    // three curated repos plus the user's own into one list, and the browse list keys on the id
    // alone. This does not fail against the merge it replaced — the three repos as published today
    // happen not to collide across each other — so it is a standing guard rather than a
    // reproduction. MangayomiIndexTest holds the case that does reproduce it.
    @Test
    fun `every id in the merged directory is unique`() {
        // Repo names, not filenames — the other tests in this file pass these and the merged rows
        // carry repoName through to the UI, so labelling them after the fixture file would be a
        // small lie sitting in the one test that reads most like production.
        val fixtures = listOf(
            "anime_index_m2k3a.json" to "m2k3a",
            "anime_index_mallyd11.json" to "Mallyd11",
            "anime_index_swakshan.json" to "Swakshan",
        )
        val parsed = fixtures.flatMap { (file, repo) -> parseMangayomiIndex(fixture(file), repo).listings }
        val merged = withUniqueIds(parsed)

        val duplicates = merged.groupBy { it.id }.filterValues { it.size > 1 }
        assertTrue(
            "duplicate ids in the merged directory would crash the browse list: " +
                duplicates.mapValues { entry -> entry.value.map { it.name to it.lang } },
            duplicates.isEmpty(),
        )
        // And the merge must not be buying that by throwing sources away: every (name, lang) pair
        // that went in still comes out.
        assertEquals(
            parsed.map { it.name to it.lang }.toSet(),
            merged.map { it.name to it.lang }.toSet(),
        )
    }
}
