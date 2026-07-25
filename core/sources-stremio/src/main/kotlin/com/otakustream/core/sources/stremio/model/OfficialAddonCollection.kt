package com.otakustream.core.sources.stremio.model

import com.otakustream.core.common.stringOrEmpty
import com.otakustream.core.common.stringOrNull
import org.json.JSONArray
import org.json.JSONObject

// Which list an add-on was found in. Surfaced in the browse UI because the three differ in how
// vetted they are, and a user picking something to install deserves to know which they're looking at.
enum class AddonListOrigin(val label: String) {
    OFFICIAL("Official"),
    COMMUNITY("Community"),
    CUSTOM("Custom list"),
}

data class OfficialAddonListing(
    val name: String,
    val description: String?,
    val logoUrl: String?,
    val transportUrl: String,
    val types: List<String>,
    val origin: AddonListOrigin = AddonListOrigin.OFFICIAL,
)

// Parses an add-on collection: an array of { manifest: {...}, transportUrl, flags: {…} }.
// transportUrl is directly the addon's manifest URL, consumable as-is by
// StremioAddonInstaller.installFromUrl.
//
// This shape is the de-facto standard — Stremio's official index.json, its own community collection
// endpoint, and a user-supplied list (issue #10) all use it, so one parser serves all three and the
// caller stamps the origin. Entries missing a manifest, a name, or a transportUrl are skipped rather
// than half-parsed, which is also what makes a wrong URL fail visibly instead of yielding junk.
fun parseOfficialAddonCollection(json: String): List<OfficialAddonListing> {
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
        )
    }
}
