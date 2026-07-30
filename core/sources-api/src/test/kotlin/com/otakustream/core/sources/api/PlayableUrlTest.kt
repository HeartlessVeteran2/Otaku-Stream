package com.otakustream.core.sources.api

import com.otakustream.core.sources.api.PendingPlayback.Provenance
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The pair of tests that matter here are the mirrored ones: the same scheme has to be accepted from
// the user and refused from a source. A policy that got either half wrong would look fine in
// isolation — one breaks on-device playback, the other lets a source read the app's own files.
class PlayableUrlTest {

    @Test
    fun `a source may point at the web`() {
        assertTrue(PlayableUrl.isAllowed("https://cdn.example.test/ep1.mp4", Provenance.SOURCE))
        assertTrue(PlayableUrl.isAllowed("http://mirror.example.test/ep1.m3u8", Provenance.SOURCE))
    }

    @Test
    fun `a source may point at the app's own torrent identity`() {
        // torrent:// is resolved internally by TorrentDataSource and never reaches the filesystem
        // outside the torrent cache, so it is not a way out.
        assertTrue(PlayableUrl.isAllowed("torrent://abcdef0123456789/0", Provenance.SOURCE))
    }

    @Test
    fun `a source may not point at a file on the device`() {
        // The reason this class exists. Nothing stops a source answering getVideoList with a path
        // to app-private storage, and the player would open it.
        assertFalse(PlayableUrl.isAllowed("file:///data/data/com.otakustream/databases/otaku_stream.db", Provenance.SOURCE))
        assertFalse(PlayableUrl.isAllowed("file:///sdcard/Download/anything.mkv", Provenance.SOURCE))
        assertFalse(PlayableUrl.isAllowed("content://com.other.app/private/doc", Provenance.SOURCE))
    }

    @Test
    fun `the user may point at a file on the device`() {
        // The mirror of the case above, and the reason a blanket ban is wrong: on-device playback
        // is a real feature, and this is exactly what the file picker and "Open with" produce.
        assertTrue(PlayableUrl.isAllowed("file:///sdcard/Movies/holiday.mkv", Provenance.USER))
        assertTrue(PlayableUrl.isAllowed("content://media/external/video/media/42", Provenance.USER))
    }

    @Test
    fun `the user may still point at the web`() {
        // A pasted link or a browser hand-off.
        assertTrue(PlayableUrl.isAllowed("https://example.test/ep1.mp4", Provenance.USER))
        assertTrue(PlayableUrl.isAllowed("torrent://abcdef0123456789/0", Provenance.USER))
    }

    @Test
    fun `scheme matching is case-insensitive`() {
        // Schemes are case-insensitive per RFC 3986 and Uri does not normalise them, so a source
        // writing FILE:// must not slip past a lowercase comparison.
        assertFalse(PlayableUrl.isAllowed("FILE:///data/data/com.otakustream/x", Provenance.SOURCE))
        assertFalse(PlayableUrl.isAllowed("Content://com.other.app/x", Provenance.SOURCE))
        assertTrue(PlayableUrl.isAllowed("HTTPS://example.test/ep1.mp4", Provenance.SOURCE))
    }

    @Test
    fun `exotic schemes are refused from a source`() {
        assertFalse(PlayableUrl.isAllowed("javascript:alert(1)", Provenance.SOURCE))
        assertFalse(PlayableUrl.isAllowed("data:video/mp4;base64,AAAA", Provenance.SOURCE))
        assertFalse(PlayableUrl.isAllowed("android_asset://x", Provenance.SOURCE))
    }

    @Test
    fun `a url with no scheme is refused from either side`() {
        // A bare path is how a file reference would arrive if the scheme were simply omitted.
        assertFalse(PlayableUrl.isAllowed("/data/data/com.otakustream/databases/otaku_stream.db", Provenance.SOURCE))
        assertFalse(PlayableUrl.isAllowed("/data/data/com.otakustream/databases/otaku_stream.db", Provenance.USER))
        assertFalse(PlayableUrl.isAllowed("", Provenance.SOURCE))
        assertFalse(PlayableUrl.isAllowed("just-some-text", Provenance.USER))
    }

    @Test
    fun `stash defaults to source provenance`() {
        // A caller that forgets to say gets the restricted treatment. If this ever flipped, every
        // existing source stash would silently gain the local schemes.
        PendingPlayback.stash(Video(url = "https://example.test/a.mp4", quality = ""))

        val stashed = PendingPlayback.consume("https://example.test/a.mp4")

        assertTrue(stashed != null && stashed.provenance == Provenance.SOURCE)
    }
}
