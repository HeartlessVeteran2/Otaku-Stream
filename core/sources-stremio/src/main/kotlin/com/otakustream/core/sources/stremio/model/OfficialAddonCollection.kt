package com.otakustream.core.sources.stremio.model

import com.otakustream.core.common.stringOrEmpty
import com.otakustream.core.common.stringOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    // Harvested from stremio-addons.net, the community-maintained index where the add-ons Stremio's
    // own collection will not carry actually get listed. Bundled with the app rather than fetched:
    // that site is a server-rendered app with no JSON API, and its manifest URLs live one page per
    // add-on, so reading it live would mean scraping fifty pages of markup that can change any day.
    THIRD_PARTY,
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
    // Adult content. Hidden from the directory unless the user turns it on.
    val isAdult: Boolean = false,
    // The add-on's own configure page, when its manifest names one, rather than the /configure
    // convention derived below. Some adult and debrid add-ons host it off the manifest's own path.
    val declaredConfigureUrl: String? = null,
) {
    // Stremio add-ons are configured at /configure on the same host, by convention and by the
    // official SDK's own routing. Derived rather than stored because it is a property of the
    // transport URL, and one that cannot then disagree with it.
    //
    // Parsed rather than string-sliced. `removeSuffix("/manifest.json")` only matches when the URL
    // *ends* there, so a manifest carrying a query or fragment — which a configured add-on's URL
    // often does — fell straight through it and produced
    // "https://host/manifest.json?token=x/configure": a link to nothing, opened in the user's
    // browser at the exact moment they were trying to make the add-on work. The query and fragment
    // are dropped rather than carried, because they configure the *manifest* and mean nothing to
    // the page that generates them.
    //
    // stremio:// is the deep-link spelling of the same address and appears in hand-written lists.
    // A browser cannot open it, so it is mapped to https the same way installation already does.
    val configureUrl: String?
        get() {
            if (!isConfigurable && !configurationRequired) return null
            // What the add-on says about itself beats the convention. `behaviorHints.configureUrl`
            // is a real field — TPB 4K Porn is one that sets it — and an add-on that names its own
            // page is the authority on where that page is.
            //
            // Parsed rather than trusted, because this value is third-party data that ends up in an
            // ACTION_VIEW intent. toHttpUrlOrNull accepts only absolute http and https, so a
            // manifest offering `intent:`, `javascript:`, `file:` or plain junk falls through to
            // the derived URL instead of being handed to whatever app claims that scheme.
            declaredConfigureUrl?.trim()?.toHttpUrlOrNull()?.let { return it.toString() }
            val url = transportUrl.trim()
                .replaceFirst(STREMIO_SCHEME_REGEX, "https://")
                .toHttpUrlOrNull()
                ?: return null
            val builder = url.newBuilder().query(null).fragment(null)
            val segments = url.pathSegments
            // A trailing empty segment is what a URL ending in "/" parses to; drop those first so
            // the manifest.json check below is looking at the real last segment.
            var last = segments.lastIndex
            while (last >= 0 && segments[last].isEmpty()) {
                builder.removePathSegment(last)
                last--
            }
            if (last >= 0 && segments[last].equals("manifest.json", ignoreCase = true)) {
                builder.removePathSegment(last)
            }
            return builder.addPathSegment("configure").build().toString()
        }
}

private val STREMIO_SCHEME_REGEX = Regex("^stremio://", RegexOption.IGNORE_CASE)

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
            resources = manifest.resourceNames(),
            isConfigurable = hints?.optBoolean("configurable", false) == true,
            // Read from both places it is written. The protocol puts it in behaviorHints; enough
            // add-ons put it at the top level of the manifest instead that reading only one of them
            // would let a configure-first add-on install as though it were ready to use.
            //
            // `== true` rather than an elvis: on a nullable Boolean it means the same thing, and it
            // does not need the parentheses that `?: false ||` wants in order to be readable —
            // parentheses which, since elvis binds tighter than `||`, are not doing anything.
            configurationRequired = hints?.optBoolean("configurationRequired", false) == true ||
                manifest.optBoolean("configurationRequired", false),
            isAdult = isAdultManifest(hints, types),
            declaredConfigureUrl = hints?.stringOrNull("configureUrl"),
        )
    }
}

// Words that only appear as a content type on adult add-ons.
private val ADULT_TYPE_WORDS = setOf("porn", "hentai", "xxx", "adult", "nsfw", "erotic")

// Whether a listing is adult content.
//
// `behaviorHints.adult` is the protocol's own flag and is believed whenever it is set. It is not
// enough on its own: of four adult add-ons in the community directory, two set it and two set no
// behaviorHints at all, so trusting only the flag would show hard pornography to someone who had
// left the setting off. The types are the second reading — an add-on declaring a type of "Porn" or
// "hentai" has said what it is regardless of which fields it bothered to fill in.
//
// Deliberately errs toward marking things adult. The cost of a false positive is one add-on hidden
// behind a switch the user can flip; the cost of a false negative is the thing that switch exists
// to prevent.
internal fun isAdultManifest(hints: JSONObject?, types: List<String>): Boolean =
    hints?.optBoolean("adult", false) == true ||
        types.any { it.trim().lowercase() in ADULT_TYPE_WORDS }
