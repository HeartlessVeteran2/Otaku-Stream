package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.sources.mangayomi.MangayomiExtensionInstaller
import com.otakustream.core.sources.mangayomi.repo.MangayomiExtensionListing
import com.otakustream.core.sources.mangayomi.repo.MangayomiRepoClient
import com.otakustream.core.sources.mangayomi.repo.MangayomiRepoPrefs
import com.otakustream.core.sources.mangayomi.repo.RecommendedExtensionRepos
import com.otakustream.core.sources.stremio.AdultContentSettings
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
import com.otakustream.core.sources.api.UiMessages

data class MangayomiExtensionsUiState(
    val isLoading: Boolean = false,
    val listings: List<MangayomiExtensionListing> = emptyList(),
    // ids of installed sources — a listing is installed when its id is among them.
    val installedIds: Set<Long> = emptySet(),
    val installingId: Long? = null,
    val repoUrl: String = "",
    val error: String? = null,
    // Entries in the loaded repos this app cannot run — Dart extensions, and manga/novel sources.
    // Shown rather than silently dropped: m2k3a's index is 64 entries of which 40 are Dart, so a
    // screen that just rendered 24 rows was indistinguishable from a broken repo.
    val unsupportedCount: Int = 0,
    // Curated repos that could not be reached, by name. A banner, not a blank screen — the other
    // repos' extensions are still on it.
    val unreachableRepos: List<String> = emptyList(),
    // The repos offered for one-tap browsing, so the screen can say where the listings came from
    // and what else is available.
    val suggestedRepos: List<RecommendedExtensionRepos.Repo> = RecommendedExtensionRepos.repos,
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
    private val adultContentSettings: AdultContentSettings,
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
            // Read here and passed down, so an adult listing never reaches this screen's state at
            // all rather than merely going unrendered by it — the same property the Stremio
            // directory holds. The setting itself lives in the Stremio module, which the extension
            // client must not depend on.
            val showAdult = adultContentSettings.get()
            runCatching { repoClient.fetch(showAdult) }
                .onSuccess { directory ->
                    _uiState.value = _uiState.value.copy(
                        listings = directory.listings,
                        unsupportedCount = directory.unsupportedCount,
                        unreachableRepos = directory.unreachableRepos,
                        // Only a URL the user typed is reported as an error: a curated repo being
                        // down is the app's problem to mention quietly, and the others still loaded.
                        error = directory.customRepoError,
                    )
                }
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

    // Puts a suggested repo in the URL field and loads it, for someone who wants that one on its
    // own. The curated repos are already merged into the list without this — it is here for the
    // case where a repo has an extension the merge deduped away, or the user simply wants to see
    // one repo's contents.
    fun useSuggestedRepo(repo: RecommendedExtensionRepos.Repo) {
        _uiState.value = _uiState.value.copy(repoUrl = repo.indexUrl)
        saveRepoUrl()
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
                UiMessages.show("Installed ${listing.name}")
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
                UiMessages.show("Removed ${listing.name}")
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                _uiState.value = _uiState.value.copy(error = failure.message ?: "Failed to uninstall extension")
            }
        }
    }
}
