package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.sources.api.MediaItem
import com.otakustream.core.sources.api.SourceFilter
import com.otakustream.core.sources.api.VideoSource
import com.otakustream.feature.sources.SourceBootstrapper
import com.otakustream.feature.sources.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class CatalogEntry(val sourceId: Long, val media: MediaItem)

// A source the user can scope browsing/search to, shown in the source picker.
data class SourcePick(val id: Long, val name: String)

data class CatalogUiState(
    val query: String = "",
    val entries: List<CatalogEntry> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val nextPageBySource: Map<Long, Int> = emptyMap(),
    val exhaustedSources: Set<Long> = emptySet(),
    // The registered sources, offered in the picker; selectedSourceId == null means "All sources"
    // (the cross-source fan-out); a non-null id scopes browsing/search to that one source.
    val availableSources: List<SourcePick> = emptyList(),
    val selectedSourceId: Long? = null,
    // id → name, used to badge each result with the source it came from.
    val sourceNames: Map<Long, String> = emptyMap(),
    // The union of every registered source's declared filters (e.g. genre) merged by name —
    // sources that don't recognize a given filter simply ignore it when it's passed to search().
    val availableFilters: List<SourceFilter> = emptyList(),
    val selectedFilters: List<SourceFilter> = emptyList(),
    // Whether any source is registered at all — drives the "install your first add-on" first-run
    // state vs. a genuine "no results" state.
    val hasAnySources: Boolean = false,
    // Set once the first fan-out completes, so the UI can tell "still loading the first page" from
    // "loaded and genuinely empty."
    val hasLoadedOnce: Boolean = false,
    // How many sources failed on the last fetch (network down, dead addon). Surfaced as a
    // dismissible "some sources couldn't load" banner rather than silently dropping their results.
    val failedSourceCount: Int = 0,
)

private data class SourceFetch(
    val sourceId: Long,
    val entries: List<CatalogEntry>,
    val hasNextPage: Boolean,
    val failed: Boolean = false,
)

private const val SEARCH_DEBOUNCE_MS = 300L
private const val FIRST_LOAD_MORE_PAGE = 2
// Soft cap per source per page so one slow source can't hold up the whole fan-out's result.
private const val SOURCE_FETCH_TIMEOUT_MS = 15_000L

@OptIn(FlowPreview::class)
@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val sourceRepository: SourceRepository,
    private val sourceBootstrapper: SourceBootstrapper,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()

    private val queryFlow = MutableStateFlow("")
    private var loadMoreJob: Job? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            // Shared, once-per-process rehydrate of persisted scripted + Stremio sources — no longer
            // duplicated against HomeViewModel's own bootstrap.
            sourceBootstrapper.ensureStarted()
            // Only start reacting to searches once bootstrapping has registered every persisted
            // source, so the very first catalog load can't race a still-loading source.
            queryFlow.debounce(SEARCH_DEBOUNCE_MS).collect { query -> startSearch(query) }
        }
        // Separate from the query flow above: re-derive availableFilters whenever a source is
        // registered/unregistered (e.g. installing/removing a Stremio addon), not just once at
        // startup, so newly installed addons' filters actually show up without a restart.
        viewModelScope.launch {
            sourceRepository.observeSources().collectLatest { sources ->
                // If the currently-scoped source was removed, fall back to "All sources".
                val prevSelected = _uiState.value.selectedSourceId
                val selected = prevSelected?.takeIf { id -> sources.any { it.id == id } }
                _uiState.value = _uiState.value.copy(
                    availableFilters = fetchAvailableFilters(sources),
                    availableSources = sources.map { SourcePick(it.id, it.name) },
                    sourceNames = sources.associate { it.id to it.name },
                    selectedSourceId = selected,
                    hasAnySources = sources.isNotEmpty(),
                )
                // The scoped source vanished — the shown results are now stale; re-run for All.
                if (prevSelected != null && selected == null) {
                    startSearch(_uiState.value.query)
                }
            }
        }
    }

    // The sources a browse/search should hit: just the picked one, or every source when "All".
    private fun effectiveSources(): List<VideoSource> {
        val selected = _uiState.value.selectedSourceId
        val all = sourceRepository.getSources()
        return if (selected == null) all else all.filter { it.id == selected }
    }

    fun selectSource(sourceId: Long?) {
        if (_uiState.value.selectedSourceId == sourceId) return
        _uiState.value = _uiState.value.copy(selectedSourceId = sourceId, isLoading = true)
        startSearch(_uiState.value.query)
    }

    fun search(query: String) {
        // Update the displayed text immediately so typing never feels laggy; the network
        // fan-out itself is debounced via queryFlow below.
        _uiState.value = _uiState.value.copy(query = query, isLoading = true)
        queryFlow.value = query
    }

    // valueIndex null clears that filter; otherwise selects filter.values[valueIndex] for it.
    // Re-runs the fan-out immediately (not debounced) since this is a deliberate tap, not typing.
    fun selectFilter(filter: SourceFilter, valueIndex: Int?) {
        val remaining = _uiState.value.selectedFilters.filterNot { it.name == filter.name }
        val updated = if (valueIndex == null) remaining else remaining + filter.copy(selected = valueIndex)
        _uiState.value = _uiState.value.copy(selectedFilters = updated, isLoading = true)
        startSearch(_uiState.value.query)
    }

    // Re-run the current search — used by the "some sources couldn't load" retry affordance.
    fun retry() {
        _uiState.value = _uiState.value.copy(isLoading = true, failedSourceCount = 0)
        startSearch(_uiState.value.query)
    }

    fun dismissSourceError() {
        _uiState.value = _uiState.value.copy(failedSourceCount = 0)
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoadingMore) return
        val pending = effectiveSources().filter { it.id !in state.exhaustedSources }
        if (pending.isEmpty()) return
        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingMore = true)
            val results = fetchPage(state.query, state.selectedFilters, pending, state.nextPageBySource)
            val current = _uiState.value
            _uiState.value = current.copy(
                // Dedupe by (source, url): the grid keys on that pair, and real sources routinely
                // repeat items across pages — a duplicate key would crash the LazyVerticalGrid.
                entries = (current.entries + results.flatMap { it.entries })
                    .distinctBy { it.sourceId to it.media.url },
                isLoadingMore = false,
                nextPageBySource = current.nextPageBySource + results.associate {
                    it.sourceId to (current.nextPageBySource[it.sourceId] ?: FIRST_LOAD_MORE_PAGE) + 1
                },
                exhaustedSources = current.exhaustedSources + results.filterNot { it.hasNextPage }.map { it.sourceId },
                failedSourceCount = results.count { it.failed },
            )
        }
    }

    // Cancels any in-flight search or load-more before starting a new one, so a query typed (or
    // a filter tapped) while an older search/loadMore is still resolving can't have that older
    // call's stale results land after — and overwrite — the newer one's.
    private fun startSearch(query: String) {
        loadMoreJob?.cancel()
        searchJob?.cancel()
        // A cancelled load-more would otherwise leave its bottom spinner stuck on — clear it
        // whenever a fresh search supersedes an in-flight page load (source switch, filter, typing).
        _uiState.value = _uiState.value.copy(isLoadingMore = false)
        searchJob = viewModelScope.launch { runSearch(query) }
    }

    private suspend fun runSearch(query: String) {
        val sources = effectiveSources()
        val results = fetchPage(query, _uiState.value.selectedFilters, sources, nextPageBySource = emptyMap())
        _uiState.value = _uiState.value.copy(
            // Dedupe by (source, url) — the grid keys on that pair, so duplicates would crash it.
            entries = results.flatMap { it.entries }.distinctBy { it.sourceId to it.media.url },
            isLoading = false,
            hasLoadedOnce = true,
            failedSourceCount = results.count { it.failed },
            nextPageBySource = results.associate { it.sourceId to FIRST_LOAD_MORE_PAGE },
            exhaustedSources = results.filterNot { it.hasNextPage }.map { it.sourceId }.toSet(),
        )
    }

    // Union of every source's declared filters, merged by name — a source that doesn't recognize
    // a filter later passed to its search() call is expected to just ignore it (already true for
    // every existing VideoSource implementation).
    private suspend fun fetchAvailableFilters(sources: List<VideoSource>): List<SourceFilter> = coroutineScope {
        sources.map { source ->
            async {
                runCatching { source.getAvailableFilters() }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    emptyList()
                }
            }
        }
            .awaitAll()
            .flatten()
            .groupBy { it.name }
            .map { (name, filters) -> SourceFilter(name = name, values = filters.flatMap { it.values }.distinct()) }
    }

    // Shared parallel fan-out used by both the initial/debounced search and loadMore() — each
    // source is queried concurrently, and a per-source runCatching (not one around the whole
    // awaitAll) keeps one broken source from affecting or cancelling the others.
    private suspend fun fetchPage(
        query: String,
        filters: List<SourceFilter>,
        sources: List<VideoSource>,
        nextPageBySource: Map<Long, Int>,
    ): List<SourceFetch> = coroutineScope {
        sources.map { source ->
            async {
                val page = nextPageBySource[source.id] ?: 1
                runCatching {
                    // Bound each source so one hung endpoint can't stall the whole fan-out — a
                    // timed-out source is treated as a failed fetch (surfaced in the error banner),
                    // not left blocking the others.
                    val result = withTimeoutOrNull(SOURCE_FETCH_TIMEOUT_MS) {
                        if (query.isBlank() && filters.isEmpty()) source.getPopular(page) else source.search(query, filters, page)
                    } ?: return@async SourceFetch(source.id, emptyList(), hasNextPage = false, failed = true)
                    SourceFetch(source.id, result.items.map { CatalogEntry(source.id, it) }, result.hasNextPage)
                }.getOrElse { error ->
                    // Don't swallow cancellation — a newer search cancels this fan-out, and eating
                    // the CancellationException would break structured concurrency.
                    if (error is CancellationException) throw error
                    SourceFetch(source.id, emptyList(), hasNextPage = false, failed = true)
                }
            }
        }.awaitAll()
    }
}
