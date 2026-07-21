package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.sources.mangayomi.MangayomiExtensionInstaller
import com.otakustream.core.sources.mangayomi.repo.MangayomiExtensionListing
import com.otakustream.core.sources.mangayomi.repo.MangayomiRepoClient
import com.otakustream.core.sources.mangayomi.repo.MangayomiRepoPrefs
import com.otakustream.feature.sources.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class MangayomiExtensionsUiState(
    val isLoading: Boolean = false,
    val listings: List<MangayomiExtensionListing> = emptyList(),
    // ids of installed sources — a listing is installed when its id is among them.
    val installedIds: Set<Long> = emptySet(),
    val installingId: Long? = null,
    val repoUrl: String = "",
    val error: String? = null,
)

// Backs the "AnymeX extensions" screen: browse a Mangayomi anime_index.json repo and install/
// uninstall JS extensions one tap. Installed extensions register as VideoSources, so they flow
// straight into the source picker/search — no extra wiring here.
@HiltViewModel
class MangayomiExtensionsViewModel @Inject constructor(
    private val repoClient: MangayomiRepoClient,
    private val repoPrefs: MangayomiRepoPrefs,
    private val installer: MangayomiExtensionInstaller,
    private val sourceRepository: SourceRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MangayomiExtensionsUiState(repoUrl = repoPrefs.repoUrl))
    val uiState: StateFlow<MangayomiExtensionsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
        viewModelScope.launch {
            sourceRepository.observeSources().collect { sources ->
                _uiState.value = _uiState.value.copy(installedIds = sources.map { it.id }.toSet())
            }
        }
    }

    fun load() {
        loadJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        loadJob = viewModelScope.launch {
            runCatching { repoClient.fetch() }
                .onSuccess { listings -> _uiState.value = _uiState.value.copy(listings = listings) }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    _uiState.value = _uiState.value.copy(error = failure.message ?: "Failed to load extension repo")
                }
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun onRepoUrlChange(value: String) {
        _uiState.value = _uiState.value.copy(repoUrl = value)
    }

    fun saveRepoUrl() {
        repoPrefs.repoUrl = _uiState.value.repoUrl
        load()
    }

    fun install(listing: MangayomiExtensionListing) {
        _uiState.value = _uiState.value.copy(installingId = listing.id, error = null)
        viewModelScope.launch {
            runCatching {
                // The download/build stays cancellable (navigate away → the install is dropped and
                // the runtime closed by the installer/factory). But once the source exists, hand it
                // to the registry non-cancellably — otherwise a cancel in that gap would orphan a
                // live QuickJS engine that's neither registered nor closed.
                val source = installer.install(listing)
                withContext(NonCancellable) { sourceRepository.registerDynamic(source) }
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                _uiState.value = _uiState.value.copy(error = failure.message ?: "Failed to install extension")
            }
            _uiState.value = _uiState.value.copy(installingId = null)
        }
    }

    fun uninstall(listing: MangayomiExtensionListing) {
        viewModelScope.launch {
            runCatching {
                // Non-cancellable so DB delete + in-memory unregister (which closes the QuickJS
                // runtime) complete atomically even if the user navigates away mid-remove.
                withContext(NonCancellable) {
                    installer.uninstall(listing.id)
                    sourceRepository.unregisterDynamic(listing.id)
                }
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                _uiState.value = _uiState.value.copy(error = failure.message ?: "Failed to uninstall extension")
            }
        }
    }
}
