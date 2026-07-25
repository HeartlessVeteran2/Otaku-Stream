package com.otakustream.core.sources.scripting.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareChallengeTest {

    @Test
    fun `non-cloudflare responses are never challenges`() {
        assertFalse(isCloudflareChallengeResponse(403, "nginx", null, "challenge-platform"))
        assertFalse(isCloudflareChallengeResponse(503, null, null, "Just a moment"))
    }

    @Test
    fun `cf-mitigated challenge header is a challenge regardless of body`() {
        assertTrue(isCloudflareChallengeResponse(403, "cloudflare", "challenge", ""))
    }

    @Test
    fun `bare 503 from cloudflare with no marker is an origin error, not a challenge`() {
        // The old false positive: a genuinely-down origin served through Cloudflare returns 503.
        assertFalse(isCloudflareChallengeResponse(503, "cloudflare", null, "<html>error 521</html>"))
    }

    @Test
    fun `403 or 503 with a body challenge marker is a challenge`() {
        assertTrue(isCloudflareChallengeResponse(403, "cloudflare", null, "<script>window._cf_chl_opt"))
        assertTrue(isCloudflareChallengeResponse(503, "cloudflare", null, "Just a moment..."))
        assertTrue(isCloudflareChallengeResponse(403, "cloudflare", null, "id=\"challenge-platform\""))
    }

    @Test
    fun `200 without cf-mitigated is not treated as a challenge`() {
        // We don't buffer 200 bodies, so a plain 200 is passed through.
        assertFalse(isCloudflareChallengeResponse(200, "cloudflare", null, ""))
    }

    @Test
    fun `cookie parser tolerates whitespace, equals in value, and malformed pairs`() {
        assertEquals(
            listOf("cf_clearance" to "abc.def", "sess" to "x=y"),
            parseCookiePairs("  cf_clearance=abc.def; sess=x=y ; =orphan; nonsense"),
        )
        assertEquals(emptyList<Pair<String, String>>(), parseCookiePairs(""))
    }
}
