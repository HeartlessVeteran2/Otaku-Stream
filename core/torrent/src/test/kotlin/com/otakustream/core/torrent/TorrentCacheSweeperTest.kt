package com.otakustream.core.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// The sweeper is the only class in the app that deletes the user's files, and until now it had no
// test — only `TorrentCachePolicy`, the pure function that decides *what* to evict, did.
//
// That gap is exactly how a real bug shipped: the policy has always honoured protected paths, but
// the caller passed none, so a sweep triggered mid-playback would happily delete the file being
// streamed. Testing the decision without testing the deletion left the dangerous half uncovered.
class TorrentCacheSweeperTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun writeFile(name: String, sizeBytes: Int, ageMillis: Long = 0): File =
        File(folder.root, name).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(sizeBytes))
            setLastModified(System.currentTimeMillis() - ageMillis)
        }

    @Test
    fun `a protected file is never deleted, however far over quota it is`() {
        // The exact shape of the bug: the file being streamed is both the largest thing in the cache
        // and the most recently written, so a size-driven sweep goes for it first.
        val watching = writeFile("watching.mkv", sizeBytes = 900)
        val old = writeFile("old.mkv", sizeBytes = 100, ageMillis = 60_000)

        TorrentCacheSweeper.sweep(
            folder.root,
            quotaBytes = 200,
            protectedPaths = setOf(watching.absolutePath),
        )

        assertTrue("the file being streamed must survive", watching.exists())
        assertFalse("the unprotected file should have been evicted", old.exists())
    }

    @Test
    fun `without protection the streamed file is the first thing evicted`() {
        // Pins the consequence rather than the mechanism, so it stays honest if the policy changes:
        // this is what "no protected paths" costs, and why the caller must pass them.
        val largest = writeFile("largest.mkv", sizeBytes = 900)

        TorrentCacheSweeper.sweep(folder.root, quotaBytes = 200, protectedPaths = emptySet())

        assertFalse(largest.exists())
    }

    @Test
    fun `nothing is deleted when already under quota`() {
        val a = writeFile("a.mkv", sizeBytes = 50)
        val b = writeFile("b.mkv", sizeBytes = 50)

        val freed = TorrentCacheSweeper.sweep(folder.root, quotaBytes = 1_000)

        assertEquals(0L, freed)
        assertTrue(a.exists())
        assertTrue(b.exists())
    }

    @Test
    fun `evicts least recently used first`() {
        val oldest = writeFile("oldest.mkv", sizeBytes = 100, ageMillis = 90_000)
        val middle = writeFile("middle.mkv", sizeBytes = 100, ageMillis = 60_000)
        val newest = writeFile("newest.mkv", sizeBytes = 100, ageMillis = 1_000)

        TorrentCacheSweeper.sweep(folder.root, quotaBytes = 250)

        assertFalse(oldest.exists())
        assertTrue(middle.exists())
        assertTrue(newest.exists())
    }

    @Test
    fun `reports only bytes it actually freed`() {
        val evictable = writeFile("evictable.mkv", sizeBytes = 400, ageMillis = 60_000)
        writeFile("keep.mkv", sizeBytes = 100)

        val freed = TorrentCacheSweeper.sweep(folder.root, quotaBytes = 150)

        assertEquals(400L, freed)
        assertFalse(evictable.exists())
    }

    @Test
    fun `sweeps nested torrent directories and tidies up the empty ones`() {
        // Torrents create a directory per torrent name; an emptied one is just litter.
        val nested = writeFile("Some.Release.Name/episode.mkv", sizeBytes = 500, ageMillis = 60_000)

        TorrentCacheSweeper.sweep(folder.root, quotaBytes = 10)

        assertFalse(nested.exists())
        assertFalse("the emptied torrent directory should be removed", nested.parentFile!!.exists())
    }

    @Test
    fun `clear removes everything except what is being read`() {
        val watching = writeFile("watching.mkv", sizeBytes = 500)
        val other = writeFile("other.mkv", sizeBytes = 500)

        TorrentCacheSweeper.clear(folder.root, protectedPaths = setOf(watching.absolutePath))

        assertTrue("clearing the cache mid-playback must not kill the playback", watching.exists())
        assertFalse(other.exists())
    }

    @Test
    fun `usage counts every file including protected ones`() {
        writeFile("a.mkv", sizeBytes = 300)
        writeFile("nested/b.mkv", sizeBytes = 200)

        assertEquals(500L, TorrentCacheSweeper.usageBytes(folder.root))
    }

    @Test
    fun `a missing cache directory is not an error`() {
        val absent = File(folder.root, "never-created")

        assertEquals(0L, TorrentCacheSweeper.sweep(absent, quotaBytes = 100))
        assertEquals(0L, TorrentCacheSweeper.clear(absent))
        assertEquals(0L, TorrentCacheSweeper.usageBytes(absent))
    }
}
