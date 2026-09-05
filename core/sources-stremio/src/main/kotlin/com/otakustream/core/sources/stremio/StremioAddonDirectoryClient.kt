package com.otakustream.core.sources.stremio

import com.otakustream.core.sources.api.SourceHttpException
import com.otakustream.core.sources.stremio.model.AddonListOrigin
import com.otakustream.core.sources.stremio.model.OfficialAddonListing
import com.otakustream.core.sources.stremio.model.parseAddonCollection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
// The result of loading the directory. `listings` is whatever loaded; `customListError` reports a
// user-supplied list that failed, separately, so a bad custom URL is visible without pretending the
// whole directory is broken.
data class AddonDirectory(
    val listings: List<OfficialAddonListing>,
    val customListError: String? = null,
    // Stremio's own endpoints failed. Reported rather than thrown, because the recommended list is
    // local and still worth showing — see fetchAddonCatalog.
    val builtInListError: String? = null,
)

// Lets users browse add-ons and one-tap install them, like the real Stremio app. Fetches Stremio's
// small official curated list plus Stremio's own community collection (the exact list the official
// app shows as "Community Add-ons"), and optionally a list the user supplied themselves (issue #10).
// All three are plain GETs of the same `[{ manifest, transportUrl, ... }]` shape, so
// parseAddonCollection consumes all of them, taking the origin so results carry where they came from.
class StremioAddonDirectoryClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val directorySettings: StremioDirectorySettings,
) {
    suspend fun fetchAddonCatalog(): AddonDirectory = coroutineScope {
        val customUrl = directorySettings.get()
        val official = async { fetchListing(OFFICIAL_ADDON_COLLECTION_URL, AddonListOrigin.OFFICIAL) }
        val community = async { fetchListing(COMMUNITY_ADDON_COLLECTION_URL, AddonListOrigin.COMMUNITY) }
        // Fetched as a Result rather than null-on-failure: unlike the two built-in collections, a
        // custom URL was typed by a person who needs to be told when it's wrong. Cancellation is
        // rethrown rather than reported — a cancelled load is not a broken list.
        val custom = customUrl?.let { url ->
            async {
                try {
                    Result.success(fetchListingOrThrow(url, AddonListOrigin.CUSTOM))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }

        val builtIn = awaitAll(official, community)
        val customResult = custom?.await()
        // Both built-in endpoints down is reported, not thrown.
        //
        // Throwing blanked the whole screen — which was right when everything on it came off the
        // network, and is wrong now that the recommended add-ons are local and need no network at
        // all. Being offline is exactly when someone is most likely to be here fixing their
        // sources, and hiding the one list that still works behind a full-screen error would be a
        // strange way to help.
        val builtInListError = "Couldn't reach Stremio's add-on lists. Showing the recommended ones."
            .takeIf { builtIn.all { listing -> listing == null } }

        // Recommended first, then official (Cinemeta and friends), then community, then the user's
        // own — deduped by normalized manifest URL, so an add-on appearing in more than one list
        // shows once, keeping the most-vetted origin it was found under.
        //
        // Recommended leads because it is the only part of this screen that answers "what do I
        // install to watch something". Everything below it is worth having and none of it resolves
        // a stream.
        val merged = (
            RecommendedAddons.listings +
                builtIn.filterNotNull().flatten() +
                customResult?.getOrNull().orEmpty()
            )
            .filterNot { it.origin in FETCHED_ORIGINS && isUnreachableOnDevice(it.transportUrl) }
            .distinctBy { normalizeStremioManifestUrl(it.transportUrl) }

        AddonDirectory(
            listings = merged,
            customListError = customResult?.exceptionOrNull()?.let { failure ->
                "Couldn't load your custom list: ${failure.message ?: "unknown error"}"
            },
            builtInListError = builtInListError,
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
        val call = httpClient.newCall(request)
        // Cancel the blocking OkHttp call when the coroutine is cancelled (navigating away, or a
        // newer load superseding this one) instead of leaving it to occupy a thread until it
        // finishes on its own. Same pattern as SourceCatalogClient.
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        val content = try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw SourceHttpException(response.code)
                response.body?.string() ?: error("Empty response body")
            }
        } finally {
            cancellation?.dispose()
        }
        val parsed = parseAddonCollection(content, origin)
        // A URL that returns 200 but isn't an add-on list (an HTML page, say) parses to nothing.
        // Treat that as an error for a custom list rather than silently showing zero add-ons, since
        // "nothing appeared" is the least useful thing to tell someone who just typed a URL.
        if (parsed.isEmpty() && origin == AddonListOrigin.CUSTOM) {
            error("no add-ons found — is it a Stremio add-on collection?")
        }
        parsed
    }

    // Drops listings that point at the machine the app is running on.
    //
    // Stremio's official list includes "Local Files", served by the Stremio *desktop* streaming
    // server at 127.0.0.1:11470. On a phone there is nothing at that address and there never will
    // be, so installing it produces a source that fails every request — and a directory whose very
    // first screen contains something guaranteed to break teaches the user not to trust the rest.
    //
    // Applied only to the two lists the app fetches on the user's behalf. A recommended entry is
    // exempt so this can never quietly delete a curated one — nothing in that list is a loopback
    // URL, and if one ever is, that is a bug to see rather than to hide. A *custom* entry is exempt
    // because the user typed the URL of that list themselves: someone pointing the app at their own
    // collection, which may well serve an add-on running on this very device, has said what they
    // want more clearly than this heuristic can second-guess.
    private fun isUnreachableOnDevice(transportUrl: String): Boolean {
        val host = transportUrl.toHttpUrlOrNull()?.host ?: return false
        return host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "0.0.0.0"
    }

    private companion object {
        // The origins whose entries the app chose to fetch, as opposed to the ones it ships or the
        // user supplied.
        val FETCHED_ORIGINS = setOf(AddonListOrigin.OFFICIAL, AddonListOrigin.COMMUNITY)

        const val OFFICIAL_ADDON_COLLECTION_URL = "https://raw.githubusercontent.com/Stremio/stremio-official-addons/master/index.json"
        // Stremio's server-maintained community collection — the source its own app's
        // "Community Add-ons" list is populated from.
        const val COMMUNITY_ADDON_COLLECTION_URL = "https://api.strem.io/addonscollection.json"
    }
}
