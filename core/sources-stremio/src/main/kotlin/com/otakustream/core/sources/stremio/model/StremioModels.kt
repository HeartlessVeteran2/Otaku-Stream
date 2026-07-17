package com.otakustream.core.sources.stremio.model

import org.json.JSONArray
import org.json.JSONObject

data class StremioExtra(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String>? = null,
    val optionsLimit: Int? = null,
)

data class StremioCatalog(val type: String, val id: String, val name: String, val extras: List<StremioExtra>) {
    val extraNames: Set<String> get() = extras.map { it.name }.toSet()
}

data class StremioManifest(
    val id: String,
    val name: String,
    val description: String?,
    val resources: List<String>,
    val catalogs: List<StremioCatalog>,
)

data class StremioMetaPreview(val id: String, val type: String, val name: String, val poster: String?)

data class StremioCatalogResponse(val metas: List<StremioMetaPreview>)

data class StremioVideoEntry(val id: String, val title: String, val season: Int?, val episode: Int?, val released: String?)

data class StremioMeta(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val description: String?,
    val genres: List<String>,
    val videos: List<StremioVideoEntry>,
    val background: String?,
    val logo: String?,
    val imdbRating: String?,
    val runtime: String?,
    val cast: List<String>,
    val director: List<String>,
    // Stremio's protocol carries trailers as {source, type} where "source" is a YouTube video
    // id, not a playable URL — kept as the raw id so callers decide how to build a link/player.
    val trailerYoutubeId: String?,
)

data class StremioStream(val url: String?, val infoHash: String?, val fileIdx: Int?, val name: String?, val description: String?)

data class StremioStreamResponse(val streams: List<StremioStream>)

data class StremioSubtitle(val id: String, val url: String, val lang: String)

data class StremioSubtitleResponse(val subtitles: List<StremioSubtitle>)

// Android's JSONObject/JSONArray optString return the literal string "null" for JSON-null
// values — every array/object string read in this file goes through one of these two helpers
// so a JSON null never turns into the four-character string "null".
private fun JSONObject.stringOrEmpty(key: String): String = if (isNull(key)) "" else optString(key)
private fun JSONObject.stringOrNull(key: String): String? = stringOrEmpty(key).ifEmpty { null }
private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
private fun JSONObject.stringListOrEmpty(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.stringOrNull(it) }
}
private fun JSONArray.stringOrNull(index: Int): String? = if (isNull(index)) null else optString(index).ifEmpty { null }

fun parseManifest(json: String): StremioManifest {
    val root = JSONObject(json)
    val resourcesArray = root.optJSONArray("resources") ?: JSONArray()
    // "resources" entries can be either a plain string ("catalog") or an object with a "name"
    // field ({"name": "catalog", ...}) depending on the addon.
    val resources = (0 until resourcesArray.length()).mapNotNull { index ->
        val obj = resourcesArray.optJSONObject(index)
        if (obj != null) obj.stringOrEmpty("name").ifEmpty { null } else resourcesArray.stringOrNull(index)
    }
    val catalogsArray = root.optJSONArray("catalogs") ?: JSONArray()
    val catalogs = (0 until catalogsArray.length()).map { index ->
        val entry = catalogsArray.getJSONObject(index)
        val extraArray = entry.optJSONArray("extra") ?: JSONArray()
        // "extra" is normally an array of {name, isRequired, options, optionsLimit} objects, but
        // some addons use the backward-compatible plain-string-array form (e.g. ["search"]).
        val extras = (0 until extraArray.length()).mapNotNull { extraIndex ->
            val obj = extraArray.optJSONObject(extraIndex)
            if (obj != null) {
                val name = obj.stringOrEmpty("name").ifEmpty { return@mapNotNull null }
                StremioExtra(
                    name = name,
                    isRequired = obj.optBoolean("isRequired", false),
                    options = obj.optJSONArray("options")?.let { options -> (0 until options.length()).mapNotNull { options.stringOrNull(it) } },
                    optionsLimit = obj.intOrNull("optionsLimit"),
                )
            } else {
                extraArray.stringOrNull(extraIndex)?.let { StremioExtra(name = it) }
            }
        }
        StremioCatalog(
            type = entry.getString("type"),
            id = entry.getString("id"),
            name = entry.stringOrEmpty("name").ifEmpty { entry.getString("id") },
            extras = extras,
        )
    }
    return StremioManifest(
        id = root.stringOrEmpty("id"),
        name = root.stringOrEmpty("name").ifEmpty { root.stringOrEmpty("id") },
        description = root.stringOrNull("description"),
        resources = resources,
        catalogs = catalogs,
    )
}

fun parseCatalogResponse(json: String): StremioCatalogResponse {
    val root = JSONObject(json)
    val metasArray = root.optJSONArray("metas") ?: JSONArray()
    val metas = (0 until metasArray.length()).map { index ->
        val entry = metasArray.getJSONObject(index)
        StremioMetaPreview(
            id = entry.getString("id"),
            type = entry.stringOrEmpty("type"),
            name = entry.stringOrEmpty("name"),
            poster = entry.stringOrNull("poster"),
        )
    }
    return StremioCatalogResponse(metas = metas)
}

fun parseMetaResponse(json: String): StremioMeta {
    val root = JSONObject(json)
    val meta = root.optJSONObject("meta") ?: JSONObject()
    val genresArray = meta.optJSONArray("genres") ?: JSONArray()
    val genres = (0 until genresArray.length()).map { genresArray.getString(it) }
    val videosArray = meta.optJSONArray("videos") ?: JSONArray()
    val videos = (0 until videosArray.length()).map { index ->
        val entry = videosArray.getJSONObject(index)
        StremioVideoEntry(
            id = entry.getString("id"),
            title = entry.stringOrEmpty("title"),
            season = entry.intOrNull("season"),
            episode = entry.intOrNull("episode"),
            released = entry.stringOrNull("released"),
        )
    }
    val trailersArray = meta.optJSONArray("trailers") ?: JSONArray()
    val trailerYoutubeId = if (trailersArray.length() > 0) trailersArray.optJSONObject(0)?.stringOrNull("source") else null
    return StremioMeta(
        id = meta.stringOrEmpty("id"),
        type = meta.stringOrEmpty("type"),
        name = meta.stringOrEmpty("name"),
        poster = meta.stringOrNull("poster"),
        description = meta.stringOrNull("description"),
        genres = genres,
        videos = videos,
        background = meta.stringOrNull("background"),
        logo = meta.stringOrNull("logo"),
        imdbRating = meta.stringOrNull("imdbRating"),
        runtime = meta.stringOrNull("runtime"),
        cast = meta.stringListOrEmpty("cast"),
        director = meta.stringListOrEmpty("director"),
        trailerYoutubeId = trailerYoutubeId,
    )
}

fun parseStreamResponse(json: String): StremioStreamResponse {
    val root = JSONObject(json)
    val streamsArray = root.optJSONArray("streams") ?: JSONArray()
    val streams = (0 until streamsArray.length()).map { index ->
        val entry = streamsArray.getJSONObject(index)
        StremioStream(
            url = entry.stringOrNull("url"),
            infoHash = entry.stringOrNull("infoHash"),
            fileIdx = entry.intOrNull("fileIdx"),
            name = entry.stringOrNull("name"),
            description = entry.stringOrNull("description"),
        )
    }
    return StremioStreamResponse(streams = streams)
}

fun parseSubtitlesResponse(json: String): StremioSubtitleResponse {
    val root = JSONObject(json)
    val subtitlesArray = root.optJSONArray("subtitles") ?: JSONArray()
    val subtitles = (0 until subtitlesArray.length()).mapNotNull { index ->
        val entry = subtitlesArray.optJSONObject(index) ?: return@mapNotNull null
        val url = entry.stringOrNull("url") ?: return@mapNotNull null
        StremioSubtitle(
            id = entry.stringOrEmpty("id").ifEmpty { url },
            url = url,
            lang = entry.stringOrEmpty("lang").ifEmpty { "unknown" },
        )
    }
    return StremioSubtitleResponse(subtitles = subtitles)
}
