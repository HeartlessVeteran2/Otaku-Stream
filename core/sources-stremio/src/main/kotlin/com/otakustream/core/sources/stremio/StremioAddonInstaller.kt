package com.otakustream.core.sources.stremio

import com.otakustream.core.database.stremio.StremioAddonRecord
import com.otakustream.core.database.stremio.StremioRepository
import com.otakustream.core.network.await
import com.otakustream.core.sources.api.SourceHttpException
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
    private val torrentEngine: com.otakustream.core.torrent.TorrentEngine,
) {
    suspend fun installFromUrl(manifestUrl: String, priority: Int = 0): List<StremioVideoSource> = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeStremioManifestUrl(manifestUrl)
        val request = Request.Builder().url(normalizedUrl).build()
        // await(), not execute(): installing is a suspend call made from a ViewModel scope, and a
        // blocking execute() could not be interrupted when the user left the screen — the request
        // ran on regardless, holding a thread and a connection nothing was waiting for.
        val content = httpClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw SourceHttpException(response.code)
            response.body?.string() ?: error("Empty manifest body")
        }
        val sources = buildSources(normalizedUrl, content)
        val manifest = parseManifest(content)
        // KeepingUserSettings, because this is also the re-install path — the directory's "Install"
        // button on an add-on that is already there, and the retry after a failed manifest fetch.
        // A plain save carried this call's `priority` and the record's default `enabled = true`, so
        // re-adding an add-on you had switched off switched it back on, and re-adding one you had
        // moved to the bottom sent it back to the end of the queue.
        stremioRepository.saveAddonKeepingUserSettings(
            StremioAddonRecord(manifestUrl = normalizedUrl, manifestJson = content, name = manifest.name, priority = priority),
        )
        // Preserving `enabled` in the database is only half of it: the runtime has to agree.
        // Re-installing an add-on the user had switched off left it disabled in the row and still
        // registered its stream/subtitle providers and handed back its catalog sources for the
        // caller to register — so it was off in the list and contributing to playback anyway, which
        // is worse than the bug it half-fixed. An add-on that is off registers nothing and returns
        // nothing to register.
        val enabled = stremioRepository.isAddonEnabled(normalizedUrl) ?: true
        if (!enabled) {
            // Unregister rather than merely skip registering. "An add-on that is off registers
            // nothing" has to be enforced, not assumed: returning early left whatever was already
            // in the registry under this base URL still serving streams, so a re-install of a
            // switched-off add-on could leave it contributing to playback — the exact state this
            // branch exists to prevent. Removing a key that isn't there is a no-op.
            unregisterProvider(normalizedUrl)
            return@withContext emptyList()
        }
        registerProviderIfAny(normalizedUrl, content)
        sources
    }

    suspend fun uninstall(manifestUrl: String) {
        streamProviderRegistry.unregister(baseUrlOf(manifestUrl))
        stremioRepository.deleteAddon(manifestUrl)
    }

    // Register an add-on that declares the "stream" and/or "subtitles" resource, so its results are
    // merged into playback even when it has no browsable catalog — Torrentio for streams,
    // OpenSubtitles for subtitles. Safe to call for any add-on: a no-op when it declares neither.
    // Idempotent (keyed by base URL).
    fun registerProviderIfAny(manifestUrl: String, manifestJson: String) {
        val manifest = parseManifest(manifestJson)
        val resources = manifest.resources.toSet()
        val routable = resources.intersect(setOf(STREMIO_RESOURCE_STREAM, STREMIO_RESOURCE_SUBTITLES))
        if (routable.isEmpty()) {
            // Also the un-register path, because this is called on re-install with a freshly
            // fetched manifest. An add-on that used to declare "stream" and no longer does was
            // left registered by a bare `return`, so the app kept asking it for streams it had
            // stopped serving — and kept waiting on the timeout for each one.
            unregisterProvider(manifestUrl)
            return
        }
        streamProviderRegistry.register(
            AddonProvider(
                baseUrl = baseUrlOf(manifestUrl),
                name = manifest.name,
                resources = resources,
                types = manifest.types.toSet(),
                idPrefixes = manifest.idPrefixes,
            ),
        )
    }

    fun unregisterProvider(manifestUrl: String) = streamProviderRegistry.unregister(baseUrlOf(manifestUrl))

    private fun baseUrlOf(manifestUrl: String): String = manifestUrl.removeSuffix("/manifest.json")

    // Stream/subtitle-only add-ons (Torrentio, OpenSubtitles) commonly declare zero catalogs, so
    // they register no browsable VideoSource — nothing to browse. They still contribute to playback:
    // registerProviderIfAny above puts them in the registry, and StremioVideoSource.getVideoList
    // queries every matching provider for streams and subtitles.
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
                onDeviceTorrentsAvailable = { torrentEngine.isUsable },
                manifestUrl = manifestUrl,
                catalog = catalog,
                resources = resources,
                id = stableSourceId(manifestUrl, catalog.type, catalog.id),
                name = "${manifest.name} — ${catalog.name}",
            )
        }
    }
}
