package com.otakustream.feature.sources.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.database.library.LibraryEntry
import com.otakustream.core.database.library.LibraryRepository
import com.otakustream.core.database.library.WatchHistoryEntry
import com.otakustream.core.database.tracking.TRACKER_SEASON_WHOLE_SERIES
import com.otakustream.core.database.tracking.TrackerLink
import com.otakustream.core.database.tracking.toTrackerSeason
import com.otakustream.core.database.tracking.TrackingRepository
import com.otakustream.core.sources.api.Episode
import com.otakustream.core.sources.api.MediaDetails
import com.otakustream.core.sources.api.MediaItem
import com.otakustream.core.sources.api.PendingPlayback
import com.otakustream.core.sources.api.PlaybackCompletion
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
    // The episode whose stream is currently being resolved (getVideoList in flight) — drives the
    // per-row spinner so a tap gives immediate feedback instead of feeling dead for a few seconds.
    val resolvingEpisodeUrl: String? = null,
)

// The viewer's AniList list entry for the currently-linked title, so the status/score/progress
// editor can live on this screen and edit the same entry as the AniList detail screen. Only
// meaningful when signed in and linked; otherwise it stays at its empty default.
data class AniListListState(
    val onList: Boolean = false,
    val status: String? = null,
    val score: Double? = null,
    val progress: Int = 0,
    val isSaving: Boolean = false,
    val saveError: String? = null,
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

    // The saved title's watch status (null when not in the library), driving the status selector.
    // One subscription, not two: Room invalidates per table, so an EXISTS query and a status query
    // against the same row both re-ran on every library write anywhere — and the status already
    // answers both questions, since a row exists exactly when its status is non-null.
    val libraryStatus: StateFlow<String?> = currentMediaUrl
        .flatMapLatest { url -> if (url == null) flowOf(null) else libraryRepository.observeStatus(url) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val inLibrary: StateFlow<Boolean> = libraryStatus
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Episodes the user has started (recordWatch fires at play start) — drives the checkmarks
    // on episode rows. Reactive, so returning from playback updates the list with no extra wiring.
    val watchedEpisodeUrls: StateFlow<Set<String>> = currentMediaUrl
        .flatMapLatest { url ->
            if (url == null) flowOf(emptyList()) else libraryRepository.observeWatchedEpisodeUrls(url)
        }
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // Which season the user is looking at. Hoisted out of the screen because it now decides more
    // than which episodes are listed: AniList models each season as its own media entry, so it also
    // decides which entry the link row targets and which one progress is pushed to (issue #9).
    // null until episodes load, and stays null for sources with no season data.
    private val _selectedSeason = MutableStateFlow<Int?>(null)
    val selectedSeason: StateFlow<Int?> = _selectedSeason.asStateFlow()

    fun selectSeason(season: Int?) {
        _selectedSeason.value = season
    }

    // The link that applies to what's on screen: the selected season's own, or the whole-series one
    // as a fallback. A single-season show and a source with no seasons both land on the fallback,
    // which is how this stays invisible for everything that isn't multi-season.
    val trackerLink: StateFlow<TrackerLink?> = combine(currentMediaUrl, _selectedSeason) { url, season ->
        url to season
    }.flatMapLatest { (url, season) ->
        if (url == null) flowOf(null) else trackingRepository.observeLink(url, season.toTrackerSeason())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Every season already linked for this title, so the UI can say which are done rather than
    // making the user click through each one to find out.
    val linkedSeasons: StateFlow<Set<Int>> = currentMediaUrl
        .flatMapLatest { url -> if (url == null) flowOf(emptyList()) else trackingRepository.observeLinksFor(url) }
        .map { links -> links.map { it.season }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // Whether the user is signed in to AniList at all — the "Link to AniList" affordance only
    // makes sense once they are.
    val hasTrackerToken: StateFlow<Boolean> = trackingRepository.observeToken()
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // The viewer's AniList list entry for the linked title, driving the on-screen status/score/
    // progress editor. Latest sign-in token, kept off the flow so the edit setters can read it.
    private val _aniListEntry = MutableStateFlow(AniListListState())
    val aniListEntry: StateFlow<AniListListState> = _aniListEntry.asStateFlow()

    @Volatile
    private var trackerToken: String? = null

    init {
        // Load (and reload) the viewer's AniList entry whenever the link or the sign-in token
        // changes, so the editor on this screen reflects — and writes to — the same AniList entry
        // as the dedicated AniList detail screen. collectLatest cancels an in-flight load if the
        // link/token changes again.
        viewModelScope.launch {
            combine(trackerLink, trackingRepository.observeToken()) { link, token -> link to token }
                .collectLatest { (link, token) ->
                    trackerToken = token
                    if (link == null || token.isNullOrBlank()) {
                        _aniListEntry.value = AniListListState()
                    } else {
                        loadAniListEntry(link.trackerMediaId, token)
                    }
                }
        }
    }

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
                // Dedupe by url: the episode list keys on url, and some sources list the same
                // episode url more than once (multi-server) — a duplicate key would crash the list.
                val episodes = source.getEpisodeList(media).distinctBy { it.url }
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

    fun setLibraryStatus(status: String) {
        val mediaUrl = currentMediaUrl.value ?: return
        viewModelScope.launch {
            libraryRepository.setStatus(mediaUrl, status)
            // Local Library is the source of truth; mirror the change up to AniList when linked.
            trackingManager.onLibraryStatusChanged(mediaUrl, status)
        }
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
        // Immediate feedback: mark this episode as resolving so its row shows a spinner while the
        // (network) getVideoList runs, instead of looking like the tap did nothing.
        _uiState.value = _uiState.value.copy(resolvingEpisodeUrl = episode.url, error = null)
        playJob = viewModelScope.launch {
            runCatching { source.getVideoList(episode) }
                .onSuccess { videos ->
                    when {
                        videos.isEmpty() ->
                            _uiState.value = _uiState.value.copy(
                                error = "No playable stream was found for this episode.",
                                resolvingEpisodeUrl = null,
                            )
                        videos.size == 1 -> playVideo(sourceId, episode, videos.first())
                        else -> _uiState.value = _uiState.value.copy(
                            pendingVideoChoices = videos,
                            pendingEpisode = episode,
                            resolvingEpisodeUrl = null,
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.value = _uiState.value.copy(
                        error = "Something went wrong starting playback. Please try again.",
                        resolvingEpisodeUrl = null,
                    )
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
        _uiState.value = _uiState.value.copy(resolvedVideoUrl = video.url, error = null, resolvingEpisodeUrl = null)
        recordLocalWatch(episode)
        registerAniListSync(video.url, episode)
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
        // Local history at resolve time (so Continue Watching / episode checkmarks update as
        // auto-play advances); the AniList sync waits for the player to report this episode
        // finished — it must never fire the instant the next stream merely resolves.
        recordLocalWatch(next)
        registerAniListSync(video.url, next)
        return video
    }

    // Records the episode in local watch history at play-start — this is what drives Continue
    // Watching and the episode-row checkmarks, so it stays eager. The AniList sync is separate
    // (registerAniListSync) and deliberately deferred to episode completion.
    private fun recordLocalWatch(episode: Episode) {
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
        }
    }

    // Defers the AniList progress push to when the player reports the stream watched to the end
    // (PlaybackCompletion), instead of firing at play-start. Captures only the singleton manager
    // and the primitives it needs — never `this` — so the app-scoped registry can't leak the VM.
    private fun registerAniListSync(videoUrl: String, episode: Episode) {
        val mediaUrl = currentMediaUrl.value ?: return
        val manager = trackingManager
        val episodeNumber = episode.episodeNumber
        // The episode's own season, not the selected one: auto-play-next can carry playback into a
        // different season than the user was looking at when they pressed play, and progress has to
        // land on the AniList entry for the episode that actually played.
        val season = episode.season
        PlaybackCompletion.register(videoUrl) {
            manager.onEpisodeWatched(mediaUrl, episodeNumber, season)
        }
    }

    // Unlinks what the row is currently showing. If the selected season has its own link, only that
    // one goes; otherwise this removes the whole-series link, which is what the row was displaying.
    fun unlinkTracker() {
        val mediaUrl = currentMediaUrl.value ?: return
        val season = _selectedSeason.value.toTrackerSeason()
        viewModelScope.launch {
            val hasOwnLink = season != TRACKER_SEASON_WHOLE_SERIES && season in linkedSeasons.value
            trackingRepository.removeLink(mediaUrl, if (hasOwnLink) season else TRACKER_SEASON_WHOLE_SERIES)
        }
    }

    fun setAniListStatus(status: String) = applyAniListEdit(status = status)

    fun setAniListScore(score: Double) = applyAniListEdit(score = score.coerceIn(0.0, 10.0))

    fun setAniListProgress(progress: Int) = applyAniListEdit(progress = progress.coerceAtLeast(0))

    private suspend fun loadAniListEntry(mediaId: Long, token: String) {
        runCatching { aniListClient.fetchViewerListEntry(token, mediaId) }
            .onSuccess { entry ->
                _aniListEntry.value = _aniListEntry.value.copy(
                    onList = entry != null,
                    status = entry?.status,
                    score = entry?.score,
                    progress = entry?.progress ?: 0,
                    isSaving = false,
                    saveError = null,
                )
            }
            .onFailure { if (it is CancellationException) throw it }
    }

    // Sends only the changed field to SaveMediaListEntry for the linked title, optimistically
    // reflects it, then reloads the canonical entry. Mirrors AniListDetailViewModel.applyEdit but
    // keyed on the current TrackerLink so it edits the same AniList entry from this screen.
    private fun applyAniListEdit(status: String? = null, score: Double? = null, progress: Int? = null) {
        val token = trackerToken
        val link = trackerLink.value
        if (token == null || link == null) {
            _aniListEntry.value = _aniListEntry.value.copy(saveError = "Sign in and link to AniList to manage your list")
            return
        }
        val mediaId = link.trackerMediaId
        val current = _aniListEntry.value
        _aniListEntry.value = current.copy(
            isSaving = true,
            saveError = null,
            onList = true,
            status = status ?: current.status,
            score = score ?: current.score,
            progress = progress ?: current.progress,
        )
        viewModelScope.launch {
            runCatching { aniListClient.saveMediaListEntry(token, mediaId, status, score, progress) }
                .onSuccess {
                    _aniListEntry.value = _aniListEntry.value.copy(isSaving = false, saveError = null)
                    loadAniListEntry(mediaId, token)
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    _aniListEntry.value = _aniListEntry.value.copy(
                        isSaving = false,
                        saveError = it.message ?: "Couldn't update your list",
                    )
                    loadAniListEntry(mediaId, token)
                }
        }
    }

    fun consumeResolvedVideoUrl() {
        _uiState.value = _uiState.value.copy(resolvedVideoUrl = null)
    }
}
