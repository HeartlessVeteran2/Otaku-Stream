package com.otakustream.core.database.security

import androidx.test.core.app.ApplicationProvider
import com.otakustream.core.database.stremio.StremioAccountStoreImpl
import com.otakustream.core.database.tracking.EncryptedTokenStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// What a sign-out is allowed to do to a sign-in that beat it.
//
// Both credential stores drop their in-memory state twice on a clear: once on the calling frame, so
// the flow turns over immediately, and once on the queued half, where it is ordered after the
// initial disk load that would otherwise restore the old credential. The second drop was
// unconditional, so a sign-in landing between the two had its brand-new credential wiped by a body
// that runs afterwards — the screen went back to signed-out with no explanation, and stayed that
// way until the next launch.
//
// The window is between clear()'s first statement and its queued half reaching the dispatcher,
// which is unreachable with a real one. Both stores take their dispatcher injected now, so the test
// owns that queue and can put a sign-in exactly where the bug lives.
//
// SCOPE, stated because it is easy to misread these as covering more than they do: only the
// in-memory half is asserted. openEncryptedPrefs returns null under Robolectric — the Android
// Keystore is not available — so both stores degrade to in-memory here and every disk operation is
// a no-op. The disk half of these races (a superseded clear removing the preference a sign-in had
// just written) cannot be exercised in this environment at all. The in-memory half is the half the
// UI observes: `authKey` and `token` are the StateFlows every screen binds "am I signed in" to.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CredentialClearRaceTest {

    private val dispatcher = StandardTestDispatcher()

    private fun stremioStore() =
        StremioAccountStoreImpl(ApplicationProvider.getApplicationContext(), dispatcher)

    private fun tokenStore() =
        EncryptedTokenStore(ApplicationProvider.getApplicationContext(), dispatcher)

    @Test
    fun `a Stremio sign-in that lands during a sign-out survives it`() = runTest(dispatcher) {
        val store = stremioStore()
        store.save("key-a", "a@example.com")
        advanceUntilIdle()

        // UNDISPATCHED, and that is the whole trick. clear()'s calling frame — the synchronous drop
        // and the snapshot — runs inline, right now, and stops at the await on its queued half,
        // which is left sitting unrun on the dispatcher. That gap is where the bug lives.
        //
        // runCurrent() cannot produce it: it drains everything queued at the current virtual time,
        // clear()'s own queued half included, so the sign-in below would land after the race rather
        // than inside it. My first version of this test did exactly that and passed against the
        // broken code.
        val signingOut = async(start = CoroutineStart.UNDISPATCHED) { store.clear() }
        store.save("key-b", "b@example.com")
        advanceUntilIdle()
        signingOut.await()

        assertEquals("the sign-in must outlive the sign-out it raced", "key-b", store.authKey.value)
        assertEquals("b@example.com", store.email)
    }

    // The counterpart: an ordinary sign-out still signs out, so the guard above cannot be satisfied
    // by simply never clearing.
    //
    // It pins less than it looks like it does, and the difference is worth writing down. The
    // synchronous drop on clear()'s calling frame is what empties the flow here, so this passes with
    // the queued drop removed entirely — I checked. The queued drop exists to undo the *initial disk
    // load* restoring the old credential after that first drop, and with openEncryptedPrefs
    // returning null under Robolectric there is no load to undo. That path has no test and cannot
    // have one here.
    @Test
    fun `a Stremio sign-out with nothing after it still signs out`() = runTest(dispatcher) {
        val store = stremioStore()
        store.save("key-a", "a@example.com")
        advanceUntilIdle()

        store.clear()
        advanceUntilIdle()

        assertNull(store.authKey.value)
        assertNull(store.email)
    }

    @Test
    fun `an AniList sign-in that lands during a sign-out survives it`() = runTest(dispatcher) {
        val store = tokenStore()
        store.save("token-a")
        advanceUntilIdle()

        val signingOut = async(start = CoroutineStart.UNDISPATCHED) { store.clear() }
        store.save("token-b")
        advanceUntilIdle()
        signingOut.await()

        assertEquals("token-b", store.token.value)
    }

    @Test
    fun `an AniList sign-out with nothing after it still signs out`() = runTest(dispatcher) {
        val store = tokenStore()
        store.save("token-a")
        advanceUntilIdle()

        store.clear()
        advanceUntilIdle()

        assertNull(store.token.value)
    }

    // clearIfCurrent is the path a rejected request takes: a 401 revokes the token it was sent
    // with. Requests outlive their token, so the one being revoked may already have been replaced —
    // and revoking then would sign the user out moments after they signed in, for a rejection that
    // was never about the new credential.
    @Test
    fun `clearIfCurrent leaves a token that has since been replaced`() = runTest(dispatcher) {
        val store = tokenStore()
        store.save("token-a")
        advanceUntilIdle()
        store.save("token-b")
        advanceUntilIdle()

        val revoked = store.clearIfCurrent("token-a")
        advanceUntilIdle()

        assertEquals("token-b", store.token.value)
        assertTrue("a stale revocation should report that it did nothing", !revoked)
    }
}
