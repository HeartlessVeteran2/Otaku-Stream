package com.otakustream.core.sources.scripting

import com.otakustream.core.sources.api.CatalogPage
import com.otakustream.core.sources.api.Episode
import com.otakustream.core.sources.api.MediaDetails
import com.otakustream.core.sources.api.MediaItem
import com.otakustream.core.sources.api.MediaStatus
import com.otakustream.core.sources.api.SourceFilter
import com.otakustream.core.sources.api.Video
import com.otakustream.core.sources.api.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// Script functions return JSON.stringify(...) matching VideoSource's data shapes.
class ScriptedVideoSource(
    private val engine: ScriptEngine,
    private val scope: ScriptScope,
    override val id: Long,
    override val name: String,
    override val lang: String,
) : VideoSource {

    // Rhino's Scriptable/Context are not thread-safe — serialize all calls into this instance's
    // scope so two concurrent VideoSource calls (e.g. a search while a details load is in flight)
    // can't interleave against the same interpreter state.
    private val mutex = Mutex()

    override suspend fun getPopular(page: Int): CatalogPage = withContext(Dispatchers.IO) {
        mutex.withLock { parseCatalogPage(engine.call(scope, "getPopular", page.toDouble())) }
    }

    override suspend fun getLatest(page: Int): CatalogPage = withContext(Dispatchers.IO) {
        mutex.withLock { parseCatalogPage(engine.call(scope, "getLatest", page.toDouble())) }
    }

    override suspend fun search(query: String, filters: List<SourceFilter>, page: Int): CatalogPage =
        withContext(Dispatchers.IO) {
            mutex.withLock { parseCatalogPage(engine.call(scope, "search", query, page.toDouble())) }
        }

    override suspend fun getMediaDetails(media: MediaItem): MediaDetails = withContext(Dispatchers.IO) {
        val json = JSONObject(mutex.withLock { engine.call(scope, "getMediaDetails", media.url) })
        MediaDetails(
            media = media,
            description = json.optString("description").ifEmpty { null },
            genres = json.optJSONArray("genres").toStringList(),
            status = runCatching { MediaStatus.valueOf(json.optString("status", "UNKNOWN")) }.getOrDefault(MediaStatus.UNKNOWN),
        )
    }

    override suspend fun getEpisodeList(media: MediaItem): List<Episode> = withContext(Dispatchers.IO) {
        val array = JSONArray(mutex.withLock { engine.call(scope, "getEpisodeList", media.url) })
        (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            Episode(
                url = entry.getString("url"),
                name = entry.getString("name"),
                episodeNumber = entry.optDouble("episodeNumber", 1.0).toFloat(),
            )
        }
    }

    override suspend fun getVideoList(episode: Episode): List<Video> = withContext(Dispatchers.IO) {
        val array = JSONArray(mutex.withLock { engine.call(scope, "getVideoList", episode.url) })
        (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            Video(
                url = entry.getString("url"),
                quality = entry.optString("quality", "default"),
                headers = entry.optJSONObject("headers").toStringMap(),
                isM3U8 = entry.optBoolean("isM3U8", false),
            )
        }
    }

    private fun parseCatalogPage(json: String): CatalogPage {
        val root = JSONObject(json)
        val items = root.getJSONArray("items")
        val mediaItems = (0 until items.length()).map { index ->
            val entry = items.getJSONObject(index)
            MediaItem(
                url = entry.getString("url"),
                title = entry.getString("title"),
                coverUrl = entry.optString("coverUrl").ifEmpty { null },
            )
        }
        return CatalogPage(items = mediaItems, hasNextPage = root.optBoolean("hasNextPage", false))
    }
}

private fun JSONArray?.toStringList(): List<String> =
    this?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList()

private fun JSONObject?.toStringMap(): Map<String, String> =
    this?.let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } } ?: emptyMap()
