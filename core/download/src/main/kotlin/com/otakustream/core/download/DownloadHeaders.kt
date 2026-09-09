package com.otakustream.core.download

import com.otakustream.core.database.download.DownloadDao
import org.json.JSONObject
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

// Per-download request headers, resolved for whichever url the downloader is fetching right now.
//
// PlayerController builds a fresh DataSource.Factory for every playback because headers are
// per-video — a Referer, a cookie, an auth token the host requires. The downloader cannot do that:
// Media3 owns one factory for the whole DownloadManager. So the headers travel with the download
// record instead and are applied per request.
//
// Which is where the url a request carries stops matching the url the headers were stored under. A
// progressive download fetches exactly the video url, so the two are the same string. **An HLS
// download fetches the playlist and then every segment**, each with its own url, and none of the
// segment urls are in the downloads table. Looking headers up by the requested url alone therefore
// found nothing for any segment — so every segment went out bare, and a source that requires a
// Referer served its playlist and then 403'd the whole way down. That is the exact failure the
// per-request design exists to prevent; it was fixed for the top-level url and missed for the rest.
//
// So the lookup is two-stage: the requested url first, then the origin it belongs to. Segments are
// served from the same origin as their playlist, which is what makes the second stage correct rather
// than a guess.
//
// Read through to the database, not cached-only: a download interrupted by the process dying resumes
// later with an empty map, and dropping the headers on resume would turn a working download into a
// 403 halfway through.
@Singleton
class DownloadHeaders @Inject constructor(
    private val dao: DownloadDao,
) {
    // Two caches rather than one, because they answer different questions and one of them is what
    // bounds the other. `byUrl` holds urls that really are downloads — a handful. `byOrigin` is what
    // every segment of a download shares, so a thousand-segment episode contributes one entry here
    // instead of a thousand there.
    //
    // Both bounded and access-ordered, the idiom core/torrent's TorrentFileCatalog already uses: an
    // unbounded map here grew for the life of the process, and nothing ever removed a segment's
    // entry because forget() only knows the download's own url.
    private val byUrl = boundedLru<String, Map<String, String>>(MAX_CACHED_URLS)
    private val byOrigin = boundedLru<String, Map<String, String>>(MAX_CACHED_ORIGINS)

    // Registered before the request is queued, so the download's own fetches never need the
    // database — including its segments, which reach it through the origin entry.
    fun remember(url: String, headers: Map<String, String>) {
        synchronized(byUrl) { byUrl[url] = headers }
        originOf(url)?.let { origin -> synchronized(byOrigin) { byOrigin[origin] = headers } }
    }

    // Drops the origin entry as well, or a removed download would keep answering for its host. It is
    // only a cache: another download from the same host repopulates it from the table on its next
    // request.
    fun forget(url: String) {
        synchronized(byUrl) { byUrl.remove(url) }
        originOf(url)?.let { origin -> synchronized(byOrigin) { byOrigin.remove(origin) } }
    }

    // Called on Media3's download executor, never the main thread — see the DAO query's comment.
    fun headersFor(url: String): Map<String, String> {
        synchronized(byUrl) { byUrl[url] }?.let { return it }

        // The url is itself a download. Exact beats origin, so two downloads from one host keep
        // their own headers.
        val exact = decode(runCatching { dao.headersJsonForBlocking(url) }.getOrNull())
        if (exact.isNotEmpty()) {
            synchronized(byUrl) { byUrl[url] = exact }
            return exact
        }

        // Not a download, so it is a part of one — a segment, a key, an init section. Find the
        // download it was fetched on behalf of by the origin they share.
        val origin = originOf(url) ?: return emptyMap()
        synchronized(byOrigin) { byOrigin[origin] }?.let { return it }
        val fromOrigin = runCatching { dao.headerRowsBlocking() }.getOrNull().orEmpty()
            .firstOrNull { row -> originOf(row.videoUrl) == origin }
            ?.let { row -> decode(row.headersJson) }
            .orEmpty()
        // Cached even when empty, and that is the point: a host with no stored headers is asked
        // about once rather than once per segment.
        synchronized(byOrigin) { byOrigin[origin] = fromOrigin }
        return fromOrigin
    }

    companion object {
        // A user's saved episodes, not their segments. Both are far above any real library and small
        // enough that the bound is a safety net rather than a mechanism the app relies on.
        private const val MAX_CACHED_URLS = 64
        private const val MAX_CACHED_ORIGINS = 32

        // scheme://authority, lowercased. Authority rather than host so a port — and, if a source
        // ever supplies one, userinfo — has to match too: a stricter key can only refuse to share
        // headers, which is the safe direction.
        internal fun originOf(url: String): String? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            val authority = uri.authority?.lowercase() ?: return null
            return "$scheme://$authority"
        }

        // Stored as JSON rather than a Room type converter: this is the only place that reads it,
        // and a converter would put a map serialisation format in the schema for one column.
        fun encode(headers: Map<String, String>): String? =
            if (headers.isEmpty()) null else JSONObject(headers as Map<*, *>).toString()

        // The counterpart, shared with anything that re-issues a stored request — a retried
        // download reads the same column this does. It lived here as a private function until a
        // second caller wrote its own copy and used getString instead of optString, which throws on
        // a non-string value and, inside a runCatching, discarded every header rather than that one.
        //
        // optString coerces instead, so a malformed value costs its own header and no more.
        // Unreadable JSON altogether means no headers, which is what the download did before they
        // were recorded at all.
        fun decode(json: String?): Map<String, String> {
            if (json.isNullOrBlank()) return emptyMap()
            return runCatching {
                val obj = JSONObject(json)
                obj.keys().asSequence().associateWith { obj.optString(it) }
            }.getOrDefault(emptyMap())
        }
    }
}

// Access-ordered so the download being fetched right now is the last thing dropped, which is the
// entry the next request is about to ask for.
private fun <K, V> boundedLru(maxEntries: Int): LinkedHashMap<K, V> =
    object : LinkedHashMap<K, V>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
            size > maxEntries
    }
