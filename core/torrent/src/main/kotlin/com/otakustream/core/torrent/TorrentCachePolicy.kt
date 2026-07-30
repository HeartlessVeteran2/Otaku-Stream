package com.otakustream.core.torrent

// A file in the torrent cache, reduced to just what the eviction decision needs.
data class CachedTorrentFile(
    val path: String,
    val sizeBytes: Long,
    val lastAccessedEpochMs: Long,
)

// Decides which cached torrent files to delete to get back under quota. Pure, so the policy can be
// tested without a filesystem — the same reason PieceStrategy is separate from the reader.
object TorrentCachePolicy {

    // 2 GiB. Enough for a couple of episodes at a decent bitrate, small enough that it can't quietly
    // consume the device. Torrent data is always re-fetchable, so evicting it costs bandwidth, never
    // the user's own content.
    const val DEFAULT_QUOTA_BYTES = 2L * 1024 * 1024 * 1024

    // Least-recently-accessed first, until the total is back within quota.
    //
    // `protectedPaths` is never evicted: the file currently being played is in the cache and is the
    // largest, most-recently-grown thing in it, so a naive size-based sweep would happily delete what
    // the player is reading. Guarding it by path rather than by recency is deliberate — recency is a
    // heuristic, "is open right now" is a fact.
    fun selectForEviction(
        files: List<CachedTorrentFile>,
        quotaBytes: Long = DEFAULT_QUOTA_BYTES,
        protectedPaths: Set<String> = emptySet(),
    ): List<CachedTorrentFile> {
        val total = files.sumOf { it.sizeBytes }
        if (total <= quotaBytes) return emptyList()

        // Protected files still count toward the total — they are really on disk. They just can't be
        // chosen. That means a quota smaller than what is currently open evicts everything else and
        // then stops, rather than deleting the file being watched.
        val candidates = files.filter { it.path !in protectedPaths }
            .sortedBy { it.lastAccessedEpochMs }

        val evicted = mutableListOf<CachedTorrentFile>()
        var freed = 0L
        for (file in candidates) {
            if (total - freed <= quotaBytes) break
            evicted += file
            freed += file.sizeBytes
        }
        return evicted
    }
}
