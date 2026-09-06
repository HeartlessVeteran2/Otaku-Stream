package com.otakustream.core.sources.stremio

import android.content.Context
import com.otakustream.core.sources.stremio.model.AddonListOrigin
import com.otakustream.core.sources.stremio.model.OfficialAddonListing
import com.otakustream.core.sources.stremio.model.parseAddonCollection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// The stremio-addons.net catalogue, shipped with the app.
//
// This is where the add-ons that actually resolve video get listed — the index Stremio's own
// collection is not. It has no JSON API: the site server-renders its pages, the index carries no
// manifest URLs at all, and each add-on's URL lives on its own detail page. Reading it live would
// mean fetching fifty pages of markup per refresh and parsing HTML that can change without notice,
// on a screen the user is waiting on. So it is harvested once, checked in, and reviewable in the
// diff — and it goes stale slowly and visibly rather than breaking silently.
//
// Stored in Stremio's own collection shape, so parseAddonCollection reads it with no second parser.
// The one thing the harvest corrects is `behaviorHints.adult`: two of the adult add-ons here
// declare no behaviorHints at all and a type of "movie", so nothing in their own manifests says
// what they are. Marking them in the data keeps that correction out of the code and next to the
// entry it applies to.
@Singleton
class BundledCommunityAddons @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // Parsed once and kept. It is ~18 KB of JSON that cannot change without a new build, so
    // re-reading and re-parsing it on every directory load would be work with no possible new
    // result.
    @Volatile
    private var cached: List<OfficialAddonListing>? = null

    // Best-effort: a directory missing this tier is worse than one that fails to open, so a
    // corrupt or missing resource yields nothing rather than propagating.
    suspend fun listings(): List<OfficialAddonListing> = withContext(Dispatchers.IO) {
        cached ?: runCatching {
            val json = context.resources.openRawResource(R.raw.community_addons)
                .bufferedReader()
                .use { it.readText() }
            parseAddonCollection(json, AddonListOrigin.THIRD_PARTY)
        }.getOrDefault(emptyList()).also { cached = it }
    }
}
