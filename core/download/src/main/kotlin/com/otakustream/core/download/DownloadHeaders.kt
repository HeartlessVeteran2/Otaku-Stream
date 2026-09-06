package com.otakustream.core.download

import com.otakustream.core.database.download.DownloadDao
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// Per-download request headers, looked up by the url being fetched.
//
// PlayerController builds a fresh DataSource.Factory for every playback because headers are
// per-video — a Referer, a cookie, an auth token the host requires. The downloader cannot do that:
// Media3 owns one factory for the whole DownloadManager. So the headers travel with the download
// record instead and are applied per request.
//
// Read through, not cached-only: a download interrupted by the process dying resumes later with an
// empty in-memory map, and dropping the headers on resume would turn a working download into a 403
// halfway through.
@Singleton
class DownloadHeaders @Inject constructor(
    private val dao: DownloadDao,
) {
    private val cache = ConcurrentHashMap<String, Map<String, String>>()

    fun remember(url: String, headers: Map<String, String>) {
        cache[url] = headers
    }

    fun forget(url: String) {
        cache.remove(url)
    }

    // Called on Media3's download executor, never the main thread — see the DAO query's comment.
    fun headersFor(url: String): Map<String, String> =
        cache.getOrPut(url) { decode(runCatching { dao.headersJsonForBlocking(url) }.getOrNull()) }

    companion object {
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
