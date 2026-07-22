package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.feature.tracking.AniListClient
import com.otakustream.feature.tracking.AniListMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    init {
        viewModelScope.launch {
            queryFlow.debounce(SEARCH_DEBOUNCE_MS).collectLatest { query ->
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

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}
