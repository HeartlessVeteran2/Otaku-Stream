package com.otakustream.core.sources.stremio

import com.otakustream.core.sources.stremio.model.AddonListOrigin
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

// The result of loading the directory. `listings` is whatever loaded; `customListError` reports a
// user-supplied list that failed, separately, so a bad custom URL is visible without pretending the
// whole directory is broken.
data class AddonDirectory(
    val listings: List<OfficialAddonListing>,
    val customListError: String? = null,
)

// Lets users browse add-ons and one-tap install them, like the real Stremio app. Fetches Stremio's
// small official curated list plus Stremio's own community collection (the exact list the official
// app shows as "Community Add-ons"), and optionally a list the user supplied themselves (issue #10).
// All three are plain GETs of the same `[{ manifest, transportUrl, ... }]` shape, so
// parseOfficialAddonCollection consumes all of them and each result is stamped with where it came from.
class StremioAddonDirectoryClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val directorySettings: StremioDirectorySettings,
) {
    suspend fun fetchAddonCatalog(): AddonDirectory = coroutineScope {
        val customUrl = directorySettings.get()
        val official = async { fetchListing(OFFICIAL_ADDON_COLLECTION_URL, AddonListOrigin.OFFICIAL) }
        val community = async { fetchListing(COMMUNITY_ADDON_COLLECTION_URL, AddonListOrigin.COMMUNITY) }
        // Fetched as a Result rather than null-on-failure: unlike the two built-in collections, a
        // custom URL was typed by a person who needs to be told when it's wrong.
        val custom = customUrl?.let { url ->
            async { runCatching { fetchListingOrThrow(url, AddonListOrigin.CUSTOM) } }
        }

        val builtIn = awaitAll(official, community)
        val customResult = custom?.await()
        // Both built-in endpoints down → surface the failure; otherwise show whatever loaded.
        if (builtIn.all { it == null } && customResult?.getOrNull() == null) {
            error("Failed to load the add-on catalog")
        }

        // Official first (curated base add-ons like Cinemeta lead the list), then community, then the
        // user's own — deduped by normalized manifest URL, so an add-on appearing in more than one
        // list shows once, keeping the most-vetted origin it was found under.
        val merged = (builtIn.filterNotNull().flatten() + customResult?.getOrNull().orEmpty())
            .distinctBy { normalizeStremioManifestUrl(it.transportUrl) }

        AddonDirectory(
            listings = merged,
            customListError = customResult?.exceptionOrNull()?.let { failure ->
                "Couldn't load your custom list: ${failure.message ?: "unknown error"}"
            },
        )
    }

    // Returns null on failure so one unreachable endpoint doesn't blank the whole catalog.
    private suspend fun fetchListing(url: String, origin: AddonListOrigin): List<OfficialAddonListing>? =
        try {
            fetchListingOrThrow(url, origin)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private suspend fun fetchListingOrThrow(
        url: String,
        origin: AddonListOrigin,
    ): List<OfficialAddonListing> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        val content = httpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "HTTP ${response.code}" }
            response.body?.string() ?: error("Empty response body")
        }
        val parsed = parseOfficialAddonCollection(content)
        // A URL that returns 200 but isn't an add-on list (an HTML page, say) parses to nothing.
        // Treat that as an error for a custom list rather than silently showing zero add-ons, since
        // "nothing appeared" is the least useful thing to tell someone who just typed a URL.
        if (parsed.isEmpty() && origin == AddonListOrigin.CUSTOM) {
            error("no add-ons found — is it a Stremio add-on collection?")
        }
        parsed.map { it.copy(origin = origin) }
    }

    private companion object {
        const val OFFICIAL_ADDON_COLLECTION_URL = "https://raw.githubusercontent.com/Stremio/stremio-official-addons/master/index.json"
        // Stremio's server-maintained community collection — the source its own app's
        // "Community Add-ons" list is populated from.
        const val COMMUNITY_ADDON_COLLECTION_URL = "https://api.strem.io/addonscollection.json"
    }
}
