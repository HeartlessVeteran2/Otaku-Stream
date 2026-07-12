package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.sources.api.MediaItem
import com.otakustream.core.sources.api.VideoSource
import com.otakustream.core.sources.scripting.ScriptedSourceBootstrapper
import com.otakustream.core.sources.stremio.StremioAddonBootstrapper
import com.otakustream.feature.sources.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
import javax.inject.Inject

data class CatalogEntry(val sourceId: Long, val media: MediaItem)

data class CatalogUiState(
    val query: String = "",
    val entries: List<CatalogEntry> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val nextPageBySource: Map<Long, Int> = emptyMap(),
    val exhaustedSources: Set<Long> = emptySet(),
)

private data class SourceFetch(val sourceId: Long, val entries: List<CatalogEntry>, val hasNextPage: Boolean)

private const val SEARCH_DEBOUNCE_MS = 300L
private const val FIRST_LOAD_MORE_PAGE = 2

@OptIn(FlowPreview::class)
@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val sourceRepository: SourceRepository,
    private val bootstrapper: ScriptedSourceBootstrapper,
    private val stremioBootstrapper: StremioAddonBootstrapper,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()

    private val queryFlow = MutableStateFlow("")
    private var loadMoreJob: Job? = null

    init {
        viewModelScope.launch {
            bootstrapper.loadPersistedSources().forEach(sourceRepository::registerDynamic)
            stremioBootstrapper.loadPersistedSources().forEach(sourceRepository::registerDynamic)
            // Only start reacting to searches once bootstrapping has registered every persisted
            // source, so the very first catalog load can't race a still-loading source.
            queryFlow.debounce(SEARCH_DEBOUNCE_MS).collectLatest { query -> runSearch(query) }
        }
    }

    fun search(query: String) {
        // Update the displayed text immediately so typing never feels laggy; the network
        // fan-out itself is debounced via queryFlow below.
        _uiState.value = _uiState.value.copy(query = query, isLoading = true)
        queryFlow.value = query
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoadingMore) return
        val pending = sourceRepository.getSources().filter { it.id !in state.exhaustedSources }
        if (pending.isEmpty()) return
        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingMore = true)
            val results = fetchPage(state.query, pending, state.nextPageBySource)
            val current = _uiState.value
            _uiState.value = current.copy(
                entries = current.entries + results.flatMap { it.entries },
                isLoadingMore = false,
                nextPageBySource = current.nextPageBySource + results.associate {
                    it.sourceId to (current.nextPageBySource[it.sourceId] ?: FIRST_LOAD_MORE_PAGE) + 1
                },
                exhaustedSources = current.exhaustedSources + results.filterNot { it.hasNextPage }.map { it.sourceId },
            )
        }
    }

    private suspend fun runSearch(query: String) {
        val sources = sourceRepository.getSources()
        val results = fetchPage(query, sources, nextPageBySource = emptyMap())
        _uiState.value = _uiState.value.copy(
            entries = results.flatMap { it.entries },
            isLoading = false,
            nextPageBySource = results.associate { it.sourceId to FIRST_LOAD_MORE_PAGE },
            exhaustedSources = results.filterNot { it.hasNextPage }.map { it.sourceId }.toSet(),
        )
    }

    // Shared parallel fan-out used by both the initial/debounced search and loadMore() — each
    // source is queried concurrently, and a per-source runCatching (not one around the whole
    // awaitAll) keeps one broken source from affecting or cancelling the others.
    private suspend fun fetchPage(
        query: String,
        sources: List<VideoSource>,
        nextPageBySource: Map<Long, Int>,
    ): List<SourceFetch> = coroutineScope {
        sources.map { source ->
            async {
                val page = nextPageBySource[source.id] ?: 1
                runCatching {
                    val result = if (query.isBlank()) source.getPopular(page) else source.search(query, emptyList(), page)
                    SourceFetch(source.id, result.items.map { CatalogEntry(source.id, it) }, result.hasNextPage)
                }.getOrDefault(SourceFetch(source.id, emptyList(), hasNextPage = false))
            }
        }.awaitAll()
    }
}
