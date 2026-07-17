package com.otakustream.core.sources.stremio.model

import org.json.JSONArray
import org.json.JSONObject

data class OfficialAddonListing(
    val name: String,
    val description: String?,
    val logoUrl: String?,
    val transportUrl: String,
    val types: List<String>,
)

// Mirrors StremioModels.kt's null-handling: JSONObject/JSONArray optString return the literal
// string "null" for JSON-null values, so every read here goes through these guards.
private fun JSONObject.stringOrEmpty(key: String): String = if (isNull(key)) "" else optString(key)
private fun JSONObject.stringOrNull(key: String): String? = stringOrEmpty(key).ifEmpty { null }
private fun JSONArray.stringOrNull(index: Int): String? = if (isNull(index)) null else optString(index).ifEmpty { null }

// Parses https://raw.githubusercontent.com/Stremio/stremio-official-addons/master/index.json —
// an array of { manifest: {...}, transportUrl, flags: { official, protected } }. transportUrl is
// directly the addon's manifest URL, consumable as-is by StremioAddonInstaller.installFromUrl.
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
