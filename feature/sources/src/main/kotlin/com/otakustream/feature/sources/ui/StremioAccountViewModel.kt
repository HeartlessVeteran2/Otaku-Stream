package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.database.library.LibraryRepository
import com.otakustream.core.database.stremio.StremioAccountStore
import com.otakustream.core.sources.stremio.account.StremioAccountClient
import com.otakustream.core.sources.stremio.account.StremioLibraryItem
import com.otakustream.core.sources.stremio.account.stremioLibraryItemFor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.otakustream.core.sources.api.UiMessages

data class StremioAccountUiState(
    val isLoggedIn: Boolean = false,
    val email: String? = null,
    val isBusy: Boolean = false,
    // A pull-to-refresh in particular, as opposed to isBusy, which also covers signing in and
    // pushing. Set only by refresh(), so the pull indicator answers for the pull gesture — and so
    // the content area can tell that a spinner of its own would be saying the same thing twice.
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val library: List<StremioLibraryItem> = emptyList(),
)

// Backs the Stremio account screen: email/password login for an authKey, then a read-only view of
// the user's Stremio library plus a one-tap push of local saves up to their account.
@HiltViewModel
class StremioAccountViewModel @Inject constructor(
    private val accountClient: StremioAccountClient,
    private val accountStore: StremioAccountStore,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StremioAccountUiState())
    val uiState: StateFlow<StremioAccountUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            accountStore.authKey.collect { authKey ->
                _uiState.value = _uiState.value.copy(
                    isLoggedIn = authKey != null,
                    email = accountStore.email,
                )
                if (authKey != null && _uiState.value.library.isEmpty()) refreshLibrary()
            }
        }
    }

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Enter your Stremio email and password.")
            return
        }
        _uiState.value = _uiState.value.copy(isBusy = true, error = null, message = null)
        viewModelScope.launch {
            runCatching { accountClient.login(email.trim(), password) }
                .onSuccess { account ->
                    accountStore.save(account.authKey, account.email)
                    _uiState.value = _uiState.value.copy(isBusy = false)
                }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    _uiState.value = _uiState.value.copy(
                        isBusy = false,
                        error = failure.message ?: "Couldn't sign in to Stremio.",
                    )
                }
        }
    }

    fun logout() {
        // The screen clears on this frame: accountStore.clear() drops its in-memory state before
        // suspending, and the durable wipe runs on the store's own scope, so leaving the screen
        // straight after tapping this can't strand the authKey on disk.
        _uiState.value = _uiState.value.copy(library = emptyList(), message = null, error = null)
        viewModelScope.launch {
            // The store reports whether the authKey actually left the disk. Saying "Signed out"
            // when it did not would be the exact lie this change is about: the credential comes
            // back on the next launch and nothing ever said so.
            if (accountStore.clear()) {
                UiMessages.show("Signed out of Stremio")
            } else {
                _uiState.value = _uiState.value.copy(
                    error = "Signed out on this screen, but the saved sign-in couldn't be removed " +
                        "from storage. It may come back when the app restarts.",
                )
            }
        }
    }

    // Pull-to-refresh. refreshLibrary() is the work; this is the flag that keeps the indicator up
    // for exactly as long as it runs.
    fun refresh() {
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        refreshLibrary()
    }

    fun refreshLibrary() {
        // Not a bare `?: return`. Every way out of this function has to retire the indicator, and
        // signing out between the pull and this line is a real ordering: the gesture would then
        // leave a spinner on screen with nothing running behind it and no way to stop it.
        val authKey = accountStore.authKey.value ?: run {
            _uiState.value = _uiState.value.copy(isRefreshing = false)
            return
        }
        _uiState.value = _uiState.value.copy(isBusy = true, error = null)
        viewModelScope.launch {
            runCatching { accountClient.fetchLibrary(authKey) }
                .onSuccess { items ->
                    _uiState.value = _uiState.value.copy(
                        isBusy = false,
                        isRefreshing = false,
                        library = items.filterNot { it.removed }.sortedBy { it.name.lowercase() },
                    )
                }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    _uiState.value = _uiState.value.copy(
                        isBusy = false,
                        isRefreshing = false,
                        error = failure.message ?: "Couldn't load your Stremio library.",
                    )
                }
        }
    }

    // Push every local save that carries a Stremio "type|id" key up to the account library.
    fun pushLocalLibrary() {
        val authKey = accountStore.authKey.value ?: return
        _uiState.value = _uiState.value.copy(isBusy = true, error = null, message = null)
        viewModelScope.launch {
            runCatching {
                // The remote library is fetched first so every item the account already has can be
                // re-sent as the server's own document rather than as a locally-invented one. That
                // is what keeps a push from resetting watch progress; libraryItemDocument explains
                // the mechanics. stremioLibraryItemFor filters out non-Stremio keys, so only genuine
                // catalog saves are pushed at all.
                val existingById = accountClient.fetchLibrary(authKey).associateBy { it.id }
                val local = libraryRepository.observeLibrary().first()
                val items = local.mapNotNull { entry ->
                    val item = stremioLibraryItemFor(entry.mediaUrl, entry.title, entry.coverUrl)
                        ?: return@mapNotNull null
                    // Keyed off the parsed id rather than re-deriving it from mediaUrl: the same
                    // parse, done once, in the function that owns the format.
                    item.copy(remoteJson = existingById[item.id]?.remoteJson)
                }
                accountClient.putLibraryItems(authKey, items)
                PushOutcome(total = items.size, added = items.count { it.remoteJson == null })
            }.onSuccess { outcome ->
                _uiState.value = _uiState.value.copy(isBusy = false, message = outcome.message())
                refreshLibrary()
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                _uiState.value = _uiState.value.copy(
                    isBusy = false,
                    error = failure.message ?: "Couldn't push to your Stremio library.",
                )
            }
        }
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null, error = null)
    }
}

// What a push actually did, split into the two cases the user cares about. The old message said
// "Pushed N titles" for a run that mostly re-sent rows that were already there — true, but it read
// as though N titles had been changed, which is the impression that made silently resetting their
// watch state so hard to notice.
private data class PushOutcome(val total: Int, val added: Int) {
    private val alreadyThere: Int get() = total - added

    fun message(): String = when {
        total == 0 -> "No Stremio titles to push — saved catalog items sync; local files don't."
        added == 0 -> "Your Stremio library already had all ${titles(total)}; watch progress left as it was."
        alreadyThere == 0 -> "Added ${titles(added)} to your Stremio library."
        else -> "Added ${titles(added)}; the other $alreadyThere kept their watch progress."
    }

    private fun titles(count: Int) = "$count title${if (count == 1) "" else "s"}"
}
