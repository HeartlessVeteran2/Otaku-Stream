package com.otakustream.feature.sources

import com.otakustream.core.sources.api.SourceHttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class SourceFailureTest {

    @Test
    fun `http failure keeps its status code`() {
        assertEquals(
            FailureReason.Http(403),
            SourceHttpException(403, "https://example.test/catalog").toFailureReason(),
        )
    }

    // The whole point of the change: a 403 and a 503 must not read the same, because one means
    // "use a different source" and the other means "try again later".
    @Test
    fun `blocked and unavailable read differently`() {
        assertEquals("blocked this request (HTTP 403)", FailureReason.Http(403).describe())
        assertEquals("server error (HTTP 503)", FailureReason.Http(503).describe())
        assertEquals("rate-limited us (HTTP 429)", FailureReason.Http(429).describe())
        assertEquals("endpoint not found (HTTP 404)", FailureReason.Http(404).describe())
    }

    @Test
    fun `unrecognised status still names the code`() {
        assertEquals("unexpected response (HTTP 418)", FailureReason.Http(418).describe())
    }

    @Test
    fun `no dns is reported as offline rather than an unknown error`() {
        assertEquals(FailureReason.Offline, UnknownHostException("nope.test").toFailureReason())
    }

    @Test
    fun `tls failures are their own category`() {
        assertEquals(FailureReason.Tls, SSLHandshakeException("bad cert").toFailureReason())
    }

    // SocketTimeoutException extends InterruptedIOException, so a `when` that tested the supertype
    // first would silently swallow the more specific case. Both map to Timeout here, but the order
    // matters the moment they stop agreeing — this pins it.
    @Test
    fun `socket timeout is classified as a timeout`() {
        assertTrue(SocketTimeoutException("read timed out").toFailureReason() is FailureReason.Timeout)
        assertTrue(InterruptedIOException("interrupted").toFailureReason() is FailureReason.Timeout)
    }

    @Test
    fun `timeout describes its budget only when one is known`() {
        assertEquals("timed out after 15s", FailureReason.Timeout(15_000).describe())
        assertEquals("timed out", FailureReason.Timeout(0).describe())
    }

    @Test
    fun `an unclassifiable error carries its message`() {
        val reason = IllegalStateException("extension threw: undefined is not a function").toFailureReason()
        assertEquals(FailureReason.Unknown("extension threw: undefined is not a function"), reason)
        assertEquals("extension threw: undefined is not a function", reason.describe())
    }

    // A blank or absent message must not render as an empty clause dangling after the source name
    // ("AnimeKai — ").
    @Test
    fun `an error with no message still says something`() {
        assertEquals("failed for an unknown reason", IOException().toFailureReason().describe())
        assertEquals("failed for an unknown reason", IllegalStateException("   ").toFailureReason().describe())
    }

    @Test
    fun `a single failure is named rather than counted`() {
        val failures = listOf(SourceFailure(1L, "AnimeKai", FailureReason.Http(403)))
        assertEquals("AnimeKai couldn't load", failures.headline())
    }

    @Test
    fun `several failures are counted`() {
        val failures = listOf(
            SourceFailure(1L, "AnimeKai", FailureReason.Http(403)),
            SourceFailure(2L, "Torrentio", FailureReason.Timeout(15_000)),
        )
        assertEquals("2 sources couldn't load", failures.headline())
        assertEquals("AnimeKai — blocked this request (HTTP 403)", failures[0].describe())
        assertEquals("Torrentio — timed out after 15s", failures[1].describe())
    }

    // When the device is offline every source fails identically; listing them per-source would be
    // noise repeating one fact, so the banner collapses to the cause instead.
    @Test
    fun `all-offline is detected so the banner can collapse`() {
        val offline = listOf(
            SourceFailure(1L, "AnimeKai", FailureReason.Offline),
            SourceFailure(2L, "Torrentio", FailureReason.Offline),
        )
        assertTrue(offline.allOffline())

        val mixed = offline.dropLast(1) + SourceFailure(2L, "Torrentio", FailureReason.Http(500))
        assertFalse(mixed.allOffline())
    }

    @Test
    fun `no failures is not offline`() {
        assertFalse(emptyList<SourceFailure>().allOffline())
        assertEquals("", emptyList<SourceFailure>().headline())
    }
}
