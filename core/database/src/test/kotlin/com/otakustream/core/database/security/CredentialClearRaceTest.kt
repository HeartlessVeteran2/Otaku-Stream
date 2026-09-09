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
// Disk is asserted too, by the sign-out tests, and getting there required a change. The stores used
// to call openEncryptedPrefs themselves, which returns null under Robolectric because the Android
// Keystore is unavailable, so they degraded to in-memory and every disk assertion would have passed
// vacuously. They take a SecurePrefsFactory now; these tests hand them ordinary SharedPreferences,
// which Robolectric does provide. Encryption is the only difference, and it is not what these
// orderings are about.
//
// It is the sign-out tests and not the race tests because of how the queue is ordered — the race
// tests say so where they would otherwise have asserted it. Disk matters on its own: memory decides
// what the screen shows now, disk decides whether you are still signed in after a restart, and the
// bug this guards produced exactly that mismatch.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CredentialClearRaceTest {

    private val dispatcher = StandardTestDispatcher()

    // Plain SharedPreferences, one file per store, cleared between tests by Robolectric.
    private val prefs = SecurePrefsFactory { fileName ->
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences(fileName, android.content.Context.MODE_PRIVATE)
    }

    private fun stremioStore() = StremioAccountStoreImpl(prefs, dispatcher)

    private fun tokenStore() = EncryptedTokenStore(prefs, dispatcher)

    private fun storedStremioKey(): String? =
        prefs.open(StremioAccountStoreImpl.PREFS_FILE_NAME)?.getString("stremio_auth_key", null)

    private fun storedToken(): String? =
        prefs.open(EncryptedTokenStore.PREFS_FILE_NAME)?.getString("anilist_access_token", null)

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
        // No disk assertion here, deliberately, and the reason is worth writing down because the
        // assertion looks so obviously right. save()'s write queues on the same serialized scope
        // *behind* clear()'s queued half — clear() was created first, by the UNDISPATCHED async —
        // so the removal always runs before the write. The broken code therefore also ends with
        // key-b on disk, and the assertion passes either way. I checked, by deleting the guard in
        // clear() and running this test with the two memory assertions above removed: green.
        //
        // Memory is what distinguishes fixed from broken here. Disk is pinned by the ordinary
        // sign-out below, where nothing races the write and a clear that never reaches disk shows.
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
        assertNull("an ordinary sign-out must reach disk too", storedStremioKey())
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

        // Same ordering as the Stremio race above, so the same rule: memory is the assertion that
        // can fail, and disk is pinned by the ordinary sign-out below.
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
        assertNull("a revoked token left on disk is read back next launch", storedToken())
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
