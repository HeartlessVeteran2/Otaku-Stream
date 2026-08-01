package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.feature.tracking.AniListClient
import com.otakustream.feature.tracking.AniListMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class AniListSearchUiState(
    val query: String = "",
    val results: List<AniListMedia> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
)

// Searches AniList's own catalog (not just installed sources) so users can find any anime, open its
// AniList detail, and Watch from there. Debounced so typing doesn't fire a request per keystroke.
@HiltViewModel
class AniListSearchViewModel @Inject constructor(
    private val aniListClient: AniListClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AniListSearchUiState())
    val uiState: StateFlow<AniListSearchUiState> = _uiState.asStateFlow()

    // Typing and retrying are the same event with different urgency, so they share one stream and
    // the wait lives *inside* the collector rather than in a `debounce()` operator on one branch.
    //
    // Two separate flows merged instead — the obvious shape — leaves them unable to cancel each
    // other: a retry tapped while a keystroke's debounce was still pending would start the search
    // immediately and then have it cancelled and restarted when that pending emission landed a
    // moment later, costing a duplicate request. collectLatest cancels the delay below, so here a
    // retry supersedes pending typing and pending typing supersedes an earlier keystroke, with one
    // rule covering both.
    //
    // A StateFlow would also refuse to re-emit an unchanged value, and re-running the *same* query
    // is exactly what retrying a failed one means.
    private data class SearchRequest(val query: String, val settleFirst: Boolean)

    // replay = 1 because the collector below is started from `init` and a request can be emitted
    // before it subscribes — a SharedFlow with no subscriber drops rather than buffers, which would
    // lose the first search outright and leave a retry's spinner running forever.
    private val requests = MutableSharedFlow<SearchRequest>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        viewModelScope.launch {
            requests.collectLatest { request ->
                // Typing waits for the user to stop; a tap on "Try again" is already deliberate and
                // runs now — making it sit out the typing debounce just leaves the button looking
                // broken for a third of a second.
                if (request.settleFirst) delay(SEARCH_DEBOUNCE_MS)
                val query = request.query
                if (query.isBlank()) {
                    _uiState.value = _uiState.value.copy(results = emptyList(), isSearching = false, error = null)
                    return@collectLatest
                }
                _uiState.value = _uiState.value.copy(isSearching = true, error = null)
                runCatching { aniListClient.search(query).media }
                    .onSuccess { results ->
                        _uiState.value = _uiState.value.copy(results = results, isSearching = false)
                    }
                    .onFailure { failure ->
                        if (failure is CancellationException) throw failure
                        _uiState.value = _uiState.value.copy(
                            isSearching = false,
                            error = failure.message ?: "Search failed",
                        )
                    }
            }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        requests.tryEmit(SearchRequest(query, settleFirst = true))
    }

    // Re-runs the current search. Almost every failure here is a dropped connection, and before this
    // the only way to trigger another attempt was to change the query — which is not the thing the
    // user wants to change.
    fun retry() {
        val query = _uiState.value.query
        if (query.isBlank()) return
        // Spinner on the same frame as the tap. The collector sets this too, but only once the
        // request is actually under way — and the gap between the two is where the button reads as
        // unresponsive, since the error message it replaces is still sitting there.
        _uiState.value = _uiState.value.copy(isSearching = true, error = null)
        requests.tryEmit(SearchRequest(query, settleFirst = false))
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}
