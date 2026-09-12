package com.otakustream.core.download

import com.otakustream.core.database.download.DownloadEntry
import com.otakustream.core.database.download.DownloadDao
import com.otakustream.core.database.download.DownloadHeaderRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test

private const val PLAYLIST = "https://cdn.example.test/stream/master.m3u8"
private const val SEGMENT = "https://cdn.example.test/stream/seg-0042.ts"
private val REFERER = mapOf("Referer" to "https://watch.example.test/")

// What headers a download's requests actually carry.
//
// The bug this exists for: headers are stored under the video url, and resolved against the url of
// whatever is being fetched. For a progressive mp4 those are the same string, so it worked. For HLS
// they are not — Media3 fetches the playlist and then every segment — so every segment resolved to
// nothing and went out bare. A source that requires a Referer served its playlist and then 403'd
// the rest, which reads to the user as a download that stalls at 0% for no reason.
//
// It is invisible to any test that only asks about the video url, which is why there wasn't one.
class DownloadHeadersTest {

    @Test
    fun `a segment carries the headers of the download it belongs to`() {
        // No remember() call: this is the state after a process death, where the in-memory cache is
        // empty and the database is the only record. That is exactly when a long download resumes.
        val dao = FakeDownloadDao(PLAYLIST to REFERER)

        assertEquals(REFERER, DownloadHeaders(dao).headersFor(SEGMENT))
    }

    @Test
    fun `a download's own url still wins over its neighbours`() {
        // Two downloads on one host with different headers. The exact match has to rank first, or
        // the second download would inherit the first's.
        val other = mapOf("Referer" to "https://elsewhere.example.test/")
        val dao = FakeDownloadDao(
            PLAYLIST to REFERER,
            "https://cdn.example.test/other/master.m3u8" to other,
        )

        val headers = DownloadHeaders(dao)

        assertEquals(REFERER, headers.headersFor(PLAYLIST))
        assertEquals(other, headers.headersFor("https://cdn.example.test/other/master.m3u8"))
    }

    // The finding two reviewers raised on the first version, and they were right.
    //
    // Keying the fallback on origin alone meant one entry per *host*, so two downloads from one host
    // overwrote each other and the first download's segments went out with the second's Referer —
    // exactly the 403 this whole change exists to stop, reintroduced one level down. Ranking by
    // shared url prefix separates them, because a host that gives each video its own credentials
    // gives it its own path too.
    @Test
    fun `two downloads on one host do not steal each other's headers`() {
        val first = mapOf("Referer" to "https://watch.example.test/one")
        val second = mapOf("Referer" to "https://watch.example.test/two")
        val dao = FakeDownloadDao(
            "https://cdn.example.test/video/abc123/master.m3u8" to first,
            "https://cdn.example.test/video/def456/master.m3u8" to second,
        )

        val headers = DownloadHeaders(dao)

        assertEquals(first, headers.headersFor("https://cdn.example.test/video/abc123/seg-1.ts"))
        assertEquals(second, headers.headersFor("https://cdn.example.test/video/def456/seg-1.ts"))
    }

    // Registering the second download must not retune the first one's segments either. remember()
    // writes the same cache the lookup reads, so this is the same defect on the write path.
    @Test
    fun `registering a second download leaves the first one's segments alone`() {
        val first = mapOf("Referer" to "https://watch.example.test/one")
        val second = mapOf("Referer" to "https://watch.example.test/two")
        val headers = DownloadHeaders(FakeDownloadDao())

        headers.remember("https://cdn.example.test/video/abc123/master.m3u8", first)
        headers.remember("https://cdn.example.test/video/def456/master.m3u8", second)

        assertEquals(first, headers.headersFor("https://cdn.example.test/video/abc123/seg-1.ts"))
        assertEquals(second, headers.headersFor("https://cdn.example.test/video/def456/seg-1.ts"))
    }

    // A download that stored no headers must go out bare, not borrow a neighbour's.
    //
    // Reading the headers *column* made "this url is not a download" and "this download stored
    // nothing" the same answer — null — so a download with no headers of its own fell through to
    // the fallback and was handed another download's Referer and cookies. Reading the row tells
    // them apart.
    @Test
    fun `a download with no stored headers does not inherit a neighbour's`() {
        val dao = FakeDownloadDao(
            "https://cdn.example.test/stream/other.m3u8" to REFERER,
            "https://cdn.example.test/stream/bare.m3u8" to emptyMap(),
        )

        assertEquals(
            emptyMap<String, String>(),
            DownloadHeaders(dao).headersFor("https://cdn.example.test/stream/bare.m3u8"),
        )
    }

    // The same thing when the stored JSON is present but unreadable. It is still a download, so it
    // still answers for itself — with nothing.
    //
    // The neighbour is named so it would *win* the fallback's tie-break. An earlier version of this
    // test had it losing, so removing the row-presence check left the test green — it passed
    // because the malformed row happened to pick itself, not because anything refused the fallback.
    @Test
    fun `a download whose stored json is malformed does not inherit either`() {
        val dao = FakeDownloadDao("https://cdn.example.test/stream/aaa.m3u8" to REFERER)
            .withRawRow("https://cdn.example.test/stream/zzz.m3u8", "{not json")

        assertEquals(
            emptyMap<String, String>(),
            DownloadHeaders(dao).headersFor("https://cdn.example.test/stream/zzz.m3u8"),
        )
    }

    // Removing a download while one of its requests is still in flight must not put it back.
    //
    // The lookup reads the table and then writes what it read into the cache, and
    // EpisodeDownloads.removeAndAwait calls forget() *before* Media3 finishes removing — so a
    // segment request that started earlier could resurrect the deleted download's headers and serve
    // them to whatever downloaded from that host next. The fake calls forget() from inside the
    // table read, which is that interleaving made deterministic.
    @Test
    fun `a forget during a lookup does not resurrect the deleted download`() {
        val dao = FakeDownloadDao(PLAYLIST to REFERER)
        val headers = DownloadHeaders(dao)
        dao.onTableRead = { headers.forget(PLAYLIST) }

        // This request read the row before the removal landed, so it may still answer with it.
        headers.headersFor(SEGMENT)
        dao.onTableRead = null
        dao.dropAll()

        // What must not happen is the entry outliving the download.
        assertEquals(emptyMap<String, String>(), headers.headersFor(SEGMENT))
    }

    // Sharing a host is not a relationship, and treating it as one leaked credentials sideways.
    //
    // Any non-empty candidate list produced a winner, so every same-origin request was handed some
    // download's headers — a thumbnail, an analytics ping, anything else on that CDN collected the
    // Referer and cookies belonging to a video it has nothing to do with.
    @Test
    fun `a same-host request that belongs to no download gets nothing`() {
        val dao = FakeDownloadDao("https://cdn.example.test/video/abc123/master.m3u8" to REFERER)

        val headers = DownloadHeaders(dao)

        assertEquals(emptyMap<String, String>(), headers.headersFor("https://cdn.example.test/thumbs/x.jpg"))
        assertEquals(emptyMap<String, String>(), headers.headersFor("https://cdn.example.test/ping"))
    }

    // Containment is tested on the directory, which ends at a slash — so it lands on a path-segment
    // boundary and cannot match half a name. A character-wise prefix score rated these two nearly
    // identical and would hand one video's credentials to the other.
    @Test
    fun `a sibling video with a near-identical path gets nothing`() {
        val dao = FakeDownloadDao("https://cdn.example.test/video/abc123/master.m3u8" to REFERER)

        assertEquals(
            emptyMap<String, String>(),
            DownloadHeaders(dao).headersFor("https://cdn.example.test/video/abc124/seg-1.ts"),
        )
    }

    // The path-level twin of the hostname test above, and the reason containment is tested on the
    // directory rather than on a trimmed prefix: a directory ends at a slash, so /video/abc/ cannot
    // match /video/abcd/. Compared without that boundary, one video's credentials go to another
    // whose name merely starts with the same letters — which a source numbering its videos
    // sequentially produces on its own, without anyone attacking anything.
    @Test
    fun `a directory whose name merely starts with the same text gets nothing`() {
        val dao = FakeDownloadDao("https://cdn.example.test/video/abc/master.m3u8" to REFERER)

        assertEquals(
            emptyMap<String, String>(),
            DownloadHeaders(dao).headersFor("https://cdn.example.test/video/abcd/seg-1.ts"),
        )
    }

    // The layout this has to keep working: a playlist that lists its segments in a subdirectory.
    @Test
    fun `segments in a subdirectory of the playlist still resolve`() {
        val dao = FakeDownloadDao("https://cdn.example.test/video/abc123/master.m3u8" to REFERER)

        assertEquals(
            REFERER,
            DownloadHeaders(dao).headersFor("https://cdn.example.test/video/abc123/chunks/seg-1.ts"),
        )
    }

    // Deleting a download must not leave it answering anywhere, and it does not occupy only one
    // directory: a playlist at /stream/ can list segments under /stream/chunks/, and each directory
    // a request touched holds its own entry. Dropping the playlist's alone left the others serving
    // a download the user deleted, with no database read to correct them.
    @Test
    fun `forget clears entries a download left in other directories`() {
        val dao = FakeDownloadDao(PLAYLIST to REFERER)
        val headers = DownloadHeaders(dao)

        // Populate a second directory belonging to the same download.
        val chunk = "https://cdn.example.test/stream/chunks/seg-1.ts"
        assertEquals(REFERER, headers.headersFor(chunk))

        headers.forget(PLAYLIST)
        dao.dropAll()

        assertEquals(emptyMap<String, String>(), headers.headersFor(chunk))
    }

    @Test
    fun `a request to a different host gets nothing`() {
        val dao = FakeDownloadDao(PLAYLIST to REFERER)

        assertEquals(emptyMap<String, String>(), DownloadHeaders(dao).headersFor("https://other.test/x.ts"))
    }

    // A host is a hostname, not a prefix of one. Sharing headers with `cdn.example.test.evil.test`
    // would hand a third party whatever the Referer authorises.
    @Test
    fun `a host that merely starts with the same text gets nothing`() {
        val dao = FakeDownloadDao(PLAYLIST to REFERER)

        val headers = DownloadHeaders(dao)

        assertEquals(emptyMap<String, String>(), headers.headersFor("https://cdn.example.test.evil.test/seg.ts"))
        assertEquals(emptyMap<String, String>(), headers.headersFor("https://cdn.example.testing/seg.ts"))
    }

    @Test
    fun `a different port or scheme is a different origin`() {
        val dao = FakeDownloadDao(PLAYLIST to REFERER)

        val headers = DownloadHeaders(dao)

        assertEquals(emptyMap<String, String>(), headers.headersFor("https://cdn.example.test:8443/seg.ts"))
        assertEquals(emptyMap<String, String>(), headers.headersFor("http://cdn.example.test/seg.ts"))
    }

    // The leak half of the same bug. Every segment used to cache its own miss, so one episode left
    // thousands of entries behind that forget() could never reach — it only knows the video url.
    @Test
    fun `a place with no stored headers is scanned for once, not once per segment`() {
        val dao = FakeDownloadDao()

        val headers = DownloadHeaders(dao)
        repeat(500) { n -> headers.headersFor("https://unknown.test/x/seg-$n.ts") }

        assertEquals("500 segments should cost one table scan, not 500", 1, dao.tableScans)
    }

    // What a registered download's segments actually cost, which is not what the first version of
    // this test claimed.
    //
    // It asserted "without touching the database" and enforced that with a check() inside the fake —
    // which throws IllegalStateException, which the production runCatching swallows. The guard could
    // never fire and the test proved nothing. What is true: each new segment url costs one indexed
    // point lookup, and no table scan. The scan is the cost worth avoiding; the point lookup is what
    // keeps a resuming download from inheriting a neighbour's headers.
    @Test
    fun `a registered download's segments cost a point lookup each and no table scan`() {
        val dao = FakeDownloadDao()

        val headers = DownloadHeaders(dao)
        headers.remember(PLAYLIST, REFERER)
        repeat(3) { n -> assertEquals(REFERER, headers.headersFor("https://cdn.example.test/stream/seg-$n.ts")) }

        assertEquals("one point lookup per segment url", 3, dao.pointLookups)
        assertEquals("the table should never be scanned", 0, dao.tableScans)

        // And asking again costs another one, which is deliberate rather than a miss. Caching a
        // segment's answer under its own url is what made the first version leak: one entry per
        // segment, thousands per episode, and forget() could reach none of them because it only
        // knows the download's url. Media3 fetches each segment once, so the repeat is the rare
        // case and the leak was the common one.
        headers.headersFor("https://cdn.example.test/stream/seg-0.ts")
        assertEquals("a repeat is not cached under its own url, by design", 4, dao.pointLookups)
    }

    // Removing a download must not leave it answering for its neighbours — the next download from
    // the same place would inherit headers meant for something the user deleted.
    @Test
    fun `forget drops the directory as well as the url`() {
        val dao = FakeDownloadDao()

        val headers = DownloadHeaders(dao)
        headers.remember(PLAYLIST, REFERER)
        headers.forget(PLAYLIST)

        assertEquals(emptyMap<String, String>(), headers.headersFor(SEGMENT))
        assertEquals(emptyMap<String, String>(), headers.headersFor(PLAYLIST))
    }

    @Test
    fun `a url that is not a url resolves to nothing rather than throwing`() {
        val dao = FakeDownloadDao(PLAYLIST to REFERER)

        assertEquals(emptyMap<String, String>(), DownloadHeaders(dao).headersFor("not a url at all"))
    }

    @Test
    fun `malformed stored json costs no more than the header it is on`() {
        assertEquals(emptyMap<String, String>(), DownloadHeaders.decode("{not json"))
        assertEquals(emptyMap<String, String>(), DownloadHeaders.decode(null))
        assertEquals(mapOf("Referer" to "https://x.test/"), DownloadHeaders.decode("""{"Referer":"https://x.test/"}"""))
    }

    @Test
    fun `encode and decode round-trip, and an empty map stores nothing`() {
        assertEquals(null, DownloadHeaders.encode(emptyMap()))
        assertEquals(REFERER, DownloadHeaders.decode(DownloadHeaders.encode(REFERER)))
    }

    // Bounded, so a session that browses a lot of hosts cannot grow this for the life of the
    // process. 33 directories into a map that holds 32.
    @Test
    fun `the least recently used directory is the one forgotten`() {
        val dao = FakeDownloadDao()
        val headers = DownloadHeaders(dao)
        (0 until 33).forEach { n -> headers.headersFor("https://host-$n.test/x/seg.ts") }
        val scansAfterFirstPass = dao.tableScans

        // The first was evicted, so asking again costs another scan; the last was not.
        headers.headersFor("https://host-0.test/x/seg.ts")
        val afterEvicted = dao.tableScans
        headers.headersFor("https://host-32.test/x/seg.ts")

        assertEquals("the evicted directory should be looked up again", scansAfterFirstPass + 1, afterEvicted)
        assertEquals("the newest directory should still be cached", afterEvicted, dao.tableScans)
    }

    private class FakeDownloadDao(vararg rows: Pair<String, Map<String, String>>) : DownloadDao {
        private val stored = rows.associate { (url, headers) -> url to DownloadHeaders.encode(headers) }
            .toMutableMap()

        // Counted rather than forbidden. The first version of this fake threw from inside the DAO
        // to assert "never queried", and production wraps every DAO call in runCatching — so the
        // throw was swallowed and the assertion was dead. A counter cannot be swallowed.
        var pointLookups = 0
            private set
        var tableScans = 0
            private set

        // Runs inside the table read, to make the forget-during-lookup interleaving deterministic.
        var onTableRead: (() -> Unit)? = null

        fun withRawRow(url: String, headersJson: String?) = apply { stored[url] = headersJson }

        fun dropAll() = stored.clear()

        override fun headerRowForBlocking(videoUrl: String): DownloadHeaderRow? {
            pointLookups++
            if (videoUrl !in stored) return null
            return DownloadHeaderRow(videoUrl, stored[videoUrl])
        }

        override fun headerRowsBlocking(): List<DownloadHeaderRow> {
            tableScans++
            onTableRead?.invoke()
            // Mirrors the query's `WHERE headersJson IS NOT NULL`.
            return stored.filterValues { it != null }.map { (url, json) -> DownloadHeaderRow(url, json) }
        }

        override fun observeAll(): Flow<List<DownloadEntry>> = flowOf(emptyList())
        override fun observeForMedia(mediaUrl: String): Flow<List<DownloadEntry>> = flowOf(emptyList())
        override suspend fun entriesForEpisode(episodeUrl: String): List<DownloadEntry> = emptyList()
        override suspend fun upsert(entry: DownloadEntry) = Unit
        override suspend fun delete(videoUrl: String) = Unit
    }
}
