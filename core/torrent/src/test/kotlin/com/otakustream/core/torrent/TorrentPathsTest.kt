package com.otakustream.core.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// The paths this guards come out of torrent metadata, which whoever made the torrent controls. The
// interesting cases are the ones that are not obviously an escape when you read them as strings.
class TorrentPathsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val saveDir: File get() = folder.root

    @Test
    fun `an ordinary file inside the directory is allowed`() {
        val resolved = TorrentPaths.containedFile(saveDir, "Some.Release/episode.mkv")

        assertEquals(File(saveDir, "Some.Release/episode.mkv").canonicalFile, resolved)
    }

    @Test
    fun `a file at the top level is allowed`() {
        assertEquals(File(saveDir, "episode.mkv").canonicalFile, TorrentPaths.containedFile(saveDir, "episode.mkv"))
    }

    @Test
    fun `dot dot traversal is refused`() {
        // The shape that would matter: the app's own database sits a few directories up from the
        // torrent cache.
        assertNull(TorrentPaths.containedFile(saveDir, "../databases/otaku_stream.db"))
        assertNull(TorrentPaths.containedFile(saveDir, "a/../../b/escaped.mkv"))
        assertNull(TorrentPaths.containedFile(saveDir, "../../../../etc/passwd"))
    }

    @Test
    fun `traversal that returns inside is allowed`() {
        // `a/../episode.mkv` resolves to a file in the directory, so refusing it would break a
        // legitimate torrent for no gain. Canonicalising is what makes this distinguishable from
        // the escapes above; a string search for ".." would reject both.
        assertEquals(File(saveDir, "episode.mkv").canonicalFile, TorrentPaths.containedFile(saveDir, "a/../episode.mkv"))
    }

    @Test
    fun `an absolute path is rebased under the directory, not honoured`() {
        // Worth pinning because the intuition is the other way round. java.io.File(parent, child)
        // does not let an absolute child replace the parent — it converts the child to a relative
        // path and joins — so "/etc/passwd" resolves to <saveDir>/etc/passwd and is contained. The
        // dangerous reading would be java.nio's Path.resolve, which *does* return the child
        // unchanged when it is absolute. If this code is ever ported to Path, this test fails.
        assertEquals(
            File(saveDir, "etc/passwd").canonicalFile,
            TorrentPaths.containedFile(saveDir, "/etc/passwd"),
        )
        assertEquals(
            File(saveDir, "data/data/com.otakustream/databases/otaku_stream.db").canonicalFile,
            TorrentPaths.containedFile(saveDir, "/data/data/com.otakustream/databases/otaku_stream.db"),
        )
    }

    @Test
    fun `an absolute path that then traverses out is refused`() {
        // The combination is what actually escapes: the leading slash is neutralised, but the `..`
        // segments still resolve.
        assertNull(TorrentPaths.containedFile(saveDir, "/../../etc/passwd"))
    }

    @Test
    fun `a sibling directory sharing a name prefix is refused`() {
        // The reason the separator is appended before the prefix test. Without it, "/…/torrents-evil"
        // starts with "/…/torrents" and would be waved through as a child of it.
        val root = folder.newFolder("torrents")
        val sibling = File(folder.root, "torrents-evil").apply { mkdirs() }
        File(sibling, "payload.mkv").writeText("x")

        assertNull(TorrentPaths.containedFile(root, "../torrents-evil/payload.mkv"))
    }

    @Test
    fun `a symlink pointing outside is refused`() {
        // Canonicalisation is what catches this: the path contains no `..` and reads as an ordinary
        // relative file, but resolves outside the directory.
        val root = folder.newFolder("root")
        val outside = folder.newFolder("outside")
        File(outside, "secret.txt").writeText("x")
        java.nio.file.Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())

        assertNull(TorrentPaths.containedFile(root, "link/secret.txt"))
    }

    @Test
    fun `the directory itself is not a contained file`() {
        // Not a file the reader could ever want, and treating it as contained would mean an empty
        // path resolved to the cache root.
        assertNull(TorrentPaths.containedFile(saveDir, ""))
        assertNull(TorrentPaths.containedFile(saveDir, "."))
    }

    @Test
    fun `containment does not require the file to exist yet`() {
        // libtorrent allocates storage after the handle is added, so the check runs before the file
        // is on disk. A check that only worked for existing files would fail every real playback.
        val resolved = TorrentPaths.containedFile(saveDir, "not/created/yet.mkv")

        assertEquals(File(saveDir, "not/created/yet.mkv").canonicalFile, resolved)
    }
}
