package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.sources.api.MediaItem
import com.otakustream.feature.sources.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CatalogEntry(val sourceId: Long, val media: MediaItem)

data class CatalogUiState(
    val query: String = "",
    val entries: List<CatalogEntry> = emptyList(),
    val isLoading: Boolean = false,
)

@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val sourceRepository: SourceRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()

    init {
        search("")
    }

    fun search(query: String) {
        _uiState.value = _uiState.value.copy(query = query, isLoading = true)
        viewModelScope.launch {
            val entries = sourceRepository.getSources().flatMap { source ->
                runCatching {
                    val page = if (query.isBlank()) source.getPopular(1) else source.search(query, emptyList(), 1)
                    page.items.map { CatalogEntry(source.id, it) }
                }.getOrDefault(emptyList())
            }
            _uiState.value = _uiState.value.copy(entries = entries, isLoading = false)
        }
    }
}
