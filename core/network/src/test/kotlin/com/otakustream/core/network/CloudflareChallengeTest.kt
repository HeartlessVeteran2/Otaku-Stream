package com.otakustream.core.network

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

    // cf-mitigated needs no corroboration, and requiring it made one header a single point of
    // failure for every signal.
    //
    // `Server: cloudflare` used to gate the whole function, so a response that lost that header —
    // an enterprise customer overriding it, a future default, a proxy in front — would fail
    // detection even while announcing the challenge outright, and the source would 403 forever with
    // the solver never being asked to run. Cloudflare only ever sends cf-mitigated about its own
    // challenges; there is nothing for the Server header to add.
    //
    // The gate is still right for the body markers below, whose strings ("Just a moment",
    // "challenge-platform") are generic enough to turn up on some other host's page.
    @Test
    fun `cf-mitigated alone is enough, even with no Server header`() {
        assertTrue(isCloudflareChallengeResponse(403, null, "challenge", ""))
        assertTrue(isCloudflareChallengeResponse(503, "AkamaiGHost", "challenge", ""))
        // And on a status that is not 403/503 at all, which the body path never reaches.
        assertTrue(isCloudflareChallengeResponse(200, null, "challenge", ""))
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
