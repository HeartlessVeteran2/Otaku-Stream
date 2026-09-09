package com.otakustream.core.torrent

import javax.inject.Inject
import javax.inject.Singleton

// What is inside a torrent, remembered from the one moment it becomes knowable.
//
// A magnet carries no file list at all. It exists for the first time inside TorrentFileReader.open,
// after the metadata arrives — and that is deep in a Media3 DataSource, on a background thread, with
// no route back to a screen. The player needs it later and elsewhere: a season pack opens on one
// episode and the viewer has to be able to reach the other eleven.
//
// Safe to remember, because a torrent's file list is fixed by its info-hash. The same hash always
// describes the same files, so an entry can never go stale — only unused.
@Singleton
class TorrentFileCatalog @Inject constructor() {

    // Bounded, and access-ordered so the torrent being watched is the last one dropped. A file list
    // is small, but the map would otherwise grow for the life of the process — every torrent ever
    // opened, held because it might be asked about again.
    //
    // Guarded by the map itself rather than made concurrent: LinkedHashMap's access-order eviction
    // mutates on *read*, so a plain ConcurrentHashMap would not give the ordering and a get() would
    // still need the lock.
    private val byInfoHash = object : LinkedHashMap<String, List<TorrentFileEntry>>(
        /* initialCapacity = */ 16,
        /* loadFactor = */ 0.75f,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<TorrentFileEntry>>) =
            size > MAX_REMEMBERED_TORRENTS
    }

    // Normalized on the way in and out, so a caller holding an add-on's upper-case hash finds what
    // the reader stored under the lower-case one. TorrentUri makes the same argument about history
    // keys, and this is the same hash arriving from the same places.
    fun remember(infoHash: String, files: List<TorrentFileEntry>) {
        val key = TorrentUri.normalizeInfoHash(infoHash) ?: return
        // Filtered and ordered on the way in, not on every read, and that is about memory more than
        // work: a torrent's file list is not bounded by its episode count. A pack ships an .nfo per
        // episode, cover art, sample clips and sidecar subtitles beside them, and a large one can run
        // to thousands of entries — held for 32 torrents, that is a lot of strings kept for a list
        // that will never show any of them.
        //
        // It is also the same snapshot the old defensive copy gave: listPlayableFiles builds a new
        // list, so a caller still mutating the one it handed over cannot reach this.
        val playable = TorrentVideoFiles.listPlayableFiles(files)
        synchronized(byInfoHash) { byInfoHash[key] = playable }
    }

    // The files a picker may offer, already ordered. Empty when this torrent has never been opened
    // in this process — which is a real answer and not an error: nothing knows a magnet's contents
    // until something plays it.
    fun playableFiles(infoHash: String): List<TorrentFileEntry> {
        val key = TorrentUri.normalizeInfoHash(infoHash) ?: return emptyList()
        return synchronized(byInfoHash) { byInfoHash[key] } ?: emptyList()
    }

    private companion object {
        // Enough to cover a session of browsing without pinning anything meaningful: each entry is a
        // few hundred short strings.
        const val MAX_REMEMBERED_TORRENTS = 32
    }
}
