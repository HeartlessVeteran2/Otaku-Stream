package com.otakustream.core.sources.stremio

import com.otakustream.core.sources.stremio.model.OfficialAddonListing
import com.otakustream.core.sources.stremio.model.parseOfficialAddonCollection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

// Lets users discover addons by browsing Stremio's own official collection instead of only
// pasting manifest URLs by hand. Deliberately scoped to the official, Stremio-maintained list —
// third-party community lists have no fixed schema guarantee (see issue #10).
class StremioAddonDirectoryClient @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    suspend fun fetchOfficialAddons(): List<OfficialAddonListing> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(OFFICIAL_ADDON_COLLECTION_URL).build()
        val content = httpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Failed to fetch addon catalog: HTTP ${response.code}" }
            response.body?.string() ?: error("Empty response body")
        }
        parseOfficialAddonCollection(content)
    }

    private companion object {
        const val OFFICIAL_ADDON_COLLECTION_URL = "https://raw.githubusercontent.com/Stremio/stremio-official-addons/master/index.json"
    }
}
