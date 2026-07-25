package com.otakustream.core.sources.stremio

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// Stremio resource names this registry routes. Stremio manifests declare these in `resources`.
const val STREMIO_RESOURCE_STREAM = "stream"
const val STREMIO_RESOURCE_SUBTITLES = "subtitles"

// An installed Stremio add-on that can answer a by-id request for content it doesn't necessarily
// have a catalog for — Torrentio for streams, OpenSubtitles for subtitles. Matched to a request by
// resource, content type, and id prefix. `name` is carried so merged results can say where they
// came from.
data class AddonProvider(
    val baseUrl: String,
    val name: String,
    val resources: Set<String>,
    val types: Set<String>,
    val idPrefixes: List<String>,
)

// Process-wide registry of installed add-ons that serve stream and/or subtitle resources.
// Catalog-based StremioVideoSources query it during getVideoList so results from add-ons with no
// browsable catalog of their own still reach playback — the piece that makes catalog-less add-ons
// actually work (issue #12). Keyed by base URL so re-registering the same add-on is idempotent.
@Singleton
class StremioStreamProviderRegistry @Inject constructor() {
    private val providers = ConcurrentHashMap<String, AddonProvider>()

    fun register(provider: AddonProvider) {
        providers[provider.baseUrl] = provider
    }

    fun unregister(baseUrl: String) {
        providers.remove(baseUrl)
    }

    // Providers that can serve this resource for this content: the add-on must declare the
    // resource, the type must match (or it declares no types), and the id must carry one of its
    // declared prefixes (or it declares none). Empty type/prefix sets mean "anything" because
    // that's how Stremio manifests express an unrestricted add-on.
    fun providersFor(resource: String, type: String, id: String): List<AddonProvider> =
        providers.values.filter { provider ->
            resource in provider.resources &&
                (provider.types.isEmpty() || type in provider.types) &&
                (provider.idPrefixes.isEmpty() || provider.idPrefixes.any { id.startsWith(it) })
        }
}
