package com.otakustream.core.sources.stremio

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// A Stremio add-on that resolves streams for content by id (e.g. Torrentio) rather than
// providing a browsable catalog. Matched to a request by content type + id prefix.
data class StreamProvider(
    val baseUrl: String,
    val types: Set<String>,
    val idPrefixes: List<String>,
)

// Process-wide registry of installed stream-only (or stream-capable) add-ons. Catalog-based
// StremioVideoSources query it during getVideoList so streams from providers like Torrentio are
// merged in — the piece that makes catalog-less stream add-ons actually work (issue #12). Keyed
// by base URL so re-registering the same add-on is idempotent.
@Singleton
class StremioStreamProviderRegistry @Inject constructor() {
    private val providers = ConcurrentHashMap<String, StreamProvider>()

    fun register(provider: StreamProvider) {
        providers[provider.baseUrl] = provider
    }

    fun unregister(baseUrl: String) {
        providers.remove(baseUrl)
    }

    // Providers that can serve this content: type must match (or the provider declares no types),
    // and the id must carry one of the provider's declared prefixes (or it declares none).
    fun providersFor(type: String, id: String): List<StreamProvider> = providers.values.filter { provider ->
        (provider.types.isEmpty() || type in provider.types) &&
            (provider.idPrefixes.isEmpty() || provider.idPrefixes.any { id.startsWith(it) })
    }
}
