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

class StremioAddonInstaller @Inject constructor(
    private val httpClient: OkHttpClient,
    private val stremioRepository: StremioRepository,
) {
    suspend fun installFromUrl(manifestUrl: String, priority: Int = 0): List<StremioVideoSource> = withContext(Dispatchers.IO) {
        val normalizedUrl = manifestUrl.trim().let { raw ->
            val withScheme = when {
                raw.startsWith("stremio://", ignoreCase = true) -> raw.replaceFirst("stremio://", "https://")
                raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> raw
                else -> "https://$raw"
            }
            if (withScheme.endsWith("manifest.json")) withScheme else "${withScheme.trimEnd('/')}/manifest.json"
        }
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
        sources
    }

    suspend fun uninstall(manifestUrl: String) = stremioRepository.deleteAddon(manifestUrl)

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
                manifestUrl = manifestUrl,
                catalog = catalog,
                resources = resources,
                id = stableSourceId(manifestUrl, catalog.type, catalog.id),
                name = "${manifest.name} — ${catalog.name}",
            )
        }
    }
}
