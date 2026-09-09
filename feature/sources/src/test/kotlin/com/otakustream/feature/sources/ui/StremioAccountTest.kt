package com.otakustream.feature.sources.ui

import com.otakustream.core.database.library.LibraryEntry
import com.otakustream.core.database.library.LibraryRepository
import com.otakustream.core.database.library.RemovedHistoryEntry
import com.otakustream.core.database.library.WatchHistoryEntry
import com.otakustream.core.database.stremio.StremioAccountStore
import com.otakustream.core.sources.stremio.account.StremioAccount
import com.otakustream.core.sources.stremio.account.StremioAccountClient
import com.otakustream.core.sources.stremio.account.StremioLibraryItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The Stremio account screen's ordering rules.
//
// This file has produced five review findings across two rounds — two of them putting one account's
// data in front of another — and had no test at all, because it needed a Keystore-backed store and
// a live network client to construct. Both are interfaces now, so the orderings that caused those
// findings can be written down as tests instead of as comments.
//
// Every one of these fails against the code as it was before the fixes.
@OptIn(ExperimentalCoroutinesApi::class)
class StremioAccountTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // A response fetched for one account must not land after the user has signed out of it. The
    // failure this guards is not just a wrong screen: the sign-in collector only fetches when
    // `library` is empty, so a stale non-empty one means the next account's library is never
    // requested at all, and their screen shows the previous account's saves indefinitely.
    @Test
    fun `a library response that outlives its account is discarded`() = runTest(dispatcher) {
        val store = FakeStore(initialKey = "key-a")
        val client = FakeClient()
        val viewModel = viewModel(client, store)
        advanceUntilIdle()

        // The load for account A is in flight when the user signs out.
        assertTrue("the sign-in collector should have started a load", client.libraryRequested)
        store.clear()
        advanceUntilIdle()
        client.library.complete(listOf(item("a-only", "Account A's show")))
        advanceUntilIdle()

        assertTrue(
            "account A's titles must not survive the sign-out",
            viewModel.uiState.value.library.isEmpty(),
        )
    }

    // The other half of that sign-out, and its own P1: cancelling the load means the coroutine
    // never reaches the handler that clears isBusy, and the sign-in button is bound to !isBusy —
    // so signing out mid-load handed you a logged-out screen you could not sign back in on.
    @Test
    fun `signing out during a load leaves the sign-in form usable`() = runTest(dispatcher) {
        val store = FakeStore(initialKey = "key-a")
        val viewModel = viewModel(FakeClient(), store)
        advanceUntilIdle()
        assertTrue("the load should be running", viewModel.uiState.value.isBusy)

        viewModel.logout()
        advanceUntilIdle()

        assertFalse("isBusy must not outlive the request it belonged to", viewModel.uiState.value.isBusy)
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    // A push is fetch, then upload, then refresh. A pull landing in the middle started a second
    // fetch whose response could arrive after the upload and overwrite the pushed library with the
    // state from before it — and whose completion cleared isBusy while the push was still running.
    @Test
    fun `a pull is refused while a push is running`() = runTest(dispatcher) {
        val store = FakeStore(initialKey = "key-a")
        val client = FakeClient()
        val viewModel = viewModel(client, store)
        advanceUntilIdle()
        client.library.complete(emptyList())
        advanceUntilIdle()

        client.reset()
        viewModel.pushLocalLibrary()
        runCurrent()
        assertTrue("the push should be running", viewModel.uiState.value.isBusy)
        val fetchesDuringPush = client.libraryFetches

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(
            "a pull during a push must not start a second fetch",
            fetchesDuringPush,
            client.libraryFetches,
        )
        assertFalse(
            "and must not raise an indicator it will never lower",
            viewModel.uiState.value.isRefreshing,
        )

        // Let the push finish. Left suspended in the fake it is an abandoned viewModelScope job —
        // not a child of the test scope, so nothing here would fail on it, which is exactly what
        // makes it the kind of leak that surfaces later as another test timing out.
        client.library.complete(emptyList())
        advanceUntilIdle()
    }

    private fun viewModel(client: StremioAccountClient, store: StremioAccountStore) =
        StremioAccountViewModel(
            accountClient = client,
            accountStore = store,
            libraryRepository = EmptyLibraryRepository,
        )

    private fun item(id: String, name: String) = StremioLibraryItem(
        id = id,
        type = "series",
        name = name,
        poster = null,
        removed = false,
        remoteJson = null,
    )

    private class FakeStore(initialKey: String?) : StremioAccountStore {
        private val _authKey = MutableStateFlow(initialKey)
        override val authKey: StateFlow<String?> = _authKey.asStateFlow()
        override var email: String? = "someone@example.com"
            private set

        override fun save(authKey: String, email: String?) {
            this.email = email
            _authKey.value = authKey
        }

        override suspend fun clear(): Boolean {
            email = null
            _authKey.value = null
            return true
        }
    }

    private class FakeClient : StremioAccountClient {
        // Completed by the test, so a response can be held open across a sign-out.
        var library = CompletableDeferred<List<StremioLibraryItem>>()
            private set
        var libraryFetches = 0
            private set
        val libraryRequested: Boolean get() = libraryFetches > 0

        fun reset() {
            library = CompletableDeferred()
            libraryFetches = 0
        }

        override suspend fun login(email: String, password: String) =
            StremioAccount(authKey = "key-b", email = email)

        override suspend fun fetchLibrary(authKey: String): List<StremioLibraryItem> {
            libraryFetches++
            return library.await()
        }

        override suspend fun putLibraryItems(authKey: String, items: List<StremioLibraryItem>) = Unit
    }

    private object EmptyLibraryRepository : LibraryRepository {
        override fun observeLibrary(): Flow<List<LibraryEntry>> = flowOf(emptyList())
        override fun observeInLibrary(mediaUrl: String): Flow<Boolean> = flowOf(false)
        override fun observeStatus(mediaUrl: String): Flow<String?> = flowOf(null)
        override suspend fun addIfAbsent(entry: LibraryEntry): Boolean = false
        override suspend fun remove(mediaUrl: String) = Unit
        override suspend fun removeAndReturn(mediaUrl: String): LibraryEntry? = null
        override suspend fun setStatus(mediaUrl: String, status: String) = Unit
        override fun observeHistory(): Flow<List<WatchHistoryEntry>> = flowOf(emptyList())
        override fun observeWatchedEpisodeUrls(mediaUrl: String): Flow<List<String>> = flowOf(emptyList())
        override suspend fun recordWatch(entry: WatchHistoryEntry) = Unit
        override suspend fun lastTitleFor(mediaUrl: String): String? = null
        override suspend fun removeHistoryEntryAndReturn(id: Long): RemovedHistoryEntry? = null
        override suspend fun restoreHistoryEntry(removed: RemovedHistoryEntry): Boolean = false
        override suspend fun clearHistory() = Unit
    }
}
