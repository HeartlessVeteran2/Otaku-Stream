package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.database.stremio.StremioRepository
import com.otakustream.core.sources.stremio.StremioAddonDirectoryClient
import com.otakustream.core.sources.stremio.StremioAddonInstaller
import com.otakustream.core.sources.stremio.model.OfficialAddonListing
import com.otakustream.feature.sources.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BrowseStremioUiState(
    val isLoading: Boolean = false,
    val listings: List<OfficialAddonListing> = emptyList(),
    val installedUrls: Set<String> = emptySet(),
    val installingUrl: String? = null,
    val error: String? = null,
)

@HiltViewModel
class BrowseStremioAddonsViewModel @Inject constructor(
    private val directoryClient: StremioAddonDirectoryClient,
    private val installer: StremioAddonInstaller,
    private val stremioRepository: StremioRepository,
    private val sourceRepository: SourceRepository,
) : ViewModel() {

    private val listings = MutableStateFlow<List<OfficialAddonListing>>(emptyList())
    private val isLoading = MutableStateFlow(false)
    private val installingUrl = MutableStateFlow<String?>(null)
    private val error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<BrowseStremioUiState> = combine(
        listings,
        stremioRepository.observeAddons(),
        isLoading,
        installingUrl,
        error,
    ) { listings, installed, loading, installing, err ->
        BrowseStremioUiState(
            isLoading = loading,
            listings = listings,
            installedUrls = installed.map { it.manifestUrl }.toSet(),
            installingUrl = installing,
            error = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseStremioUiState())

    init {
        load()
    }

    fun load() {
        isLoading.value = true
        error.value = null
        viewModelScope.launch {
            runCatching { directoryClient.fetchOfficialAddons() }
                .onSuccess { listings.value = it }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    error.value = failure.message ?: "Failed to load addon catalog"
                }
            isLoading.value = false
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
                .onSuccess { sources -> sources.forEach(sourceRepository::registerDynamic) }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    error.value = failure.message ?: "Failed to install addon"
                }
            installingUrl.value = null
        }
    }
}
