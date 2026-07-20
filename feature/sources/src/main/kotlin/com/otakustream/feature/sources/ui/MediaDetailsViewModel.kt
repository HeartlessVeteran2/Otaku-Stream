package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.database.library.LibraryEntry
import com.otakustream.core.database.library.LibraryRepository
import com.otakustream.core.database.library.WatchHistoryEntry
import com.otakustream.core.database.tracking.TrackerLink
import com.otakustream.core.database.tracking.TrackingRepository
import com.otakustream.core.sources.api.Episode
import com.otakustream.core.sources.api.MediaDetails
import com.otakustream.core.sources.api.MediaItem
import com.otakustream.core.sources.api.PendingPlayback
import com.otakustream.core.sources.api.PlaybackQueue
import com.otakustream.core.sources.api.SkipMark
import com.otakustream.core.sources.api.Video
import com.otakustream.feature.sources.SourceRepository
import com.otakustream.feature.tracking.AniListClient
import com.otakustream.feature.tracking.AniSkipClient
import com.otakustream.feature.tracking.TrackingManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MediaDetailsUiState(
    val isLoading: Boolean = false,
    val details: MediaDetails? = null,
    val episodes: List<Episode> = emptyList(),
    val resolvedVideoUrl: String? = null,
    val error: String? = null,
    val pendingVideoChoices: List<Video> = emptyList(),
    val pendingEpisode: Episode? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MediaDetailsViewModel @Inject constructor(
    private val sourceRepository: SourceRepository,
    private val libraryRepository: LibraryRepository,
    private val trackingRepository: TrackingRepository,
    private val trackingManager: TrackingManager,
    private val aniListClient: AniListClient,
    private val aniSkipClient: AniSkipClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediaDetailsUiState())
    val uiState: StateFlow<MediaDetailsUiState> = _uiState.asStateFlow()

    private val currentMediaUrl = MutableStateFlow<String?>(null)
    private var currentTitle: String = ""
    private var currentSourceId: Long = 0L

    val inLibrary: StateFlow<Boolean> = currentMediaUrl
        .flatMapLatest { url -> if (url == null) flowOf(false) else libraryRepository.observeInLibrary(url) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Episodes the user has started (recordWatch fires at play start) — drives the checkmarks
    // on episode rows. Reactive, so returning from playback updates the list with no extra wiring.
    val watchedEpisodeUrls: StateFlow<Set<String>> = currentMediaUrl
        .flatMapLatest { url ->
            if (url == null) flowOf(emptyList()) else libraryRepository.observeWatchedEpisodeUrls(url)
        }
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val trackerLink: StateFlow<TrackerLink?> = currentMediaUrl
        .flatMapLatest { url -> if (url == null) flowOf(null) else trackingRepository.observeLink(url) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Whether the user is signed in to AniList at all — the "Link to AniList" affordance only
    // makes sense once they are.
    val hasTrackerToken: StateFlow<Boolean> = trackingRepository.observeToken()
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var loadedFor: Pair<Long, String>? = null
    private var loadJob: Job? = null
    private var playJob: Job? = null

    private val _autoPlayEnabled = MutableStateFlow(PlaybackQueue.autoPlayEnabled)
    val autoPlayEnabled: StateFlow<Boolean> = _autoPlayEnabled.asStateFlow()

    fun setAutoPlayEnabled(enabled: Boolean) {
        PlaybackQueue.autoPlayEnabled = enabled
        _autoPlayEnabled.value = enabled
    }

    fun load(sourceId: Long, mediaUrl: String, mediaTitle: String) {
        currentMediaUrl.value = mediaUrl
        currentTitle = mediaTitle
        currentSourceId = sourceId
        if (loadedFor == sourceId to mediaUrl) return
        loadedFor = sourceId to mediaUrl
        loadJob?.cancel()

        val source = sourceRepository.getSource(sourceId)
        if (source == null) {
            _uiState.value = _uiState.value.copy(error = "This source is no longer available.")
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        loadJob = viewModelScope.launch {
            runCatching {
                val media = MediaItem(url = mediaUrl, title = mediaTitle)
                val details = source.getMediaDetails(media)
                val episodes = source.getEpisodeList(media)
                details to episodes
            }.onSuccess { (details, episodes) ->
                _uiState.value = _uiState.value.copy(isLoading = false, details = details, episodes = episodes)
            }.onFailure { error ->
                // A newer load() cancels this job — let cancellation propagate rather than
                // showing a stale error over the new load.
                if (error is CancellationException) throw error
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Couldn't load this title. Check your connection and try again.",
                )
            }
        }
    }

    // Re-run the last load() after a failure (the "Retry" affordance on the details error state).
    fun retryLoad() {
        val url = currentMediaUrl.value ?: return
        loadedFor = null
        load(currentSourceId, url, currentTitle)
    }

    fun toggleWatchlist() {
        val mediaUrl = currentMediaUrl.value ?: return
        viewModelScope.launch {
            if (inLibrary.value) {
                libraryRepository.remove(mediaUrl)
            } else {
                libraryRepository.add(
                    LibraryEntry(
                        mediaUrl = mediaUrl,
                        sourceId = currentSourceId,
                        title = currentTitle,
                        coverUrl = _uiState.value.details?.media?.coverUrl,
                        addedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    fun playEpisode(sourceId: Long, episode: Episode) {
        val source = sourceRepository.getSource(sourceId) ?: return
        playJob?.cancel()
        playJob = viewModelScope.launch {
            runCatching { source.getVideoList(episode) }
                .onSuccess { videos ->
                    when {
                        videos.isEmpty() ->
                            _uiState.value = _uiState.value.copy(error = "No playable stream was found for this episode.")
                        videos.size == 1 -> playVideo(sourceId, episode, videos.first())
                        else -> _uiState.value = _uiState.value.copy(pendingVideoChoices = videos, pendingEpisode = episode)
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.value = _uiState.value.copy(error = "Something went wrong starting playback. Please try again.")
                }
        }
    }

    // Called once the user picks a stream from the picker sheet (or immediately by playEpisode
    // when there's only one option, so there's nothing to choose).
    fun selectVideo(video: Video) {
        val episode = _uiState.value.pendingEpisode ?: return
        val sourceId = currentSourceId
        _uiState.value = _uiState.value.copy(pendingVideoChoices = emptyList(), pendingEpisode = null)
        playVideo(sourceId, episode, video)
    }

    fun dismissVideoPicker() {
        _uiState.value = _uiState.value.copy(pendingVideoChoices = emptyList(), pendingEpisode = null)
    }

    private fun playVideo(sourceId: Long, episode: Episode, video: Video) {
        PendingPlayback.stash(video, skipLookup = buildSkipLookup(episode))
        PlaybackQueue.setNextResolver { resolveNextVideo(sourceId, episode) }
        _uiState.value = _uiState.value.copy(resolvedVideoUrl = video.url, error = null)
        recordWatchAndSync(episode)
    }

    // For AniList-linked shows, hand the player a closure that resolves AniSkip intro/outro/recap
    // timings once the real duration is known. Best-effort: no link or no episode number → null
    // (the player just falls back to any manual skip markers).
    private fun buildSkipLookup(episode: Episode): (suspend (Long) -> List<SkipMark>)? {
        val link = trackerLink.value ?: return null
        val episodeNumber = episode.episodeNumber.toInt()
        if (episodeNumber < 1) return null
        // Capture only the singletons and primitives the lookup needs — never `this`. The
        // singleton PlayerController holds this closure for the life of the playback, so
        // capturing the ViewModel would leak it. AniListClient caches the MAL id internally.
        val aniList = aniListClient
        val aniSkip = aniSkipClient
        val trackerMediaId = link.trackerMediaId
        return { durationMs ->
            runCatching {
                val malId = aniList.getMalId(trackerMediaId)
                if (malId == null) {
                    emptyList()
                } else {
                    aniSkip.fetch(malId, episodeNumber, durationMs / 1000)
                        .map { SkipMark(it.startMs, it.endMs, it.kind) }
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                emptyList()
            }
        }
    }

    // Resolves the episode after currentEpisode (by list order, matching what's displayed) and
    // re-arms the resolver for the one after that, so auto-play chains through the whole list.
    private suspend fun resolveNextVideo(sourceId: Long, currentEpisode: Episode): Video? {
        val episodes = _uiState.value.episodes
        val currentIndex = episodes.indexOfFirst { it.url == currentEpisode.url }
        val next = episodes.getOrNull(currentIndex + 1) ?: return null
        val source = sourceRepository.getSource(sourceId) ?: return null
        val video = source.getVideoList(next).firstOrNull() ?: return null
        PlaybackQueue.setNextResolver { resolveNextVideo(sourceId, next) }
        recordWatchAndSync(next)
        return video
    }

    private fun recordWatchAndSync(episode: Episode) {
        val mediaUrl = currentMediaUrl.value ?: return
        viewModelScope.launch {
            libraryRepository.recordWatch(
                WatchHistoryEntry(
                    sourceId = currentSourceId,
                    mediaUrl = mediaUrl,
                    mediaTitle = currentTitle,
                    episodeUrl = episode.url,
                    episodeName = episode.name,
                    episodeNumber = episode.episodeNumber,
                    watchedAtEpochMs = System.currentTimeMillis(),
                    coverUrl = _uiState.value.details?.media?.coverUrl,
                ),
            )
            trackingManager.onEpisodeWatched(mediaUrl, episode.episodeNumber)
        }
    }

    fun unlinkTracker() {
        val mediaUrl = currentMediaUrl.value ?: return
        viewModelScope.launch { trackingRepository.removeLink(mediaUrl) }
    }

    fun consumeResolvedVideoUrl() {
        _uiState.value = _uiState.value.copy(resolvedVideoUrl = null)
    }
}
