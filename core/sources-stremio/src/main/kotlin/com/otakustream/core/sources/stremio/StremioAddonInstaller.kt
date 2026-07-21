package com.otakustream.core.sources.stremio

import com.otakustream.core.database.stremio.StremioAddonRecord
import com.otakustream.core.database.stremio.StremioRepository
import com.otakustream.core.sources.api.stableSourceId
import com.otakustream.core.sources.stremio.model.parseManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

// Canonicalizes a user-entered add-on URL to its manifest URL (adds a scheme, appends
// /manifest.json). Shared so "is this installed?" checks elsewhere match the saved record's URL.
fun normalizeStremioManifestUrl(manifestUrl: String): String = manifestUrl.trim().let { raw ->
    val withScheme = when {
        raw.startsWith("stremio://", ignoreCase = true) -> raw.replaceFirst("stremio://", "https://")
        raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> raw
        else -> "https://$raw"
    }
    if (withScheme.endsWith("manifest.json")) withScheme else "${withScheme.trimEnd('/')}/manifest.json"
}

class StremioAddonInstaller @Inject constructor(
    private val httpClient: OkHttpClient,
    private val stremioRepository: StremioRepository,
    private val streamProviderRegistry: StremioStreamProviderRegistry,
) {
    suspend fun installFromUrl(manifestUrl: String, priority: Int = 0): List<StremioVideoSource> = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeStremioManifestUrl(manifestUrl)
        val request = Request.Builder().url(normalizedUrl).build()
        val content = httpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Failed to download manifest: HTTP ${response.code}" }
            response.body?.string() ?: error("Empty manifest body")
        }
        val sources = buildSources(normalizedUrl, content)
        val manifest = parseManifest(content)
        stremioRepository.saveAddon(
            StremioAddonRecord(manifestUrl = normalizedUrl, manifestJson = content, name = manifest.name, priority = priority),
        )
        registerStreamProviderIfAny(normalizedUrl, content)
        sources
    }

    suspend fun uninstall(manifestUrl: String) {
        streamProviderRegistry.unregister(baseUrlOf(manifestUrl))
        stremioRepository.deleteAddon(manifestUrl)
    }

    // Register an add-on that declares the "stream" resource as a stream provider, so its streams
    // are merged into playback even when it has no browsable catalog (Torrentio et al.). Safe to
    // call for any add-on — a no-op when "stream" isn't declared. Idempotent (keyed by base URL).
    fun registerStreamProviderIfAny(manifestUrl: String, manifestJson: String) {
        val manifest = parseManifest(manifestJson)
        if ("stream" !in manifest.resources) return
        streamProviderRegistry.register(
            StreamProvider(
                baseUrl = baseUrlOf(manifestUrl),
                types = manifest.types.toSet(),
                idPrefixes = manifest.idPrefixes,
            ),
        )
    }

    fun unregisterStreamProvider(manifestUrl: String) = streamProviderRegistry.unregister(baseUrlOf(manifestUrl))

    private fun baseUrlOf(manifestUrl: String): String = manifestUrl.removeSuffix("/manifest.json")

    // Stream/subtitle-only addons (e.g. Torrentio, OpenSubtitles) commonly declare zero
    // catalogs — they're still installable, they just don't register a browsable VideoSource
    // (catalog-less stream/subtitle resolution is a separate, larger piece of work; see
    // https://github.com/HeartlessVeteran2/Otaku-Stream/issues/12).
    fun buildSources(
        manifestUrl: String,
        manifestJson: String,
        isCatalogEnabled: (type: String, id: String) -> Boolean = { _, _ -> true },
    ): List<StremioVideoSource> {
        val manifest = parseManifest(manifestJson)
        val resources = manifest.resources.toSet()
        return manifest.catalogs.filter { isCatalogEnabled(it.type, it.id) }.map { catalog ->
            StremioVideoSource(
                httpClient = httpClient,
                stremioRepository = stremioRepository,
                streamProviderRegistry = streamProviderRegistry,
                manifestUrl = manifestUrl,
                catalog = catalog,
                resources = resources,
                id = stableSourceId(manifestUrl, catalog.type, catalog.id),
                name = "${manifest.name} — ${catalog.name}",
            )
        }
    }
}
