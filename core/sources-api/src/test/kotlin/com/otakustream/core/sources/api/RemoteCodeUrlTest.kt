package com.otakustream.core.sources.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The rule this pins is easy to weaken by accident, because the app permits cleartext everywhere
// else and every instinct built by the rest of the codebase says "http is fine here". It isn't, for
// code: a script fetched over http can be replaced in flight by anyone on the path, and the app
// then runs it.
class RemoteCodeUrlTest {

    @Test
    fun `https is allowed`() {
        assertTrue(RemoteCodeUrl.isAllowed("https://example.test/source.js"))
        assertTrue(RemoteCodeUrl.isAllowed("https://raw.githubusercontent.com/u/r/main/index.json"))
    }

    @Test
    fun `plain http from the network is refused`() {
        assertFalse(RemoteCodeUrl.isAllowed("http://example.test/source.js"))
        assertFalse(RemoteCodeUrl.isAllowed("http://192.168.1.10/source.js"))
    }

    @Test
    fun `loopback over http is allowed so a source can be developed locally`() {
        // Without this the documented way to write a source — serve the file from your own machine —
        // is the one workflow the rule breaks.
        assertTrue(RemoteCodeUrl.isAllowed("http://localhost:8000/source.js"))
        assertTrue(RemoteCodeUrl.isAllowed("http://127.0.0.1:8000/source.js"))
        assertTrue(RemoteCodeUrl.isAllowed("http://[::1]:8000/source.js"))
        // The emulator's alias for the host machine's loopback.
        assertTrue(RemoteCodeUrl.isAllowed("http://10.0.2.2:8000/source.js"))
    }

    @Test
    fun `a userinfo prefix cannot disguise a remote host as loopback`() {
        // The reason the check is on the parsed host rather than a substring of the URL. To a human
        // skimming, this reads as 127.0.0.1; the host is evil.test, and that is where the body would
        // come from.
        assertFalse(RemoteCodeUrl.isAllowed("http://127.0.0.1@evil.test/source.js"))
        assertFalse(RemoteCodeUrl.isAllowed("http://localhost@evil.test/source.js"))
    }

    @Test
    fun `a host that merely contains localhost is not loopback`() {
        assertFalse(RemoteCodeUrl.isAllowed("http://localhost.evil.test/source.js"))
        assertFalse(RemoteCodeUrl.isAllowed("http://notlocalhost/source.js"))
        assertFalse(RemoteCodeUrl.isAllowed("http://127.0.0.1.evil.test/source.js"))
    }

    @Test
    fun `the scheme is matched case-insensitively`() {
        // Schemes are case-insensitive per RFC 3986, so a link written HTTPS:// must not be refused
        // and one written HTTP:// must not slip through.
        assertTrue(RemoteCodeUrl.isAllowed("HTTPS://example.test/source.js"))
        assertFalse(RemoteCodeUrl.isAllowed("HTTP://example.test/source.js"))
        assertTrue(RemoteCodeUrl.isAllowed("http://LOCALHOST:8000/source.js"))
    }

    @Test
    fun `non-http schemes are refused`() {
        // file:// would read code off the device, and a content:// URI could be handed in by another
        // app. Neither is a thing the installer should accept.
        assertFalse(RemoteCodeUrl.isAllowed("file:///sdcard/source.js"))
        assertFalse(RemoteCodeUrl.isAllowed("content://com.other.app/source.js"))
        assertFalse(RemoteCodeUrl.isAllowed("javascript:alert(1)"))
        assertFalse(RemoteCodeUrl.isAllowed("ftp://example.test/source.js"))
    }

    @Test
    fun `malformed input is refused rather than throwing`() {
        // These reach the check straight from a text field the user pastes into.
        assertFalse(RemoteCodeUrl.isAllowed(""))
        assertFalse(RemoteCodeUrl.isAllowed("   "))
        assertFalse(RemoteCodeUrl.isAllowed("not a url"))
        assertFalse(RemoteCodeUrl.isAllowed("example.test/source.js"))
        assertFalse(RemoteCodeUrl.isAllowed("https://exa mple.test/source.js"))
    }

    @Test
    fun `surrounding whitespace does not change the verdict`() {
        // Pasted links routinely carry a trailing newline.
        assertTrue(RemoteCodeUrl.isAllowed("  https://example.test/source.js\n"))
        assertFalse(RemoteCodeUrl.isAllowed("  http://example.test/source.js\n"))
    }

    @Test
    fun `require throws for a refused url and is silent for an allowed one`() {
        RemoteCodeUrl.require("https://example.test/source.js", "A source script")

        val error = runCatching { RemoteCodeUrl.require("http://example.test/source.js", "A source script") }
            .exceptionOrNull()

        assertTrue(error is java.io.IOException)
        // The message has to name the thing and the fix, because it is shown to someone who just
        // pasted a link and needs to know what to do about it.
        assertTrue(error!!.message!!.contains("A source script"))
        assertTrue(error.message!!.contains("https"))
    }
}
