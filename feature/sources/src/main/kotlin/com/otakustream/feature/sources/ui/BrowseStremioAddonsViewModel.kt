package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.database.stremio.StremioRepository
import com.otakustream.core.sources.stremio.StremioAddonDirectoryClient
import com.otakustream.core.sources.stremio.StremioAddonInstaller
import com.otakustream.core.sources.stremio.StremioDirectorySettings
import com.otakustream.core.sources.stremio.model.OfficialAddonListing
import com.otakustream.feature.sources.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.otakustream.core.sources.api.UiMessages

data class BrowseStremioUiState(
    val isLoading: Boolean = false,
    val listings: List<OfficialAddonListing> = emptyList(),
    val installedUrls: Set<String> = emptySet(),
    val installingUrl: String? = null,
    val error: String? = null,
    val customListUrl: String = "",
    // Reported separately from `error`: a broken custom list must not read as the whole directory
    // being down, since the official and community add-ons are still listed below it.
    val customListError: String? = null,
)

@HiltViewModel
class BrowseStremioAddonsViewModel @Inject constructor(
    private val directoryClient: StremioAddonDirectoryClient,
    private val installer: StremioAddonInstaller,
    private val stremioRepository: StremioRepository,
    private val sourceRepository: SourceRepository,
    private val directorySettings: StremioDirectorySettings,
) : ViewModel() {

    private val listings = MutableStateFlow<List<OfficialAddonListing>>(emptyList())
    private val isLoading = MutableStateFlow(false)
    private val installingUrl = MutableStateFlow<String?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val customListError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<BrowseStremioUiState> = combine(
        listings,
        stremioRepository.observeAddons(),
        isLoading,
        installingUrl,
        combine(error, directorySettings.customListUrl, customListError) { err, customUrl, customErr ->
            Triple(err, customUrl, customErr)
        },
    ) { listings, installed, loading, installing, (err, customUrl, customErr) ->
        BrowseStremioUiState(
            isLoading = loading,
            listings = listings,
            installedUrls = installed.map { it.manifestUrl }.toSet(),
            installingUrl = installing,
            error = err,
            customListUrl = customUrl.orEmpty(),
            customListError = customErr,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseStremioUiState())

    init {
        load()
    }

    // A newer load supersedes an older one rather than racing it: Retry and Save both call this, and
    // two in-flight fetches could otherwise finish out of order and leave the screen showing the
    // earlier result. Same job-cancelling shape the other loading ViewModels here use.
    private var loadJob: Job? = null

    fun load() {
        loadJob?.cancel()
        isLoading.value = true
        error.value = null
        customListError.value = null
        loadJob = viewModelScope.launch {
            runCatching { directoryClient.fetchAddonCatalog() }
                .onSuccess { directory ->
                    listings.value = directory.listings
                    customListError.value = directory.customListError
                }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    error.value = failure.message ?: "Failed to load addon catalog"
                }
            isLoading.value = false
        }
    }

    // Saving re-fetches so the list the user just added (or removed) is reflected immediately. The
    // save is awaited first — the fetch reads the same store, so starting it before the write landed
    // would reload with the *old* URL and show a stale list right after the user changed it.
    fun saveCustomListUrl(url: String) {
        viewModelScope.launch {
            directorySettings.set(url)
            load()
        }
    }

    fun install(listing: OfficialAddonListing) {
        installingUrl.value = listing.transportUrl
        error.value = null
        viewModelScope.launch {
            runCatching {
                val nextPriority = (stremioRepository.getAllAddons().maxOfOrNull { it.priority } ?: -1) + 1
                installer.installFromUrl(listing.transportUrl, priority = nextPriority)
            }
                .onSuccess { sources ->
                    sources.forEach(sourceRepository::registerDynamic)
                    UiMessages.show("Installed ${listing.name}")
                }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    error.value = failure.message ?: "Failed to install addon"
                }
            installingUrl.value = null
        }
    }
}
