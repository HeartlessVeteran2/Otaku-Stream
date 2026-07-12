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
    suspend fun installFromUrl(manifestUrl: String): List<StremioVideoSource> = withContext(Dispatchers.IO) {
        val normalizedUrl = manifestUrl.trim()
            .replaceFirst("stremio://", "https://")
            .let { if (it.endsWith("manifest.json")) it else "${it.trimEnd('/')}/manifest.json" }
        val request = Request.Builder().url(normalizedUrl).build()
        val content = httpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Failed to download manifest: HTTP ${response.code}" }
            response.body?.string() ?: error("Empty manifest body")
        }
        val sources = buildSources(normalizedUrl, content)
        val manifest = parseManifest(content)
        stremioRepository.saveAddon(StremioAddonRecord(manifestUrl = normalizedUrl, manifestJson = content, name = manifest.name))
        sources
    }

    suspend fun uninstall(manifestUrl: String) = stremioRepository.deleteAddon(manifestUrl)

    fun buildSources(manifestUrl: String, manifestJson: String): List<StremioVideoSource> {
        val manifest = parseManifest(manifestJson)
        require(manifest.catalogs.isNotEmpty()) { "Addon declares no catalogs" }
        return manifest.catalogs.map { catalog ->
            StremioVideoSource(
                httpClient = httpClient,
                stremioRepository = stremioRepository,
                manifestUrl = manifestUrl,
                catalog = catalog,
                id = stableSourceId(manifestUrl, catalog.type, catalog.id),
                name = "${manifest.name} — ${catalog.name}",
            )
        }
    }
}
