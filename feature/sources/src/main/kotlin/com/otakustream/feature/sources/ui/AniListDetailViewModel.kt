package com.otakustream.feature.sources.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.database.tracking.TrackingRepository
import com.otakustream.feature.tracking.AniListClient
import com.otakustream.feature.tracking.AniListMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

// The AniList list statuses, in the order AnymeX/AniList present them. Values are the exact
// MediaListStatus enum names the SaveMediaListEntry mutation expects.
val ANILIST_STATUSES = listOf("CURRENT", "PLANNING", "COMPLETED", "REPEATING", "PAUSED", "DROPPED")

fun aniListStatusLabel(status: String?): String = when (status) {
    "CURRENT" -> "Watching"
    "PLANNING" -> "Planning"
    "COMPLETED" -> "Completed"
    "REPEATING" -> "Rewatching"
    "PAUSED" -> "Paused"
    "DROPPED" -> "Dropped"
    else -> "Not on list"
}

data class AniListDetailUiState(
    val media: AniListMedia? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    // List-control state (only meaningful when signed in).
    val isSignedIn: Boolean = false,
    val onList: Boolean = false,
    val listStatus: String? = null,
    val listScore: Double? = null,
    val listProgress: Int = 0,
    val isSaving: Boolean = false,
    val saveError: String? = null,
)

// Backs the AniList anime detail screen: read-only metadata plus, when signed in, the viewer's own
// list controls (status/score/progress) written back through SaveMediaListEntry. The Watch action
// itself is wired in Phase 6.
@HiltViewModel
class AniListDetailViewModel @Inject constructor(
    private val aniListClient: AniListClient,
    private val trackingRepository: TrackingRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val mediaId: Long = savedStateHandle.get<Long>("mediaId") ?: 0L

    private val _uiState = MutableStateFlow(AniListDetailUiState())
    val uiState: StateFlow<AniListDetailUiState> = _uiState.asStateFlow()

    @Volatile
    private var token: String? = null

    init {
        load()
        // Track sign-in state; load/refresh the viewer's own list entry when a token is present.
        viewModelScope.launch {
            trackingRepository.observeToken().distinctUntilChanged().collect { newToken ->
                token = newToken
                if (newToken != null) {
                    _uiState.value = _uiState.value.copy(isSignedIn = true)
                    loadViewerEntry(newToken)
                } else {
                    _uiState.value = _uiState.value.copy(
                        isSignedIn = false,
                        onList = false,
                        listStatus = null,
                        listScore = null,
                        listProgress = 0,
                    )
                }
            }
        }
    }

    fun load() {
        // A missing/mistyped nav arg reads back as 0L; fail clearly instead of round-tripping to
        // AniList for Media(id: 0).
        if (mediaId <= 0L) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = "Couldn't open this title.")
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatching { aniListClient.fetchMediaDetail(mediaId) }
                .onSuccess { media -> _uiState.value = _uiState.value.copy(media = media, isLoading = false, error = null) }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = failure.message ?: "Couldn't load this title",
                    )
                }
        }
    }

    private fun loadViewerEntry(token: String) {
        viewModelScope.launch {
            runCatching { aniListClient.fetchViewerListEntry(token, mediaId) }
                .onSuccess { entry ->
                    _uiState.value = _uiState.value.copy(
                        onList = entry != null,
                        listStatus = entry?.status,
                        listScore = entry?.score,
                        listProgress = entry?.progress ?: 0,
                    )
                }
                .onFailure { failure -> if (failure is CancellationException) throw failure }
        }
    }

    fun setStatus(status: String) = applyEdit(status = status)

    fun setScore(score: Double) = applyEdit(score = score.coerceIn(0.0, 10.0))

    fun setProgress(progress: Int) {
        val max = _uiState.value.media?.episodes ?: Int.MAX_VALUE
        applyEdit(progress = progress.coerceIn(0, max))
    }

    // Sends only the changed field to SaveMediaListEntry, optimistically reflects it, then reloads
    // the canonical entry. Requires a token; no-ops (with a hint) when signed out.
    private fun applyEdit(status: String? = null, score: Double? = null, progress: Int? = null) {
        val authToken = token
        if (authToken == null) {
            _uiState.value = _uiState.value.copy(saveError = "Sign in to AniList to manage your list")
            return
        }
        _uiState.value = _uiState.value.copy(
            isSaving = true,
            saveError = null,
            // Optimistic local update so the control reflects immediately.
            onList = true,
            listStatus = status ?: _uiState.value.listStatus,
            listScore = score ?: _uiState.value.listScore,
            listProgress = progress ?: _uiState.value.listProgress,
        )
        viewModelScope.launch {
            runCatching { aniListClient.saveMediaListEntry(authToken, mediaId, status, score, progress) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isSaving = false, saveError = null)
                    loadViewerEntry(authToken)
                }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        saveError = failure.message ?: "Couldn't update your list",
                    )
                    // Re-sync to whatever AniList actually holds after a failed write.
                    loadViewerEntry(authToken)
                }
        }
    }
}
