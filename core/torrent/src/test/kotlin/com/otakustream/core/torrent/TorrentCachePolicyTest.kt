package com.otakustream.core.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentCachePolicyTest {

    private fun file(path: String, mb: Long, accessedAt: Long) =
        CachedTorrentFile(path, mb * 1024 * 1024, accessedAt)

    private val quota = 100L * 1024 * 1024 // 100 MiB

    @Test
    fun `nothing is evicted under quota`() {
        val files = listOf(file("a", 30, 1), file("b", 30, 2))
        assertTrue(TorrentCachePolicy.selectForEviction(files, quota).isEmpty())
    }

    @Test
    fun `nothing is evicted exactly at quota`() {
        val files = listOf(file("a", 50, 1), file("b", 50, 2))
        assertTrue(TorrentCachePolicy.selectForEviction(files, quota).isEmpty())
    }

    @Test
    fun `evicts least recently accessed first`() {
        val files = listOf(
            file("newest", 60, 300),
            file("oldest", 60, 100),
            file("middle", 60, 200),
        )
        val evicted = TorrentCachePolicy.selectForEviction(files, quota)
        // 180 MiB total, 100 MiB quota: dropping the two oldest gets to 60 MiB.
        assertEquals(listOf("oldest", "middle"), evicted.map { it.path })
    }

    @Test
    fun `stops as soon as it is back under quota`() {
        val files = listOf(
            file("oldest", 80, 100),
            file("newer", 40, 200),
            file("newest", 40, 300),
        )
        // 160 MiB total; dropping only the oldest leaves 80 MiB, so the others must survive.
        assertEquals(listOf("oldest"), TorrentCachePolicy.selectForEviction(files, quota).map { it.path })
    }

    @Test
    fun `never evicts a protected file`() {
        // The file being played is the biggest and least-recently-stamped thing in the cache, which is
        // exactly what a naive sweep would delete out from under the player.
        val files = listOf(
            file("playing-now", 120, 1),
            file("stale", 40, 500),
        )
        val evicted = TorrentCachePolicy.selectForEviction(files, quota, protectedPaths = setOf("playing-now"))
        assertEquals(listOf("stale"), evicted.map { it.path })
    }

    @Test
    fun `evicts everything evictable when the protected file alone exceeds quota`() {
        // Can't get under quota without deleting what's playing, so it frees what it can and stops
        // rather than sacrificing the playback.
        val files = listOf(
            file("playing-now", 500, 1),
            file("stale-a", 20, 100),
            file("stale-b", 20, 200),
        )
        val evicted = TorrentCachePolicy.selectForEviction(files, quota, protectedPaths = setOf("playing-now"))
        assertEquals(listOf("stale-a", "stale-b"), evicted.map { it.path })
    }

    @Test
    fun `an empty cache evicts nothing`() {
        assertTrue(TorrentCachePolicy.selectForEviction(emptyList(), quota).isEmpty())
    }

    @Test
    fun `a zero quota evicts every unprotected file`() {
        val files = listOf(file("a", 10, 1), file("b", 10, 2))
        assertEquals(2, TorrentCachePolicy.selectForEviction(files, quotaBytes = 0).size)
    }

    @Test
    fun `default quota is two gibibytes`() {
        assertEquals(2L * 1024 * 1024 * 1024, TorrentCachePolicy.DEFAULT_QUOTA_BYTES)
    }
}
