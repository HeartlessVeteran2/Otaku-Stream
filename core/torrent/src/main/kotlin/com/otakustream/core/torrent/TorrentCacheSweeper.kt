package com.otakustream.core.torrent

import android.util.Log
import java.io.File

private const val TAG = "TorrentCacheSweeper"

// Applies TorrentCachePolicy to a real directory. The decision of *what* to evict lives in the
// policy, unit-tested without a filesystem; this is only the part that has to touch disk.
object TorrentCacheSweeper {

    // Deletes least-recently-used torrent data until the directory is back under quota. Returns the
    // number of bytes freed.
    //
    // `protectedPaths` must contain the absolute path of anything currently being read. The file
    // being played is the largest and most recently written thing in the cache, so without it a
    // size-driven sweep deletes exactly what the player is streaming.
    fun sweep(
        cacheDir: File,
        quotaBytes: Long = TorrentCachePolicy.DEFAULT_QUOTA_BYTES,
        protectedPaths: Set<String> = emptySet(),
    ): Long {
        if (!cacheDir.isDirectory) return 0

        val onDisk = scan(cacheDir)
        val victims = TorrentCachePolicy.selectForEviction(onDisk, quotaBytes, protectedPaths)
        if (victims.isEmpty()) return 0

        var freed = 0L
        var evicted = 0
        for (victim in victims) {
            val file = File(victim.path)
            // Count only what actually went away. A delete can fail — the file may have been opened
            // for a new playback between the scan and here — and reporting it as freed would make the
            // next sweep think it's already under quota.
            if (runCatching { file.delete() }.getOrDefault(false)) {
                freed += victim.sizeBytes
                evicted++
            } else {
                Log.w(TAG, "Could not evict ${file.name}")
            }
        }
        removeEmptyDirectories(cacheDir)
        // Deletions, not attempts. This line is what you read when the cache won't come down to
        // quota, and an attempted count would say the sweep worked while the disk says otherwise.
        Log.i(TAG, "Evicted $evicted of ${victims.size} selected file(s), freed $freed bytes")
        return freed
    }

    // Total bytes currently held, for the settings screen.
    fun usageBytes(cacheDir: File): Long = if (cacheDir.isDirectory) scan(cacheDir).sumOf { it.sizeBytes } else 0

    // Deletes everything, ignoring quota and recency — the "clear cache" action. Still respects
    // protected paths, because clearing the cache mid-playback shouldn't kill the playback.
    fun clear(cacheDir: File, protectedPaths: Set<String> = emptySet()): Long {
        if (!cacheDir.isDirectory) return 0
        var freed = 0L
        scan(cacheDir).filter { it.path !in protectedPaths }.forEach { entry ->
            if (runCatching { File(entry.path).delete() }.getOrDefault(false)) {
                freed += entry.sizeBytes
            } else {
                // The user pressed "Clear now" and the usage figure didn't go to zero. Without this
                // there is nothing anywhere to say which file refused to go.
                Log.w(TAG, "Could not delete ${File(entry.path).name} while clearing the cache")
            }
        }
        removeEmptyDirectories(cacheDir)
        return freed
    }

    // lastModified stands in for last-accessed: Android doesn't expose a reliable atime, and for
    // torrent data the two move together — a file is written to as it's streamed, so the most
    // recently played is also the most recently modified.
    private fun scan(cacheDir: File): List<CachedTorrentFile> =
        cacheDir.walkTopDown()
            .filter { it.isFile }
            .map { CachedTorrentFile(it.absolutePath, it.length(), it.lastModified()) }
            .toList()

    // Torrents create a directory per torrent name; an emptied one is just litter.
    private fun removeEmptyDirectories(root: File) {
        root.walkBottomUp()
            .filter { it.isDirectory && it != root }
            .forEach { dir -> if (dir.listFiles()?.isEmpty() == true) runCatching { dir.delete() } }
    }
}
