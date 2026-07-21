package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.database.library.LibraryRepository
import com.otakustream.core.database.library.WatchHistoryEntry
import com.otakustream.core.sources.api.VideoSource
import com.otakustream.feature.sources.SourceBootstrapper
import com.otakustream.feature.sources.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

private const val RAIL_ITEM_CAP = 20
private const val CONTINUE_WATCHING_CAP = 10
// Soft cap per source per rail so one slow source can't hold up the whole home fan-out.
private const val RAIL_FETCH_TIMEOUT_MS = 15_000L

data class HomeUiState(
    val popular: List<CatalogEntry> = emptyList(),
    val latest: List<CatalogEntry> = emptyList(),
    val isLoading: Boolean = false,
    val hasAnySources: Boolean = false,
    val hasLoadedOnce: Boolean = false,
)

// Drives the content-forward home on the Play tab: Continue Watching plus Popular/Latest rails
// fanned out across every enabled source. Lives in feature:sources (not app) because it must run
// both source bootstrappers — Play is the start destination, so persisted addons may not be
// registered yet when it first loads. registerDynamic dedupes by id, so CatalogViewModel
// bootstrapping again later is harmless.
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val sourceRepository: SourceRepository,
    private val sourceBootstrapper: SourceBootstrapper,
    libraryRepository: LibraryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val continueWatching: StateFlow<List<WatchHistoryEntry>> = libraryRepository.observeHistory()
        .map { history -> history.distinctBy { it.mediaUrl }.take(CONTINUE_WATCHING_CAP) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var railsJob: Job? = null

    init {
        viewModelScope.launch {
            // Shared, once-per-process rehydrate of persisted sources (dedup with CatalogViewModel).
            // A failed bootstrap (corrupt persisted source, I/O error) must not kill this coroutine
            // before observeSources() collection starts — that would leave the home stuck loading
            // forever — so SourceBootstrapper guards each rehydrate internally.
            sourceBootstrapper.ensureStarted()
            // React to every registration change (bootstrap above, addon install/removal later)
            // so a newly installed add-on populates the home without a restart.
            sourceRepository.observeSources().collectLatest { sources ->
                _uiState.value = _uiState.value.copy(hasAnySources = sources.isNotEmpty())
                refreshRails(sources)
            }
        }
    }

    fun refresh() {
        refreshRails(sourceRepository.getSources())
    }

    private fun refreshRails(sources: List<VideoSource>) {
        railsJob?.cancel()
        railsJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            // The two rails are independent — fetch them concurrently.
            coroutineScope {
                val popular = async { fetchRail(sources) { it.getPopular(1) } }
                val latest = async { fetchRail(sources) { it.getLatest(1) } }
                _uiState.value = _uiState.value.copy(
                    popular = popular.await(),
                    latest = latest.await(),
                    isLoading = false,
                    hasLoadedOnce = true,
                )
            }
        }
    }

    // Per-source runCatching keeps one broken add-on from blanking the whole rail; results are
    // interleaved round-robin so a single prolific source doesn't crowd the others out.
    private suspend fun fetchRail(
        sources: List<VideoSource>,
        fetch: suspend (VideoSource) -> com.otakustream.core.sources.api.CatalogPage,
    ): List<CatalogEntry> = coroutineScope {
        val perSource = sources.map { source ->
            async {
                runCatching { fetch(source).items.map { CatalogEntry(source.id, it) } }
                    .getOrElse { error ->
                        if (error is CancellationException) throw error
                        emptyList()
                    }
            }
        }.awaitAll()
        // Dedupe by (source, url) before the cap: the rails key on that pair, and a source can
        // repeat an item — a duplicate key would crash the LazyRow.
        interleave(perSource).distinctBy { it.sourceId to it.media.url }.take(RAIL_ITEM_CAP)
    }

    private fun interleave(lists: List<List<CatalogEntry>>): List<CatalogEntry> {
        val result = mutableListOf<CatalogEntry>()
        val maxSize = lists.maxOfOrNull { it.size } ?: 0
        for (index in 0 until maxSize) {
            for (list in lists) {
                list.getOrNull(index)?.let(result::add)
            }
        }
        return result
    }
}
