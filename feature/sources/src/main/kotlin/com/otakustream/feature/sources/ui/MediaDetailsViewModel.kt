package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.sources.api.Episode
import com.otakustream.core.sources.api.MediaDetails
import com.otakustream.core.sources.api.MediaItem
import com.otakustream.feature.sources.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MediaDetailsUiState(
    val isLoading: Boolean = false,
    val details: MediaDetails? = null,
    val episodes: List<Episode> = emptyList(),
    val resolvedVideoUrl: String? = null,
    val error: String? = null,
)

@HiltViewModel
class MediaDetailsViewModel @Inject constructor(
    private val sourceRepository: SourceRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediaDetailsUiState())
    val uiState: StateFlow<MediaDetailsUiState> = _uiState.asStateFlow()

    private var loadedFor: Pair<Long, String>? = null
    private var loadJob: Job? = null
    private var playJob: Job? = null

    fun load(sourceId: Long, mediaUrl: String, mediaTitle: String) {
        if (loadedFor == sourceId to mediaUrl) return
        loadedFor = sourceId to mediaUrl
        loadJob?.cancel()

        val source = sourceRepository.getSource(sourceId)
        if (source == null) {
            _uiState.value = _uiState.value.copy(error = "Source not found")
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true)
        loadJob = viewModelScope.launch {
            runCatching {
                val media = MediaItem(url = mediaUrl, title = mediaTitle)
                val details = source.getMediaDetails(media)
                val episodes = source.getEpisodeList(media)
                details to episodes
            }.onSuccess { (details, episodes) ->
                _uiState.value = _uiState.value.copy(isLoading = false, details = details, episodes = episodes)
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = error.message)
            }
        }
    }

    fun playEpisode(sourceId: Long, episode: Episode) {
        val source = sourceRepository.getSource(sourceId) ?: return
        playJob?.cancel()
        playJob = viewModelScope.launch {
            runCatching { source.getVideoList(episode) }
                .onSuccess { videos ->
                    val url = videos.firstOrNull()?.url
                    _uiState.value = _uiState.value.copy(resolvedVideoUrl = url, error = if (url == null) "No video found" else null)
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
        }
    }

    fun consumeResolvedVideoUrl() {
        _uiState.value = _uiState.value.copy(resolvedVideoUrl = null)
    }
}
