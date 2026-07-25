package com.otakustream.core.sources.stremio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The registry is what makes catalog-less add-ons work (issue #12): a Torrentio or OpenSubtitles
// install has no catalog and therefore no VideoSource of its own, so it only ever gets asked for
// anything if providersFor routes a request to it. That routing is pure matching logic with no
// Android or network dependency, so it's tested directly here rather than inferred from behaviour.
class StremioProviderRegistryTest {

    private fun provider(
        baseUrl: String = "https://addon.example",
        name: String = "Addon",
        resources: Set<String> = setOf(STREMIO_RESOURCE_STREAM),
        types: Set<String> = emptySet(),
        idPrefixes: List<String> = emptyList(),
    ) = AddonProvider(baseUrl, name, resources, types, idPrefixes)

    @Test
    fun `routes by resource so a subtitle-only add-on is never asked for streams`() {
        val registry = StremioStreamProviderRegistry()
        registry.register(
            provider(
                baseUrl = "https://opensubtitles.example",
                name = "OpenSubtitles",
                resources = setOf(STREMIO_RESOURCE_SUBTITLES),
            ),
        )

        // The case the issue was actually about: before subtitles were routed, this add-on matched
        // nothing at all and sat installed doing nothing.
        assertEquals(
            listOf("https://opensubtitles.example"),
            registry.providersFor(STREMIO_RESOURCE_SUBTITLES, "series", "tt123:1:1").map { it.baseUrl },
        )
        assertTrue(
            "a subtitles-only add-on must not be queried for streams",
            registry.providersFor(STREMIO_RESOURCE_STREAM, "series", "tt123:1:1").isEmpty(),
        )
    }

    @Test
    fun `an all-in-one add-on matches both resources`() {
        val registry = StremioStreamProviderRegistry()
        registry.register(
            provider(resources = setOf(STREMIO_RESOURCE_STREAM, STREMIO_RESOURCE_SUBTITLES)),
        )

        assertEquals(1, registry.providersFor(STREMIO_RESOURCE_STREAM, "movie", "tt1").size)
        assertEquals(1, registry.providersFor(STREMIO_RESOURCE_SUBTITLES, "movie", "tt1").size)
    }

    @Test
    fun `declared types filter, and no declared types matches anything`() {
        val registry = StremioStreamProviderRegistry()
        registry.register(provider(baseUrl = "https://series-only.example", types = setOf("series")))
        registry.register(provider(baseUrl = "https://anything.example", types = emptySet()))

        assertEquals(
            setOf("https://series-only.example", "https://anything.example"),
            registry.providersFor(STREMIO_RESOURCE_STREAM, "series", "tt1").map { it.baseUrl }.toSet(),
        )
        // A movie request must skip the series-only add-on but still reach the unrestricted one:
        // an empty `types` in a Stremio manifest means "no restriction", not "nothing".
        assertEquals(
            listOf("https://anything.example"),
            registry.providersFor(STREMIO_RESOURCE_STREAM, "movie", "tt1").map { it.baseUrl },
        )
    }

    @Test
    fun `declared id prefixes filter, and no declared prefixes matches anything`() {
        val registry = StremioStreamProviderRegistry()
        registry.register(provider(baseUrl = "https://imdb.example", idPrefixes = listOf("tt")))
        registry.register(provider(baseUrl = "https://kitsu.example", idPrefixes = listOf("kitsu:")))
        registry.register(provider(baseUrl = "https://anyid.example", idPrefixes = emptyList()))

        assertEquals(
            setOf("https://imdb.example", "https://anyid.example"),
            registry.providersFor(STREMIO_RESOURCE_STREAM, "movie", "tt0111161").map { it.baseUrl }.toSet(),
        )
        assertEquals(
            setOf("https://kitsu.example", "https://anyid.example"),
            registry.providersFor(STREMIO_RESOURCE_STREAM, "series", "kitsu:42:1").map { it.baseUrl }.toSet(),
        )
        // An id matching no declared prefix still reaches the unrestricted add-on only.
        assertEquals(
            listOf("https://anyid.example"),
            registry.providersFor(STREMIO_RESOURCE_STREAM, "series", "mal:99").map { it.baseUrl },
        )
    }

    @Test
    fun `type and prefix restrictions both have to pass`() {
        val registry = StremioStreamProviderRegistry()
        registry.register(provider(types = setOf("series"), idPrefixes = listOf("tt")))

        assertEquals(1, registry.providersFor(STREMIO_RESOURCE_STREAM, "series", "tt1:1:1").size)
        assertTrue(registry.providersFor(STREMIO_RESOURCE_STREAM, "movie", "tt1").isEmpty())
        assertTrue(registry.providersFor(STREMIO_RESOURCE_STREAM, "series", "kitsu:1").isEmpty())
    }

    @Test
    fun `re-registering the same base url replaces instead of duplicating`() {
        val registry = StremioStreamProviderRegistry()
        registry.register(provider(name = "Before", types = setOf("movie")))
        // Reinstalling or re-enabling an add-on calls register again; cold-start rehydration calls it
        // for every persisted add-on. Keying by base URL is what keeps that idempotent.
        registry.register(provider(name = "After", types = setOf("movie")))

        val matches = registry.providersFor(STREMIO_RESOURCE_STREAM, "movie", "tt1")
        assertEquals(1, matches.size)
        assertEquals("After", matches.single().name)
    }

    @Test
    fun `unregister removes the provider`() {
        val registry = StremioStreamProviderRegistry()
        registry.register(provider(baseUrl = "https://gone.example"))
        registry.unregister("https://gone.example")

        // Disabling or uninstalling an add-on must stop it being queried immediately, not at the
        // next cold start.
        assertTrue(registry.providersFor(STREMIO_RESOURCE_STREAM, "movie", "tt1").isEmpty())
    }
}
