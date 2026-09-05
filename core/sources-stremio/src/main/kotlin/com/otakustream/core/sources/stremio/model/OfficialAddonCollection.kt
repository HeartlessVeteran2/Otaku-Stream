package com.otakustream.core.sources.stremio.model

import com.otakustream.core.common.stringOrEmpty
import com.otakustream.core.common.stringOrNull
import org.json.JSONArray
import org.json.JSONObject

// Which list an add-on was found in. The browse UI surfaces this because the three differ in how
// vetted they are, and someone about to install something deserves to know which they're looking at.
// Semantic only — the display label belongs to the UI, not to a model in core.
enum class AddonListOrigin {
    // Shipped with the app, and the only list here that is chosen rather than fetched.
    //
    // It exists because Stremio's own collection carries no stream providers at all — not one of
    // its ~95 community add-ons resolves a video. Those are installed by pasting a URL found
    // somewhere else, which is a thing you have to already know to do. This is that knowledge,
    // written down.
    RECOMMENDED,
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
    // What the add-on actually does: "stream", "catalog", "meta", "subtitles". Read off the manifest
    // and kept, where it used to be parsed and dropped.
    //
    // Without it the directory is 100 rows that all look alike, and roughly half of Stremio's
    // community list is subtitle add-ons — so someone looking for something that plays anime has to
    // install things one at a time to find out what they are.
    val resources: List<String> = emptyList(),
    // The add-on offers a configuration page. Usually optional, and for some (a debrid service, a
    // Jackett instance) it is the difference between working and returning nothing.
    val isConfigurable: Boolean = false,
    // The add-on does nothing at all until configured. Installing one blind produces a source that
    // is silently empty forever, which looks exactly like a broken app.
    val configurationRequired: Boolean = false,
) {
    // Stremio add-ons are configured at /configure on the same host, by convention and by the
    // official SDK's own routing. Derived rather than stored because it is a property of the
    // transport URL, and one that cannot then disagree with it.
    val configureUrl: String?
        get() = if (!isConfigurable && !configurationRequired) {
            null
        } else {
            transportUrl.removeSuffix("/manifest.json").trimEnd('/') + "/configure"
        }
}

// What an add-on contributes, for a directory that can be filtered down to the part you want.
enum class AddonKind { STREAMS, CATALOGS, SUBTITLES, OTHER }

// Streams first when an add-on does several things, because that is the one the user is looking for
// and the one that is scarce: an add-on that both catalogues and streams is a stream add-on for the
// purpose of finding something to play.
fun OfficialAddonListing.kind(): AddonKind = when {
    resources.any { it.equals("stream", ignoreCase = true) } -> AddonKind.STREAMS
    resources.any { it.equals("catalog", ignoreCase = true) || it.equals("meta", ignoreCase = true) } ->
        AddonKind.CATALOGS
    resources.any { it.equals("subtitles", ignoreCase = true) } -> AddonKind.SUBTITLES
    // Includes add-ons whose manifest lists no resources at all, which is what a
    // configuration-required add-on serves until it is configured.
    else -> AddonKind.OTHER
}

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
        val hints = manifest.optJSONObject("behaviorHints")
        OfficialAddonListing(
            name = name,
            description = manifest.stringOrNull("description"),
            logoUrl = manifest.stringOrNull("logo"),
            transportUrl = transportUrl,
            types = types,
            origin = origin,
            resources = manifest.parseResourceNames(),
            isConfigurable = hints?.optBoolean("configurable", false) ?: false,
            // Read from both places it is written. The protocol puts it in behaviorHints; enough
            // add-ons put it at the top level of the manifest instead that reading only one of them
            // would let a configure-first add-on install as though it were ready to use.
            configurationRequired = (hints?.optBoolean("configurationRequired", false) ?: false) ||
                manifest.optBoolean("configurationRequired", false),
        )
    }
}

// "resources" entries are either a plain string ("stream") or an object with a "name" field
// ({"name": "stream", "types": [...], "idPrefixes": [...]}) — both forms are current, and the
// add-ons that matter most here use the object form. parseManifest already handles this for the
// installed-add-on path; the directory needs the same reading to say what a listing does.
private fun JSONObject.parseResourceNames(): List<String> {
    val array = optJSONArray("resources") ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val obj = array.optJSONObject(index)
        if (obj != null) obj.stringOrEmpty("name").ifEmpty { null } else array.stringOrNull(index)
    }
}
