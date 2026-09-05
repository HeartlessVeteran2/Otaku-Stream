package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.database.stremio.StremioRepository
import com.otakustream.core.sources.stremio.StremioAddonDirectoryClient
import com.otakustream.core.sources.stremio.StremioAddonInstaller
import com.otakustream.core.sources.stremio.AdultContentSettings
import com.otakustream.core.sources.stremio.StremioDirectorySettings
import com.otakustream.core.sources.stremio.normalizeStremioManifestUrl
import com.otakustream.core.sources.stremio.model.AddonKind
import com.otakustream.core.sources.stremio.model.OfficialAddonListing
import com.otakustream.core.sources.stremio.model.kind
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

// Which slice of the directory is on screen. ALL is a hundred rows of which about half are subtitle
// add-ons, so it is browsable rather than useful; STREAMS is what someone opening this screen almost
// always came for.
enum class AddonFilter(val label: String) {
    ALL("All"),
    STREAMS("Streams"),
    CATALOGS("Catalogs"),
    SUBTITLES("Subtitles"),
}

data class BrowseStremioUiState(
    val isLoading: Boolean = false,
    val listings: List<OfficialAddonListing> = emptyList(),
    val filter: AddonFilter = AddonFilter.ALL,
    val installedUrls: Set<String> = emptySet(),
    val installingUrl: String? = null,
    val error: String? = null,
    val customListUrl: String = "",
    // Reported separately from `error`: a broken custom list must not read as the whole directory
    // being down, since the official and community add-ons are still listed below it.
    val customListError: String? = null,
    val showAdult: Boolean = false,
)

@HiltViewModel
class BrowseStremioAddonsViewModel @Inject constructor(
    private val directoryClient: StremioAddonDirectoryClient,
    private val installer: StremioAddonInstaller,
    private val stremioRepository: StremioRepository,
    private val sourceRepository: SourceRepository,
    private val directorySettings: StremioDirectorySettings,
    private val adultContentSettings: AdultContentSettings,
) : ViewModel() {

    private val listings = MutableStateFlow<List<OfficialAddonListing>>(emptyList())
    private val filter = MutableStateFlow(AddonFilter.ALL)

    // Driven by what the directory actually filtered on, not by a second read of the preference.
    // Two independent reads settle at different moments, and the pairing that produces is the one
    // that must never happen: adult rows on screen under a switch that reads off.
    private val showAdult = MutableStateFlow(false)
    private val isLoading = MutableStateFlow(false)
    private val installingUrl = MutableStateFlow<String?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val customListError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<BrowseStremioUiState> = combine(
        combine(listings, filter) { all, selected -> all.filteredBy(selected) to selected },
        stremioRepository.observeAddons(),
        isLoading,
        installingUrl,
        combine(
            error,
            directorySettings.customListUrl,
            customListError,
            showAdult,
        ) { err, customUrl, customErr, adult ->
            DirectoryPrefsState(err, customUrl, customErr, adult)
        },
    ) { (visible, selected), installed, loading, installing, prefs ->
        BrowseStremioUiState(
            isLoading = loading,
            listings = visible,
            filter = selected,
            // Normalized on both sides, because installation normalizes before saving.
            //
            // A listing whose transportUrl is `stremio://…`, or which omits the trailing
            // /manifest.json, is stored under a different string than the one the row holds — so
            // comparing raw strings left it reading "Install" after it had just been installed,
            // and tapping again reinstalled it. Not introduced here, but this is the screen it
            // shows on, and the curated list makes hand-written URLs more common rather than less.
            installedUrls = installed.mapTo(mutableSetOf()) { normalizeStremioManifestUrl(it.manifestUrl) },
            installingUrl = installing,
            error = prefs.error,
            customListUrl = prefs.customListUrl.orEmpty(),
            customListError = prefs.customListError,
            showAdult = prefs.showAdult,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseStremioUiState())

    init {
        load()
    }

    fun setFilter(selected: AddonFilter) {
        filter.value = selected
    }

    // Turning it off hides what is already on screen *before* anything is saved or refetched.
    //
    // The reload is what rebuilds the list properly, but it is a network round trip that can be
    // slow and can fail — and until it returns, the adult rows fetched under the old setting are
    // still being rendered. Leaving them there for the duration is precisely the thing this switch
    // exists to prevent, so the off case does not wait for anything it does not have to.
    //
    // Turning it *on* has no such urgency and stays purely reload-driven: nothing can be shown
    // early that was not already fetched.
    fun setShowAdult(enabled: Boolean) {
        if (!enabled) {
            showAdult.value = false
            listings.value = listings.value.filterNot { it.isAdult }
        }
        viewModelScope.launch {
            // The switch follows the store, not the tap. A preferences write can fail, and a switch
            // that moved while the setting did not would disagree with the directory on the next
            // load — and would disagree in the direction of showing more than the user asked for.
            val stored = adultContentSettings.set(enabled)
            if (!stored) {
                error.value = "Couldn't save that setting."
            }
            load()
        }
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
                    showAdult.value = directory.showAdult
                    customListError.value = directory.customListError
                    // A banner beside the recommended list rather than instead of it: the fetched
                    // lists being unreachable no longer empties the screen.
                    error.value = directory.builtInListError
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

// Filtering by what the add-on does, not by what it calls itself.
//
// ALL is returned untouched rather than mapped through the same predicate, so a listing whose
// manifest declares no resources at all — every configuration-required add-on, until it is
// configured — is reachable from somewhere. Under a specific filter it would fall into no bucket
// and vanish, which for AIOStreams would mean the add-on cannot be found on the screen that exists
// to find add-ons.
private fun List<OfficialAddonListing>.filteredBy(filter: AddonFilter): List<OfficialAddonListing> =
    when (filter) {
        AddonFilter.ALL -> this
        AddonFilter.STREAMS -> filter { it.kind() == AddonKind.STREAMS }
        AddonFilter.CATALOGS -> filter { it.kind() == AddonKind.CATALOGS }
        AddonFilter.SUBTITLES -> filter { it.kind() == AddonKind.SUBTITLES }
    }

// The four values that ride together through the combine, which is capped at five flows. A named
// holder rather than another nested Triple: the previous destructuring was already at the edge of
// readable, and adding a fourth positional field to it would make a mis-ordered argument a silent
// bug rather than a compile error.
private data class DirectoryPrefsState(
    val error: String?,
    val customListUrl: String?,
    val customListError: String?,
    val showAdult: Boolean,
)
