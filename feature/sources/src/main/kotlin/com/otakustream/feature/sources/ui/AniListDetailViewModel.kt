package com.otakustream.feature.sources.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.feature.tracking.AniListClient
import com.otakustream.feature.tracking.AniListMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AniListDetailUiState(
    val media: AniListMedia? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

// Backs the AniList anime detail screen. Phase 3 shows read-only metadata (cover/banner, synopsis,
// genres, score, relations, recommendations); Phase 5 adds list controls (status/score/progress)
// and a working Watch action. The AniList media id arrives as a nav argument.
@HiltViewModel
class AniListDetailViewModel @Inject constructor(
    private val aniListClient: AniListClient,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val mediaId: Long = savedStateHandle.get<Long>("mediaId") ?: 0L

    private val _uiState = MutableStateFlow(AniListDetailUiState())
    val uiState: StateFlow<AniListDetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatching { aniListClient.fetchMediaDetail(mediaId) }
                .onSuccess { media -> _uiState.value = AniListDetailUiState(media = media, isLoading = false) }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = failure.message ?: "Couldn't load this title",
                    )
                }
        }
    }
}
