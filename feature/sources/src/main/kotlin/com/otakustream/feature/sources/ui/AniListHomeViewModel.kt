package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.database.tracking.TrackingRepository
import com.otakustream.feature.tracking.AniListClient
import com.otakustream.feature.tracking.AniListListEntry
import com.otakustream.feature.tracking.AniListMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AniListHomeUiState(
    val trending: List<AniListMedia> = emptyList(),
    val thisSeason: List<AniListMedia> = emptyList(),
    val allTimePopular: List<AniListMedia> = emptyList(),
    // The signed-in user's in-progress list (CURRENT/REPEATING), shown as an AniList "Continue
    // watching" rail above the discovery rails. Empty when logged out.
    val continueWatching: List<AniListListEntry> = emptyList(),
    val isLoading: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val error: String? = null,
)

// Backs the AniList-forward discovery rails on the Play tab (AnymeX-style): Trending, This Season,
// and All-Time Popular come straight from AniList with no login. When a token is present it also
// loads the user's own in-progress list. Lives in feature:sources so it can share the Play home
// with the source-based rails; the AniList client itself is in feature:tracking.
@HiltViewModel
class AniListHomeViewModel @Inject constructor(
    private val aniListClient: AniListClient,
    private val trackingRepository: TrackingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AniListHomeUiState())
    val uiState: StateFlow<AniListHomeUiState> = _uiState.asStateFlow()

    init {
        loadDiscovery()
        // Re-load the personal rail whenever sign-in state flips (token appears/clears).
        viewModelScope.launch {
            trackingRepository.observeToken().distinctUntilChanged().collect { token ->
                loadContinueWatching(token)
            }
        }
    }

    fun refresh() = loadDiscovery()

    private fun loadDiscovery() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            runCatching {
                coroutineScope {
                    val trending = async { aniListClient.fetchTrending().media }
                    val thisSeason = async { aniListClient.fetchPopularThisSeason().media }
                    val popular = async { aniListClient.fetchAllTimePopular().media }
                    Triple(trending.await(), thisSeason.await(), popular.await())
                }
            }.onSuccess { (trending, thisSeason, popular) ->
                _uiState.value = _uiState.value.copy(
                    trending = trending,
                    thisSeason = thisSeason,
                    allTimePopular = popular,
                    isLoading = false,
                    hasLoadedOnce = true,
                    error = null,
                )
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    hasLoadedOnce = true,
                    error = failure.message ?: "Couldn't load AniList",
                )
            }
        }
    }

    private fun loadContinueWatching(token: String?) {
        if (token == null) {
            _uiState.value = _uiState.value.copy(continueWatching = emptyList())
            return
        }
        viewModelScope.launch {
            runCatching {
                val viewer = aniListClient.fetchViewer(token)
                aniListClient.fetchUserAnimeLists(token, viewer.id)
            }.onSuccess { entries ->
                // Only the actively-watching buckets belong in a "continue" rail; most-progress first.
                val inProgress = entries
                    .filter { it.status == "CURRENT" || it.status == "REPEATING" }
                    .sortedByDescending { it.progress }
                _uiState.value = _uiState.value.copy(continueWatching = inProgress)
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                // A personal-rail failure must never blank the logged-out discovery rails.
                _uiState.value = _uiState.value.copy(continueWatching = emptyList())
            }
        }
    }
}
