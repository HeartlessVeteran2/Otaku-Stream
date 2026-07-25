package com.otakustream.core.sources.stremio.model

import com.otakustream.core.common.stringOrEmpty
import com.otakustream.core.common.stringOrNull
import org.json.JSONArray
import org.json.JSONObject

// Which list an add-on was found in. The browse UI surfaces this because the three differ in how
// vetted they are, and someone about to install something deserves to know which they're looking at.
// Semantic only — the display label belongs to the UI, not to a model in core.
enum class AddonListOrigin {
    OFFICIAL,
    COMMUNITY,
    CUSTOM,
}

data class OfficialAddonListing(
    val name: String,
    val description: String?,
    val logoUrl: String?,
    val transportUrl: String,
    val types: List<String>,
    val origin: AddonListOrigin,
)

// Parses an add-on collection: an array of { manifest: {...}, transportUrl, flags: {…} }.
// transportUrl is directly the addon's manifest URL, consumable as-is by
// StremioAddonInstaller.installFromUrl.
//
// This shape is the de-facto standard — Stremio's official index.json, its own community collection
// endpoint, and a user-supplied list (issue #10) all use it, so one parser serves all three and the
// caller declares the origin. Entries missing a manifest, a name, or a transportUrl are skipped
// rather than half-parsed, which is also what makes a wrong URL fail visibly instead of yielding junk.
//
// `origin` is a required parameter rather than a defaulted field on the model: defaulting it would
// mean a forgotten argument silently labels a community or user-supplied add-on as vetted Official,
// which is the one direction this must never get wrong.
fun parseAddonCollection(json: String, origin: AddonListOrigin): List<OfficialAddonListing> {
    val array = JSONArray(json)
    return (0 until array.length()).mapNotNull { index ->
        val entry = array.optJSONObject(index) ?: return@mapNotNull null
        val manifest = entry.optJSONObject("manifest") ?: return@mapNotNull null
        val transportUrl = entry.stringOrNull("transportUrl") ?: return@mapNotNull null
        val name = manifest.stringOrEmpty("name").ifEmpty { return@mapNotNull null }
        val types = manifest.optJSONArray("types")?.let { typesArray ->
            (0 until typesArray.length()).mapNotNull { typesArray.stringOrNull(it) }
        }.orEmpty()
        OfficialAddonListing(
            name = name,
            description = manifest.stringOrNull("description"),
            logoUrl = manifest.stringOrNull("logo"),
            transportUrl = transportUrl,
            types = types,
            origin = origin,
        )
    }
}
