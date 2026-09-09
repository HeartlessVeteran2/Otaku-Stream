package com.otakustream.core.download

import com.otakustream.core.database.download.DownloadDao
import com.otakustream.core.database.download.DownloadHeaderRow
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
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
// So a request that is not itself a download is matched to the download it belongs to, by the url
// they share the most of. Segments are served from the same origin as their playlist and almost
// always from the same directory, which is what makes that correct rather than a guess.
//
// Read through to the database, not cached-only: a download interrupted by the process dying resumes
// later with an empty map, and dropping the headers on resume would turn a working download into a
// 403 halfway through.
@Singleton
class DownloadHeaders @Inject constructor(
    private val dao: DownloadDao,
) {
    // Two caches rather than one, because they answer different questions and one of them is what
    // bounds the other. `byUrl` holds urls that really are downloads — a handful. `byDirectory` is
    // what every segment of a download shares, so a thousand-segment episode contributes one entry
    // here instead of a thousand there.
    //
    // Both bounded and access-ordered, the idiom core/torrent's TorrentFileCatalog already uses: an
    // unbounded map here grew for the life of the process, and nothing ever removed a segment's
    // entry because forget() only knows the download's own url.
    private val byUrl = boundedLru<String, Map<String, String>>(MAX_CACHED_URLS)
    private val byDirectory = boundedLru<String, Map<String, String>>(MAX_CACHED_DIRECTORIES)

    // Bumped by every change to what is stored, and checked before a lookup commits what it read.
    //
    // The lookup below reads the table and then writes a cache entry, and removeAndAwait calls
    // forget() *before* Media3's removal completes — so a segment request already in flight could
    // read a row, have forget() clear the caches underneath it, and then write the deleted
    // download's headers back. The entry outlived the download and was served to the next one on
    // that host. Committing only when nothing changed in between closes that without a lock across
    // the database read, which happens on Media3's download executor and must not block forget().
    private val generation = AtomicLong()

    // Registered before the request is queued, so the download's own fetches never need the
    // database — including its segments, which reach it through the directory entry.
    fun remember(url: String, headers: Map<String, String>) {
        generation.incrementAndGet()
        synchronized(byUrl) { byUrl[url] = headers }
        directoryOf(url)?.let { dir -> synchronized(byDirectory) { byDirectory[dir] = headers } }
    }

    // Drops the whole directory cache, not just this download's own directory.
    //
    // A download does not occupy only one: a playlist at /stream/master.m3u8 can list its segments
    // under /stream/chunks/, and every directory a request touched holds its own entry. Removing
    // the playlist's alone left the others answering for a download the user had deleted, and the
    // next download from that host would be handed its headers with no database read to correct
    // them.
    //
    // Clearing all of it is the complete answer rather than the clever one, and it is affordable:
    // forget() runs when someone deletes a download, and these are only a cache — every other
    // download repopulates its own on its next request, at one table scan each. Tracking which
    // directories belong to which download would mean a second index to bound and keep in step,
    // which is more machinery than the thing it saves.
    fun forget(url: String) {
        generation.incrementAndGet()
        synchronized(byUrl) { byUrl.remove(url) }
        synchronized(byDirectory) { byDirectory.clear() }
    }

    // Called on Media3's download executor, never the main thread — see the DAO query's comment.
    fun headersFor(url: String): Map<String, String> {
        synchronized(byUrl) { byUrl[url] }?.let { return it }

        // Is this url a download in its own right? Asked before the directory cache and not after,
        // because a download that has not been remembered — one resuming after the process died —
        // would otherwise be handed the headers of whichever neighbour was cached first. It costs
        // an indexed point lookup per new segment url, which is not the cost worth avoiding here;
        // the full-table scan below is, and the directory cache is what avoids it.
        val exactRow = runCatching { dao.headerRowForBlocking(url) }.getOrNull()
        if (exactRow != null) {
            // The row existing is the answer, even when it holds no readable headers. A download
            // that stored none must go out bare rather than inherit a neighbour's credentials.
            val own = decode(exactRow.headersJson)
            synchronized(byUrl) { byUrl[url] = own }
            return own
        }

        // Not a download, so it is a part of one — a segment, a key, an init section. Find the
        // download it was fetched on behalf of.
        val directory = directoryOf(url) ?: return emptyMap()
        synchronized(byDirectory) { byDirectory[directory] }?.let { return it }

        val readAt = generation.get()
        val resolved = resolveFromTable(url, directory)
        // Cached even when empty, and that is the point: a place with no stored headers is asked
        // about once rather than once per segment.
        //
        // Unless something was forgotten or registered while the table was being read, in which
        // case what was read may already describe a download that no longer exists. Dropping the
        // write costs one repeated scan and nothing else.
        if (generation.get() == readAt) {
            synchronized(byDirectory) { byDirectory[directory] = resolved }
        }
        return resolved
    }

    // Which download does this url belong to? Only one it actually sits underneath.
    //
    // Sharing an origin is not a relationship. Every same-origin request used to be handed some
    // download's headers, because any non-empty candidate list produced a winner — so a thumbnail,
    // an analytics ping, anything else on that CDN collected the Referer and cookies belonging to a
    // video it has nothing to do with. Requiring containment means a request that belongs to no
    // download gets nothing, which is what it should have had all along.
    //
    // Containment is tested on the directory — the origin plus the path up to and including the
    // last slash — so the comparison lands on a path-segment boundary for free and cannot match
    // half a name. /video/abc123/ contains /video/abc123/chunks/ and does not contain
    // /video/abc124/, which a character-wise prefix score rated nearly identical.
    //
    // Longest containing directory wins, so a download nested inside another's path keeps its own
    // headers.
    //
    // Two residuals, neither fixable from here, because a segment url carries nothing that says
    // which download requested it — Media3 gives the resolver a DataSpec and no parent link:
    //  - two playlists in the *same* directory with *different* headers are indistinguishable.
    //    Ties break by url, so the choice is deterministic rather than dependent on row order.
    //  - a playlist whose segments are listed by absolute url in a *sibling* directory resolves to
    //    nothing rather than to a guess. That is the safe direction: refusing to share headers
    //    costs a 403 on a layout that is already unusual, where sharing them wrongly hands one
    //    video's credentials to an unrelated request.
    private fun resolveFromTable(url: String, directory: String): Map<String, String> {
        val origin = originOf(url) ?: return emptyMap()
        val best = runCatching { dao.headerRowsBlocking() }.getOrNull().orEmpty()
            .filter { row -> originOf(row.videoUrl) == origin }
            .mapNotNull { row -> directoryOf(row.videoUrl)?.let { dir -> dir to row } }
            .filter { (dir, _) -> directory.startsWith(dir) }
            .minWithOrNull(
                compareByDescending<Pair<String, DownloadHeaderRow>> { (dir, _) -> dir.length }
                    .thenBy { (_, row) -> row.videoUrl },
            )
        return decode(best?.second?.headersJson)
    }

    companion object {
        // A user's saved episodes, not their segments. Both are far above any real library and small
        // enough that the bound is a safety net rather than a mechanism the app relies on.
        private const val MAX_CACHED_URLS = 64
        private const val MAX_CACHED_DIRECTORIES = 32

        // scheme://authority, lowercased. Authority rather than host so a port — and, if a source
        // ever supplies one, userinfo — has to match too: a stricter key can only refuse to share
        // headers, which is the safe direction.
        internal fun originOf(url: String): String? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            val authority = uri.authority?.lowercase() ?: return null
            return "$scheme://$authority"
        }

        // The origin plus the path up to and including the last slash — the "folder" a url sits in.
        // Segments of one HLS download nearly always share this with their playlist, which is what
        // makes it the right granularity for the cache: one entry per download rather than one per
        // origin (too coarse to tell two downloads apart) or one per url (which is the leak).
        internal fun directoryOf(url: String): String? {
            val origin = originOf(url) ?: return null
            val path = runCatching { URI(url) }.getOrNull()?.path.orEmpty()
            val lastSlash = path.lastIndexOf('/')
            return origin + if (lastSlash >= 0) path.substring(0, lastSlash + 1) else "/"
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
