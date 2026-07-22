package com.otakustream.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.database.library.LibraryEntry
import com.otakustream.core.database.library.LibraryRepository
import com.otakustream.core.database.library.WatchHistoryEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    val watchlist: List<LibraryEntry> = emptyList(),
    val history: List<WatchHistoryEntry> = emptyList(),
    // Most recent history row per media — the "continue watching" rail.
    val continueWatching: List<WatchHistoryEntry> = emptyList(),
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    val uiState: StateFlow<LibraryUiState> = combine(
        libraryRepository.observeLibrary(),
        libraryRepository.observeHistory(),
    ) { watchlist, history ->
        LibraryUiState(
            watchlist = watchlist,
            history = history,
            continueWatching = history.distinctBy { it.mediaUrl }.take(10),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun removeFromWatchlist(mediaUrl: String) {
        viewModelScope.launch { libraryRepository.remove(mediaUrl) }
    }

    fun setStatus(mediaUrl: String, status: String) {
        viewModelScope.launch { libraryRepository.setStatus(mediaUrl, status) }
    }

    fun clearHistory() {
        viewModelScope.launch { libraryRepository.clearHistory() }
    }
}
