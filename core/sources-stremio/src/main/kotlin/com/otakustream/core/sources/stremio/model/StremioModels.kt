package com.otakustream.core.sources.stremio.model

import org.json.JSONArray
import org.json.JSONObject

data class StremioCatalog(val type: String, val id: String, val name: String, val extraNames: Set<String>)

data class StremioManifest(
    val id: String,
    val name: String,
    val description: String?,
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
)

data class StremioStream(val url: String?, val infoHash: String?, val fileIdx: Int?, val name: String?, val description: String?)

data class StremioStreamResponse(val streams: List<StremioStream>)

// Android's JSONObject.optString returns the literal string "null" for JSON-null values.
private fun JSONObject.stringOrEmpty(key: String): String = if (isNull(key)) "" else optString(key)
private fun JSONObject.stringOrNull(key: String): String? = stringOrEmpty(key).ifEmpty { null }
private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null

fun parseManifest(json: String): StremioManifest {
    val root = JSONObject(json)
    val catalogsArray = root.optJSONArray("catalogs") ?: JSONArray()
    val catalogs = (0 until catalogsArray.length()).map { index ->
        val entry = catalogsArray.getJSONObject(index)
        val extraArray = entry.optJSONArray("extra") ?: JSONArray()
        // "extra" is normally an array of {name: ...} objects, but some addons use the
        // backward-compatible plain-string-array form (e.g. ["search"]) instead.
        val extraNames = (0 until extraArray.length())
            .mapNotNull { index ->
                val obj = extraArray.optJSONObject(index)
                if (obj != null) obj.stringOrEmpty("name").ifEmpty { null } else extraArray.optString(index).ifEmpty { null }
            }
            .toSet()
        StremioCatalog(
            type = entry.getString("type"),
            id = entry.getString("id"),
            name = entry.stringOrEmpty("name").ifEmpty { entry.getString("id") },
            extraNames = extraNames,
        )
    }
    return StremioManifest(
        id = root.stringOrEmpty("id"),
        name = root.stringOrEmpty("name").ifEmpty { root.stringOrEmpty("id") },
        description = root.stringOrNull("description"),
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
    return StremioMeta(
        id = meta.stringOrEmpty("id"),
        type = meta.stringOrEmpty("type"),
        name = meta.stringOrEmpty("name"),
        poster = meta.stringOrNull("poster"),
        description = meta.stringOrNull("description"),
        genres = genres,
        videos = videos,
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
