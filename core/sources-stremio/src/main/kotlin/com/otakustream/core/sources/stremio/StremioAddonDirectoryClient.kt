package com.otakustream.core.sources.stremio

import com.otakustream.core.sources.stremio.model.OfficialAddonListing
import com.otakustream.core.sources.stremio.model.parseOfficialAddonCollection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

// Lets users browse add-ons and one-tap install them, like the real Stremio app. Fetches two public
// collections and merges them: Stremio's small official curated list plus Stremio's own community
// collection (the exact list the official app shows as "Community Add-ons"). Both are plain GETs of
// the same `[{ manifest, transportUrl, ... }]` shape, so parseOfficialAddonCollection consumes both.
class StremioAddonDirectoryClient @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    suspend fun fetchAddonCatalog(): List<OfficialAddonListing> = coroutineScope {
        val official = async { fetchListing(OFFICIAL_ADDON_COLLECTION_URL) }
        val community = async { fetchListing(COMMUNITY_ADDON_COLLECTION_URL) }
        val results = awaitAll(official, community)
        // Both endpoints down → surface the failure; otherwise show whatever loaded.
        if (results.all { it == null }) error("Failed to load the add-on catalog")
        // Official first (curated base add-ons like Cinemeta lead the list), then community, deduped
        // by normalized manifest URL so an add-on in both collections appears once.
        results.filterNotNull().flatten().distinctBy { normalizeStremioManifestUrl(it.transportUrl) }
    }

    // Returns null on failure so one unreachable endpoint doesn't blank the whole catalog.
    private suspend fun fetchListing(url: String): List<OfficialAddonListing>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            val content = httpClient.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "HTTP ${response.code}" }
                response.body?.string() ?: error("Empty response body")
            }
            parseOfficialAddonCollection(content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val OFFICIAL_ADDON_COLLECTION_URL = "https://raw.githubusercontent.com/Stremio/stremio-official-addons/master/index.json"
        // Stremio's server-maintained community collection — the source its own app's
        // "Community Add-ons" list is populated from.
        const val COMMUNITY_ADDON_COLLECTION_URL = "https://api.strem.io/addonscollection.json"
    }
}
