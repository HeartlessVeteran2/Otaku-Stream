package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.database.library.LibraryRepository
import com.otakustream.core.database.stremio.StremioAccountStore
import com.otakustream.core.sources.stremio.account.StremioAccountClient
import com.otakustream.core.sources.stremio.account.StremioLibraryItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StremioAccountUiState(
    val isLoggedIn: Boolean = false,
    val email: String? = null,
    val isBusy: Boolean = false,
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
        accountStore.clear()
        _uiState.value = _uiState.value.copy(library = emptyList(), message = null, error = null)
    }

    fun refreshLibrary() {
        val authKey = accountStore.authKey.value ?: return
        _uiState.value = _uiState.value.copy(isBusy = true, error = null)
        viewModelScope.launch {
            runCatching { accountClient.fetchLibrary(authKey) }
                .onSuccess { items ->
                    _uiState.value = _uiState.value.copy(
                        isBusy = false,
                        library = items.filterNot { it.removed }.sortedBy { it.name.lowercase() },
                    )
                }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    _uiState.value = _uiState.value.copy(
                        isBusy = false,
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
                val local = libraryRepository.observeLibrary().first()
                val items = local.mapNotNull { entry -> entry.toStremioLibraryItem() }
                accountClient.putLibraryItems(authKey, items)
                items.size
            }.onSuccess { count ->
                _uiState.value = _uiState.value.copy(
                    isBusy = false,
                    message = if (count == 0) {
                        "No Stremio titles to push — saved catalog items sync; local files don't."
                    } else {
                        "Pushed $count title${if (count == 1) "" else "s"} to your Stremio library."
                    },
                )
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

// Local saves store Stremio catalog items as "type|id" (no URL scheme). Only those map onto a
// Stremio library item; anything with a real URL (scripted/mangayomi sources, local files) is skipped.
private fun com.otakustream.core.database.library.LibraryEntry.toStremioLibraryItem(): StremioLibraryItem? {
    if (mediaUrl.contains("://") || !mediaUrl.contains("|")) return null
    val type = mediaUrl.substringBefore("|")
    val id = mediaUrl.substringAfter("|")
    if (type.isBlank() || id.isBlank()) return null
    return StremioLibraryItem(id = id, type = type, name = title, poster = coverUrl, removed = false)
}
