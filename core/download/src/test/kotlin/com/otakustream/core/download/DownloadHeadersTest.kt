package com.otakustream.core.download

import com.otakustream.core.database.download.DownloadEntry
import com.otakustream.core.database.download.DownloadHeaderRow
import com.otakustream.core.database.download.DownloadDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `a download's own url still wins over its origin`() {
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
    fun `a host with no stored headers is asked about once, not once per segment`() {
        val dao = FakeDownloadDao()

        val headers = DownloadHeaders(dao)
        repeat(500) { n -> headers.headersFor("https://unknown.test/seg-$n.ts") }

        assertEquals("500 segments should cost one lookup, not 500", 1, dao.originScans)
    }

    @Test
    fun `a registered download answers its segments without touching the database`() {
        val dao = FakeDownloadDao().apply { failIfQueried = true }

        val headers = DownloadHeaders(dao)
        headers.remember(PLAYLIST, REFERER)

        assertEquals(REFERER, headers.headersFor(SEGMENT))
    }

    // Removing a download must not leave it answering for its whole host — the next download from
    // the same origin would inherit headers meant for something the user deleted.
    @Test
    fun `forget drops the origin as well as the url`() {
        val dao = FakeDownloadDao().apply { failIfQueried = true }

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
    // process. 33 origins into a map that holds 32.
    @Test
    fun `the least recently used origin is the one forgotten`() {
        val dao = FakeDownloadDao()
        val headers = DownloadHeaders(dao)
        (0 until 33).forEach { n -> headers.headersFor("https://host-$n.test/seg.ts") }
        val scansAfterFirstPass = dao.originScans

        // The first host was evicted, so asking again costs another scan; the last was not.
        headers.headersFor("https://host-0.test/seg.ts")
        val afterEvicted = dao.originScans
        headers.headersFor("https://host-32.test/seg.ts")

        assertEquals("the evicted origin should be looked up again", scansAfterFirstPass + 1, afterEvicted)
        assertEquals("the newest origin should still be cached", afterEvicted, dao.originScans)
    }

    private class FakeDownloadDao(vararg rows: Pair<String, Map<String, String>>) : DownloadDao {
        private val stored = rows.toMap()

        // Counts the origin scan specifically — the query a segment falls through to. The whole
        // point of caching the miss is that this stays at one per host.
        var originScans = 0
            private set

        // Set by the tests that assert a path never reaches the database at all.
        var failIfQueried = false

        override fun headersJsonForBlocking(videoUrl: String): String? {
            check(!failIfQueried) { "headersFor should not have queried the database" }
            return stored[videoUrl]?.let { DownloadHeaders.encode(it) }
        }

        override fun headerRowsBlocking(): List<DownloadHeaderRow> {
            check(!failIfQueried) { "headersFor should not have queried the database" }
            originScans++
            return stored.map { (url, headers) -> DownloadHeaderRow(url, DownloadHeaders.encode(headers)) }
        }

        override fun observeAll(): Flow<List<DownloadEntry>> = flowOf(emptyList())
        override fun observeForMedia(mediaUrl: String): Flow<List<DownloadEntry>> = flowOf(emptyList())
        override suspend fun entriesForEpisode(episodeUrl: String): List<DownloadEntry> = emptyList()
        override suspend fun upsert(entry: DownloadEntry) = Unit
        override suspend fun delete(videoUrl: String) = Unit
    }
}
