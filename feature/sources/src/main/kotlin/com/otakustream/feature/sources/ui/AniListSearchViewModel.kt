package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.feature.tracking.AniListClient
import com.otakustream.feature.tracking.AniListMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

data class AniListSearchUiState(
    val query: String = "",
    val results: List<AniListMedia> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
)

// Searches AniList's own catalog (not just installed sources) so users can find any anime, open its
// AniList detail, and Watch from there. Debounced so typing doesn't fire a request per keystroke.
@OptIn(FlowPreview::class)
@HiltViewModel
class AniListSearchViewModel @Inject constructor(
    private val aniListClient: AniListClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AniListSearchUiState())
    val uiState: StateFlow<AniListSearchUiState> = _uiState.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    // Bumped by retry(). queryFlow is a StateFlow and will not re-emit an unchanged value, so
    // running the *same* search again — which is exactly what retrying a failed one means — needs
    // something else to change.
    private val retryTick = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            combine(queryFlow, retryTick) { query, _ -> query }.debounce(SEARCH_DEBOUNCE_MS).collectLatest { query ->
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
        queryFlow.value = query
    }

    // Re-runs the current search. Almost every failure here is a dropped connection, and before this
    // the only way to trigger another attempt was to change the query — which is not the thing the
    // user wants to change.
    fun retry() {
        retryTick.value++
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}
