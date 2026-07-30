package com.otakustream.feature.tracking

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// This is the whole of the defence against a forged sign-in, so the ways it could be quietly wrong
// are worth naming: accepting a redirect with no state, accepting one when no sign-in is pending,
// accepting the same redirect twice, or generating a value predictable enough to guess.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AniListAuthStateTest {

    private lateinit var authState: AniListAuthState

    @Before
    fun setUp() {
        authState = AniListAuthState(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `the nonce it issues is the one it accepts`() {
        val nonce = authState.begin()

        assertTrue(authState.consume(nonce))
    }

    @Test
    fun `a redirect carrying a different state is refused`() {
        authState.begin()

        assertFalse(authState.consume("some-other-value"))
    }

    @Test
    fun `a redirect with no state at all is refused`() {
        // The shape a forged redirect actually takes. An attacker firing
        // otakustream://anilist-auth#access_token=... has no reason to invent a state, and this is
        // the case a null-tolerant comparison would wave through.
        authState.begin()

        assertFalse(authState.consume(null))
        assertFalse(authState.consume(""))
    }

    @Test
    fun `nothing is accepted when no sign-in is pending`() {
        // The app was not in the middle of signing in, so every redirect is unsolicited — including
        // one that guessed a plausible-looking value, and including null against null.
        assertFalse(authState.consume(null))
        assertFalse(authState.consume(""))
        assertFalse(authState.consume("anything"))
    }

    @Test
    fun `a nonce is single use`() {
        // Re-opening the redirect URL, from history or a saved link, must not sign in again.
        val nonce = authState.begin()

        assertTrue(authState.consume(nonce))
        assertFalse(authState.consume(nonce))
    }

    @Test
    fun `starting a second sign-in invalidates the first`() {
        // Only the attempt the user most recently initiated should be able to complete.
        val first = authState.begin()
        val second = authState.begin()

        assertFalse(authState.consume(first))
        assertTrue(authState.consume(second))
    }

    @Test
    fun `a rejected redirect does not invalidate the sign-in in progress`() {
        // Anyone who can get a link opened can fire a forged redirect at this app while the user is
        // still on the consent page. If a failed check consumed the pending nonce, that would cancel
        // the real sign-in — a denial of service on signing in at all, available to anyone. So a
        // rejection must leave the pending attempt alone.
        val nonce = authState.begin()

        assertFalse(authState.consume("forged"))
        assertFalse(authState.consume(null))

        assertTrue("the genuine redirect must still be accepted", authState.consume(nonce))
    }

    @Test
    fun `each nonce is distinct and long enough not to be guessed`() {
        val nonces = (1..50).map {
            authState.begin().also { value -> authState.consume(value) }
        }

        assertEquals("every nonce must be unique", nonces.size, nonces.toSet().size)
        nonces.forEach { nonce ->
            // 32 random bytes, hex-encoded.
            assertEquals(64, nonce.length)
            assertTrue("expected hex, got '$nonce'", nonce.all { it in "0123456789abcdef" })
        }
        assertNotEquals(nonces.first(), nonces.last())
    }

    @Test
    fun `a pending nonce survives the app being killed mid-sign-in`() {
        // The browser is another process and Android may kill this one while the user is on the
        // consent page. Held only in memory, the nonce would be gone exactly when the genuine
        // redirect arrived — turning a security control into an intermittent sign-in failure.
        val nonce = authState.begin()

        val afterRestart = AniListAuthState(ApplicationProvider.getApplicationContext())

        assertTrue(afterRestart.consume(nonce))
    }

    @Test
    fun `the authorize url carries the nonce`() {
        // If the state never reaches AniList it is never echoed back, and every genuine redirect
        // would then fail the check — the failure mode where the fix looks like a broken sign-in.
        val nonce = authState.begin()

        assertTrue(AniListAuth.authorizeUrl(nonce).contains("state=$nonce"))
    }
}
