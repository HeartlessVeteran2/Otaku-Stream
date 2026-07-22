package com.otakustream.feature.sources.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.database.tracking.TrackerLink
import com.otakustream.core.database.tracking.TrackingRepository
import com.otakustream.core.sources.api.MediaItem
import com.otakustream.feature.sources.SourceBootstrapper
import com.otakustream.feature.sources.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// One source's results for the AniList title being matched.
data class WatchResultGroup(
    val sourceId: Long,
    val sourceName: String,
    val items: List<MediaItem>,
)

// Where the bridge hands off once a source result is chosen (or an existing mapping is found).
data class WatchTarget(val sourceId: Long, val mediaUrl: String, val title: String)

data class AniListWatchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val groups: List<WatchResultGroup> = emptyList(),
    val hasNoSources: Boolean = false,
    val error: String? = null,
    val navigateTo: WatchTarget? = null,
)

// The Watch bridge: connects an AniList anime to an actual stream. Given the AniList title, it fans
// the title out across every installed source as a search, groups the hits by source, and lets the
// user pick the matching result. The chosen mapping is persisted as a TrackerLink (AniList id ↔
// source media url + source id) so next time Watch jumps straight back to that source and progress
// syncs to AniList through the normal MediaDetails playback path.
@HiltViewModel
class AniListWatchViewModel @Inject constructor(
    private val sourceRepository: SourceRepository,
    private val sourceBootstrapper: SourceBootstrapper,
    private val trackingRepository: TrackingRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val mediaId: Long = savedStateHandle.get<Long>("mediaId") ?: 0L
    private val title: String = savedStateHandle.get<String>("title").orEmpty()

    private val _uiState = MutableStateFlow(AniListWatchUiState(query = title))
    val uiState: StateFlow<AniListWatchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            // Persisted sources may not be registered yet when arriving here from an AniList screen.
            sourceBootstrapper.ensureStarted()
            // If a source was already chosen for this AniList id, skip the search and reopen it.
            // Guard on mediaId > 0: a missing/mistyped nav arg reads back as 0L, which could match
            // a stray legacy TrackerLink whose trackerMediaId is 0 and mis-open the wrong source —
            // fall back to the title search instead.
            val existing = if (mediaId > 0L) trackingRepository.getLinkByTrackerId(mediaId) else null
            if (existing != null && existing.sourceId != 0L &&
                sourceRepository.getSource(existing.sourceId) != null
            ) {
                _uiState.value = _uiState.value.copy(
                    navigateTo = WatchTarget(existing.sourceId, existing.mediaUrl, existing.trackerTitle),
                )
                return@launch
            }
            runSearch(title)
        }
    }

    fun search(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(query) }
    }

    private suspend fun runSearch(query: String) {
        val sources = sourceRepository.getSources()
        if (sources.isEmpty()) {
            _uiState.value = _uiState.value.copy(hasNoSources = true, isSearching = false, groups = emptyList())
            return
        }
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(groups = emptyList(), isSearching = false, hasNoSources = false)
            return
        }
        _uiState.value = _uiState.value.copy(isSearching = true, error = null, hasNoSources = false)
        // Per-source runCatching keeps one broken source from failing the whole match.
        val groups = coroutineScope {
            sources.map { source ->
                async {
                    runCatching { source.search(query, emptyList(), 1).items.distinctBy { it.url } }
                        .getOrElse { error ->
                            if (error is CancellationException) throw error
                            emptyList()
                        }
                        .takeIf { it.isNotEmpty() }
                        ?.let { WatchResultGroup(source.id, source.name, it.take(RESULTS_PER_SOURCE)) }
                }
            }.awaitAll().filterNotNull()
        }
        _uiState.value = _uiState.value.copy(isSearching = false, groups = groups)
    }

    fun pick(sourceId: Long, item: MediaItem) {
        viewModelScope.launch {
            trackingRepository.saveLink(
                TrackerLink(
                    mediaUrl = item.url,
                    trackerMediaId = mediaId,
                    trackerTitle = title.ifBlank { item.title },
                    sourceId = sourceId,
                ),
            )
            _uiState.value = _uiState.value.copy(navigateTo = WatchTarget(sourceId, item.url, item.title))
        }
    }

    fun consumeNavigation() {
        _uiState.value = _uiState.value.copy(navigateTo = null)
    }

    private companion object {
        const val RESULTS_PER_SOURCE = 8
    }
}
