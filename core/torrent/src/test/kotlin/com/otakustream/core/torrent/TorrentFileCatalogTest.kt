package com.otakustream.core.torrent

import org.junit.Assert.assertEquals
import org.junit.Test

private const val HASH_A = "0123456789abcdef0123456789abcdef01234567"
private const val HASH_B = "89abcdef0123456789abcdef0123456789abcdef"

class TorrentFileCatalogTest {

    private fun file(index: Int, path: String, mb: Long) = TorrentFileEntry(index, path, mb * 1024L * 1024L)

    @Test
    fun `a remembered pack comes back in episode order, extras dropped`() {
        val catalog = TorrentFileCatalog()
        catalog.remember(
            HASH_A,
            listOf(
                file(0, "Pack/readme.nfo", 1),
                file(1, "Pack/Show - 10.mkv", 1400),
                file(2, "Pack/Show - 2.mkv", 1400),
            ),
        )

        assertEquals(
            listOf("Pack/Show - 2.mkv", "Pack/Show - 10.mkv"),
            catalog.playableFiles(HASH_A).map { it.path },
        )
    }

    // The hash reaches this class from two directions — the reader stores what the url parsed, and a
    // screen asks with whatever an add-on returned. Add-ons return either case. Storing under one
    // spelling and asking with the other is an empty picker over a pack that is right there.
    @Test
    fun `the same torrent is found whatever case its hash arrives in`() {
        val catalog = TorrentFileCatalog()
        catalog.remember(HASH_A.uppercase(), listOf(file(0, "Show.mkv", 1400)))

        assertEquals(1, catalog.playableFiles(HASH_A).size)
        assertEquals(1, catalog.playableFiles(HASH_A.uppercase()).size)
    }

    // Nothing knows what is in a magnet until something plays it. That is an answer, not a failure,
    // and the caller's job is to show no picker rather than an error.
    @Test
    fun `a torrent nothing has opened lists nothing`() {
        assertEquals(emptyList<TorrentFileEntry>(), TorrentFileCatalog().playableFiles(HASH_B))
    }

    @Test
    fun `a hash that is not a hash is ignored rather than stored`() {
        val catalog = TorrentFileCatalog()
        catalog.remember("not-an-info-hash", listOf(file(0, "Show.mkv", 1400)))

        assertEquals(emptyList<TorrentFileEntry>(), catalog.playableFiles("not-an-info-hash"))
    }

    // The list is a snapshot. The caller hands over a list it may still be building, and a picker
    // rendering from a list that changed underneath it is a crash rather than a wrong row.
    @Test
    fun `the remembered list does not change when the caller's does`() {
        val catalog = TorrentFileCatalog()
        val handedOver = mutableListOf(file(0, "Show - 1.mkv", 1400))
        catalog.remember(HASH_A, handedOver)

        handedOver += file(1, "Show - 2.mkv", 1400)

        assertEquals(1, catalog.playableFiles(HASH_A).size)
    }

    // The other direction of the same isolation. Kotlin's List is read-only, not immutable, so a
    // caller can cast to the backing ArrayList — and handing every reader the same instance means one
    // of them emptying it empties the cache for everyone, for the rest of the process.
    @Test
    fun `what one reader mutates is not what the next one sees`() {
        val catalog = TorrentFileCatalog()
        catalog.remember(HASH_A, listOf(file(0, "Show - 1.mkv", 1400), file(1, "Show - 2.mkv", 1400)))

        @Suppress("UNCHECKED_CAST")
        (catalog.playableFiles(HASH_A) as MutableList<TorrentFileEntry>).clear()

        assertEquals(2, catalog.playableFiles(HASH_A).size)
    }

    // Bounded, so a long session of browsing torrents cannot grow this for the life of the process.
    // Access-ordered, so what is being watched is the last thing dropped — which is the entry a
    // picker is about to ask for.
    @Test
    fun `the least recently used torrent is the one forgotten`() {
        val catalog = TorrentFileCatalog()
        val hashes = (0 until 33).map { n -> "%040x".format(n) }
        hashes.forEach { hash -> catalog.remember(hash, listOf(file(0, "Show.mkv", 1400))) }

        // 33 stored into a map that holds 32: the first is gone and the last is not.
        assertEquals(emptyList<TorrentFileEntry>(), catalog.playableFiles(hashes.first()))
        assertEquals(1, catalog.playableFiles(hashes.last()).size)
    }

    @Test
    fun `reading a torrent keeps it from being the one forgotten`() {
        val catalog = TorrentFileCatalog()
        val hashes = (0 until 32).map { n -> "%040x".format(n) }
        hashes.forEach { hash -> catalog.remember(hash, listOf(file(0, "Show.mkv", 1400))) }

        // The oldest entry, touched — which is what watching it does.
        assertEquals(1, catalog.playableFiles(hashes.first()).size)
        catalog.remember("%040x".format(999), listOf(file(0, "Show.mkv", 1400)))

        assertEquals(
            "the torrent being watched was evicted while an untouched one survived",
            1,
            catalog.playableFiles(hashes.first()).size,
        )
        assertEquals(emptyList<TorrentFileEntry>(), catalog.playableFiles(hashes[1]))
    }
}
