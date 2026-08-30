package com.otakustream.feature.sources.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.common.runCatchingCancellable
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
import com.otakustream.core.database.download.DownloadEntry
import com.otakustream.core.database.download.DownloadRepository
import com.otakustream.core.download.DownloadHeaders
import com.otakustream.core.download.EpisodeDownloads
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class StreamAction { PLAY, DOWNLOAD }

data class MediaDetailsUiState(
    val isLoading: Boolean = false,
    val details: MediaDetails? = null,
    val episodes: List<Episode> = emptyList(),
    val resolvedVideoUrl: String? = null,
    val error: String? = null,
    val pendingVideoChoices: List<Video> = emptyList(),
    val pendingEpisode: Episode? = null,
    // What the stream picker will do with the choice. The same sheet serves both, because picking a
    // quality matters more for a download than for a stream — it is the one that occupies the
    // device until the user deletes it.
    val pendingAction: StreamAction = StreamAction.PLAY,
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
    private val downloadRepository: DownloadRepository,
    private val episodeDownloads: EpisodeDownloads,
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

    // Episodes with a download recorded, keyed by the episode's own url. Same shape and same
    // reason as watchedEpisodeUrls above: a row has to render its state without resolving the
    // stream, which would be a network round trip per row.
    //
    // Presence here means "the user asked for this", not "it finished" — the finished/failed
    // distinction comes from Media3's index, which is the component that actually knows.
    val downloadedEpisodeUrls: StateFlow<Set<String>> = currentMediaUrl
        .flatMapLatest { url ->
            if (url == null) flowOf(emptyList()) else downloadRepository.observeForMedia(url)
        }
        .map { entries -> entries.mapTo(mutableSetOf()) { it.episodeUrl } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // Which season the user is looking at. Hoisted out of the screen because it now decides more
    // than which episodes are listed: AniList models each season as its own media entry, so it also
    // decides which entry the link row and the list editor target (issue #9). It deliberately does
    // *not* decide where watch progress goes — that follows the played episode's own season, since
    // auto-play can carry playback out of the season the user was looking at (registerAniListSync).
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
        if (url == null) {
            flowOf(null)
        } else {
            // Clear first: a StateFlow keeps its last value while the new Room query runs, so
            // without this the row would still show — and Unlink would still target — the previous
            // season's link for the moment after switching seasons.
            trackingRepository.observeLink(url, season.toTrackerSeason()).onStart { emit(null) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
        // Navigating to a different title must drop the previous title's season selection, or the
        // link row and the progress push target a season the new title may not even have. The
        // screen's own effect can't catch this — it keys on the derived season list, and two titles
        // with the same seasons produce an identical list, so it never re-fires. Comparing the url
        // (not `loadedFor`) keeps retryLoad() from clearing a selection on the same title.
        if (currentMediaUrl.value != mediaUrl) _selectedSeason.value = null
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

    fun playEpisode(sourceId: Long, episode: Episode) = resolveEpisode(sourceId, episode, StreamAction.PLAY)

    // Same resolve as playing: a download needs the stream url, and only the source can produce it.
    fun downloadEpisode(sourceId: Long, episode: Episode) =
        resolveEpisode(sourceId, episode, StreamAction.DOWNLOAD)

    // Every row for the episode, not the first one.
    //
    // Sources routinely hand back signed or rotating stream urls, so downloading an episode twice
    // produces two rows with different videoUrls. Cancelling only one left the other downloading,
    // with the episode still showing as saved and no way to reach the leftover.
    fun cancelDownload(episode: Episode) {
        viewModelScope.launch { clearDownloadsFor(episode.url) }
    }

    private suspend fun clearDownloadsFor(episodeUrl: String) {
        downloadRepository.entriesForEpisode(episodeUrl).forEach { entry ->
            episodeDownloads.remove(entry.videoUrl)
            downloadRepository.forget(entry.videoUrl)
        }
    }

    private fun resolveEpisode(sourceId: Long, episode: Episode, action: StreamAction) {
        val source = sourceRepository.getSource(sourceId) ?: return
        playJob?.cancel()
        // Immediate feedback: mark this episode as resolving so its row shows a spinner while the
        // (network) getVideoList runs, instead of looking like the tap did nothing.
        _uiState.value = _uiState.value.copy(
            resolvingEpisodeUrl = episode.url,
            error = null,
            pendingAction = action,
        )
        playJob = viewModelScope.launch {
            runCatching { source.getVideoList(episode) }
                .onSuccess { videos ->
                    when {
                        videos.isEmpty() ->
                            _uiState.value = _uiState.value.copy(
                                error = "No playable stream was found for this episode.",
                                resolvingEpisodeUrl = null,
                            )
                        videos.size == 1 -> when (action) {
                            StreamAction.PLAY -> playVideo(sourceId, episode, videos.first())
                            StreamAction.DOWNLOAD -> enqueueDownload(sourceId, episode, videos.first())
                        }
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
        val state = _uiState.value
        val episode = state.pendingEpisode ?: return
        val sourceId = currentSourceId
        _uiState.value = state.copy(pendingVideoChoices = emptyList(), pendingEpisode = null)
        when (state.pendingAction) {
            StreamAction.PLAY -> playVideo(sourceId, episode, video)
            StreamAction.DOWNLOAD -> enqueueDownload(sourceId, episode, video)
        }
    }

    fun dismissVideoPicker() {
        _uiState.value = _uiState.value.copy(pendingVideoChoices = emptyList(), pendingEpisode = null)
    }

    // Records what the episode is called before handing the url to the downloader.
    //
    // Written first, and deliberately: Media3's index knows only a url, so if the app crashed
    // between starting the download and storing the metadata, the Downloads list would show an
    // entry it could not name. Writing first means the worst case is a named row for a download
    // that never started, which the state join renders as failed — recoverable and legible.
    private fun enqueueDownload(sourceId: Long, episode: Episode, video: Video) {
        val mediaUrl = currentMediaUrl.value ?: return
        val title = _uiState.value.details?.media?.title ?: return
        _uiState.value = _uiState.value.copy(resolvingEpisodeUrl = null)
        viewModelScope.launch {
            // One download per episode. Re-downloading after the source rotated its stream url
            // would otherwise leave the previous attempt on disk with nothing pointing at it.
            clearDownloadsFor(episode.url)
            downloadRepository.remember(
                DownloadEntry(
                    videoUrl = video.url,
                    mediaUrl = mediaUrl,
                    episodeUrl = episode.url,
                    sourceId = sourceId,
                    mediaTitle = title,
                    episodeName = episode.name,
                    episodeNumber = episode.episodeNumber,
                    coverUrl = _uiState.value.details?.media?.coverUrl,
                    requestedAtEpochMs = System.currentTimeMillis(),
                    headersJson = DownloadHeaders.encode(video.headers),
                    isM3U8 = video.isM3U8,
                ),
            )
            episodeDownloads.start(video.url, isM3U8 = video.isM3U8, headers = video.headers)
        }
    }

    private fun playVideo(sourceId: Long, episode: Episode, video: Video) {
        PendingPlayback.stash(video, skipLookup = buildSkipLookup(episode))
        installNextResolver(sourceId, episode)
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
    // Hands PlaybackQueue a resolver that captures singletons and primitives only — never `this`.
    //
    // It used to be `PlaybackQueue.setNextResolver { resolveNextVideo(sourceId, episode) }`, a
    // method reference that captured the ViewModel into an app-scoped registry. The leak was the
    // lesser problem. The real one: the resolver called recordLocalWatch, which launches on
    // viewModelScope — and by the time auto-play resolves the next episode the user has left the
    // details screen, so that scope is cancelled and the launch does nothing. Every auto-played
    // episode was silently missing from watch history, so Continue Watching stayed parked on the
    // episode the user had started by hand. buildSkipLookup below already avoids exactly this.
    private fun installNextResolver(sourceId: Long, episode: Episode) {
        val mediaUrl = currentMediaUrl.value ?: return
        // A snapshot of the list as it was when playback started. Auto-play must keep advancing
        // through the same episode list even after the screen that loaded it is gone, and reading
        // _uiState later would be reading a ViewModel that no longer has anyone updating it.
        val episodes = _uiState.value.episodes
        val currentIndex = episodes.indexOfFirst { it.url == episode.url }
        if (currentIndex < 0 || currentIndex + 1 !in episodes.indices) {
            // Nothing follows this episode, so there is nothing to resolve. Installing anyway is
            // what put a dead Next button in the player controls: hasResolver() is the only thing
            // driving that button, and a resolver that can only ever return null still counts.
            // Clearing rather than leaving it alone matters too — whatever the *previous* playback
            // installed is still armed, and it points into a different show's episode list.
            PlaybackQueue.clear()
            return
        }
        PlaybackQueue.setNextResolver(
            nextVideoResolver(
                sources = sourceRepository,
                library = libraryRepository,
                tracking = trackingManager,
                episodes = episodes,
                current = episode,
                sourceId = sourceId,
                mediaUrl = mediaUrl,
                mediaTitle = currentTitle,
                coverUrl = _uiState.value.details?.media?.coverUrl,
            ),
        )
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

    // Unlinks exactly the row the UI is showing. The season comes from the resolved link itself
    // rather than from the selection, so this can't disagree with what's on screen: if the row is
    // displaying the whole-series fallback, that's what gets removed; if it's displaying the
    // season's own link, that's what gets removed. Reading it off a second flow would let the two
    // drift apart between emissions and delete the wrong one.
    fun unlinkTracker() {
        val mediaUrl = currentMediaUrl.value ?: return
        val season = trackerLink.value?.season ?: return
        viewModelScope.launch { trackingRepository.removeLink(mediaUrl, season) }
    }

    fun setAniListStatus(status: String) = applyAniListEdit(status = status)

    fun setAniListScore(score: Double) = applyAniListEdit(score = score.coerceIn(0.0, 10.0))

    fun setAniListProgress(progress: Int) = applyAniListEdit(progress = progress.coerceAtLeast(0))

    // True while `mediaId` is still the AniList entry the screen is pointing at. Every write to
    // _aniListEntry from an in-flight request is gated on this: switching season (or title) retargets
    // the editor at a different AniList media, and a request that started before the switch must not
    // land its result on the new season's editor.
    private fun stillEditing(mediaId: Long) = trackerLink.value?.trackerMediaId == mediaId

    private suspend fun loadAniListEntry(mediaId: Long, token: String) {
        runCatching { aniListClient.fetchViewerListEntry(token, mediaId) }
            .onSuccess { entry ->
                if (!stillEditing(mediaId)) return
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
                    // The save itself still went through for the right AniList entry — only the
                    // on-screen editor state is skipped, because it now belongs to another entry.
                    if (!stillEditing(mediaId)) return@launch
                    _aniListEntry.value = _aniListEntry.value.copy(isSaving = false, saveError = null)
                    loadAniListEntry(mediaId, token)
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    if (!stillEditing(mediaId)) return@launch
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

// Deliberately top level rather than a method: a resolver built here *cannot* capture a ViewModel,
// because there is no ViewModel in scope to capture. PlaybackQueue is app-scoped and holds whatever
// it is given until the next playback replaces it, so that guarantee is worth making structural
// instead of relying on a comment.
//
// The suspend body runs on PlayerController's own scope, which lives as long as the process — which
// is why recordWatch is awaited here rather than launched on viewModelScope. That launch was the
// bug: by the time auto-play calls this, the details screen is gone and its scope is cancelled.
private fun nextVideoResolver(
    sources: SourceRepository,
    library: LibraryRepository,
    tracking: TrackingManager,
    episodes: List<Episode>,
    current: Episode,
    sourceId: Long,
    mediaUrl: String,
    mediaTitle: String,
    coverUrl: String?,
): suspend () -> Video? {
    // The resolver has to be able to name itself: re-arming the chain is only correct while this
    // resolver is still the one PlaybackQueue holds (see replaceResolverIfCurrent), and identity is
    // the check. A lambda cannot refer to itself as it is being built, so it is handed its own
    // reference immediately afterwards — set long before anything can invoke it.
    val self = java.util.concurrent.atomic.AtomicReference<suspend () -> Video?>()
    val resolver: suspend () -> Video? = resolver@{
        val currentIndex = episodes.indexOfFirst { it.url == current.url }
        val next = episodes.getOrNull(currentIndex + 1)
        if (next == null) {
            // End of the captured list. Retire the chain so the Next button stops offering an
            // episode that does not exist, but only if this is still the armed resolver.
            self.get()?.let { PlaybackQueue.replaceResolverIfCurrent(it, null) }
            return@resolver null
        }
        val source = sources.getSource(sourceId) ?: return@resolver null
        val video = source.getVideoList(next).firstOrNull() ?: return@resolver null

        // Chain on to the episode after this one, with the same captured list — unless the user
        // started something else while getVideoList was in flight, in which case that newer
        // playback owns the queue and this chain is done.
        //
        // This stops a stale chain re-arming over a newer one. It does *not* make the video returned
        // below safe on its own: ownership can change again between here and the return, and no
        // check placed inside a resolver can close that gap. PlaybackQueue.resolveNext is what does,
        // by re-checking the chain once this has returned.
        val stillArmed = self.get()?.let { me ->
            PlaybackQueue.replaceResolverIfCurrent(
                me,
                nextVideoResolver(sources, library, tracking, episodes, next, sourceId, mediaUrl, mediaTitle, coverUrl),
            )
        } ?: false
        if (!stillArmed) return@resolver null

        // Local history at resolve time, so Continue Watching and the episode checkmarks advance
        // with auto-play. The AniList sync stays deferred to the player reporting this episode
        // finished — it must never fire the instant the next stream merely resolves.
        //
        // Guarded, because awaiting it here put it on autoplay's critical path in a way the old
        // fire-and-forget launch never did: PlaybackQueue.resolveNext turns any throw from this
        // resolver into null, and playNext reads null as "there is no next episode". Unguarded, one
        // failed database write would stop the episode after it from playing at all, even though its
        // stream had already resolved. A missing history row is worth far less than continuing
        // playback.
        runCatchingCancellable {
            library.recordWatch(
                WatchHistoryEntry(
                    sourceId = sourceId,
                    mediaUrl = mediaUrl,
                    mediaTitle = mediaTitle,
                    episodeUrl = next.url,
                    episodeName = next.name,
                    episodeNumber = next.episodeNumber,
                    watchedAtEpochMs = System.currentTimeMillis(),
                    coverUrl = coverUrl,
                ),
            )
        }.onFailure { Log.w("MediaDetailsViewModel", "Could not record the auto-played episode", it) }
        val episodeNumber = next.episodeNumber
        val season = next.season
        PlaybackCompletion.register(video.url) {
            tracking.onEpisodeWatched(mediaUrl, episodeNumber, season)
        }
        video
    }
    self.set(resolver)
    return resolver
}
