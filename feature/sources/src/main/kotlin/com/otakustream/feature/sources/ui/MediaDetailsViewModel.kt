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
import com.otakustream.core.sources.api.VideoSource
import com.otakustream.feature.sources.FailureReason
import com.otakustream.feature.sources.SourceFailure
import com.otakustream.feature.sources.PeerSource
import com.otakustream.feature.sources.SourceRepository
import com.otakustream.feature.sources.StreamOption
import com.otakustream.feature.sources.describe
import com.otakustream.feature.sources.detail
import com.otakustream.feature.sources.findPeerSources
import com.otakustream.feature.sources.peerEpisodeFor
import com.otakustream.feature.sources.sortedBestFirst
import com.otakustream.feature.sources.streamOptionsFrom
import com.otakustream.feature.sources.toFailureReason
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

enum class StreamAction { PLAY, DOWNLOAD }

// How long any one source gets to produce streams before the pool moves on without it. The same
// budget the catalog fan-out uses, and for a stronger reason: someone waiting on a play button is
// less patient than someone waiting on a grid, not more.
private const val STREAM_FETCH_TIMEOUT_MS = 15_000L

data class MediaDetailsUiState(
    val isLoading: Boolean = false,
    val details: MediaDetails? = null,
    val episodes: List<Episode> = emptyList(),
    val resolvedVideoUrl: String? = null,
    val error: String? = null,
    // The pooled stream list, best first. One list, gathered from every source linked to the same
    // AniList entry, rather than only the source whose page the user happens to be on.
    val pendingStreams: List<StreamOption> = emptyList(),
    val pendingEpisode: Episode? = null,
    // The source whose episode list is on screen — which is not necessarily the source that
    // produced the chosen stream, now that streams are pooled. Carried with the pending episode
    // rather than read back off the ViewModel's mutable `currentSourceId`, because the two roles
    // have to stay separable: this one decides where watch history and auto-play go.
    val pendingSourceId: Long = 0L,
    // Sources that have not answered yet. The sheet opens on the first stream that arrives and
    // fills in behind it, so this is what tells the user the list is still growing — without it a
    // short list looks like a complete one and they pick a 480p stream while the 1080p is in
    // flight.
    val sourcesSearching: Int = 0,
    // Sources that were asked and produced nothing, with the reason. Shown under the pooled list,
    // because "Torrentio timed out" is the difference between "this episode is hard to find" and
    // "look again in a minute".
    val streamFailures: List<SourceFailure> = emptyList(),
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

    // Ask everything that has this show, not just the source whose page is open.
    //
    // This is what the details screen used to do, in full: look up one source, call getVideoList,
    // show what came back. It made the source the user happened to be browsing the *only* source
    // that could play an episode — so a title present in four add-ons still failed if the one in
    // front of them was down, and a 1080p release sitting in another add-on was unreachable without
    // backing out and opening the same show again somewhere else.
    //
    // The join is the AniList link that already exists. Linking a title records
    // (mediaUrl, sourceId) against an AniList media id; several sources linked to the same id are,
    // by the user's own assertion, the same show. That assertion is the only reliable identity
    // there is here — titles differ between sources, romanised differently, with and without
    // "Season 2" — which is why the pool is built from links rather than from title matching.
    private fun resolveEpisode(sourceId: Long, episode: Episode, action: StreamAction) {
        val source = sourceRepository.getSource(sourceId) ?: return
        playJob?.cancel()
        // Immediate feedback: mark this episode as resolving so its row shows a spinner while the
        // (network) getVideoList runs, instead of looking like the tap did nothing.
        _uiState.update {
            it.copy(
                resolvingEpisodeUrl = episode.url,
                error = null,
                pendingAction = action,
                pendingEpisode = null,
                pendingSourceId = source.id,
                pendingStreams = emptyList(),
                streamFailures = emptyList(),
                sourcesSearching = 0,
            )
        }
        playJob = viewModelScope.launch {
            val peers = peersFor(source.id, episode)
            if (peers.isEmpty()) {
                resolveFromOneSource(source, episode, action)
            } else {
                poolStreams(source, peers, episode, action)
            }
        }
    }

    // The unpooled path, kept exactly as it was.
    //
    // Reached whenever the title is linked to nothing, or linked from only this source — which is
    // the common case for a show the user has just found. It matters that this stays the plain
    // one-source path: with a single source there is no list to assemble and no reason to make the
    // user tap through a sheet holding one entry.
    private suspend fun resolveFromOneSource(source: VideoSource, episode: Episode, action: StreamAction) {
        runCatching { streamOptionsFrom(source, episode) }
            .onSuccess { streams ->
                when {
                    // Named, because this path only ever asks one source: without the name the user
                    // cannot tell whether this episode is missing everywhere or just from the
                    // add-on they happen to be browsing.
                    streams.isEmpty() ->
                        _uiState.update {
                            it.copy(
                                error = "${source.name} has no playable stream for this episode.",
                                resolvingEpisodeUrl = null,
                            )
                        }
                    streams.size == 1 -> act(action, source.id, episode, streams.first())
                    else -> _uiState.update {
                        it.copy(
                            pendingStreams = streams.sortedBestFirst(),
                            pendingEpisode = episode,
                            resolvingEpisodeUrl = null,
                        )
                    }
                }
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                // Say what actually happened. "Something went wrong ... please try again" is
                // advice that is wrong half the time — a 403 or a dead endpoint will not fix
                // itself on a second tap, and the user needs to know to try another source.
                _uiState.update {
                    it.copy(
                        error = "Couldn't start playback: ${source.name} ${error.toFailureReason().describe()}.",
                        resolvingEpisodeUrl = null,
                    )
                }
            }
    }

    // Every source at once, with the sheet opening on the first answer rather than the last.
    //
    // Waiting for all of them would hand the slowest source control of when the user sees anything,
    // and a torrent indexer taking twelve seconds is ordinary. Streams are appended as they arrive
    // and re-sorted, so the list reorders under the user — which is the right trade only because
    // the count of sources still searching is on screen beside it, saying plainly that it will.
    private suspend fun poolStreams(
        primary: VideoSource,
        peers: List<PeerSource>,
        episode: Episode,
        action: StreamAction,
    ) {
        _uiState.update {
            it.copy(
                pendingEpisode = episode,
                sourcesSearching = peers.size + 1,
                // The row spinner hands over to the sheet here: the sheet is now the thing showing
                // that work is in progress, and two indicators for one wait is one too many.
                resolvingEpisodeUrl = null,
            )
        }
        coroutineScope {
            val gathers = buildList {
                add(async { gather(episode, primary.id, primary.name) { streamOptionsFrom(primary, episode) } })
                peers.forEach { peer ->
                    add(
                        async {
                            gather(episode, peer.source.id, peer.source.name) {
                                val match = peerEpisodeFor(peer, currentTitle, episode)
                                    // A source that lists the show but not this episode is not a
                                    // failed source, and saying so is the only way the user can
                                    // tell the two apart.
                                    ?: throw NoSuchEpisodeException(episode.episodeNumber)
                                streamOptionsFrom(peer.source, match)
                            }
                        },
                    )
                }
            }
            gathers.awaitAll()
        }
        // Only if this is still the resolution the screen is waiting on. Picking a stream or
        // dismissing the sheet cancels this job, but cancellation is not instantaneous, and the
        // last thing a dismissed sheet should do is reopen itself or overwrite a fresh error.
        _uiState.update { state ->
            if (state.pendingEpisode?.url != episode.url) {
                state
            } else if (state.pendingStreams.isEmpty()) {
                state.copy(
                    pendingEpisode = null,
                    sourcesSearching = 0,
                    error = noStreamsMessage(state.streamFailures),
                )
            } else {
                state.copy(sourcesSearching = 0)
            }
        }
    }

    // One source's answer, folded into the shared list as soon as it arrives.
    //
    // Best-effort per source, for the same reason the catalog fan-out is: one add-on being down
    // must not decide whether the episode plays. The timeout is what makes that true rather than
    // aspirational — without it a source that accepts the connection and never answers holds the
    // "still searching" count above zero forever.
    private suspend fun gather(
        episode: Episode,
        sourceId: Long,
        sourceName: String,
        block: suspend () -> List<StreamOption>,
    ) {
        val reason = try {
            val streams = withTimeoutOrNull(STREAM_FETCH_TIMEOUT_MS) { block() }
            when {
                streams == null -> FailureReason.Timeout(STREAM_FETCH_TIMEOUT_MS)
                streams.isEmpty() -> FailureReason.Unknown("returned no streams")
                else -> {
                    appendStreams(episode, streams)
                    null
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (error is NoSuchEpisodeException) {
                FailureReason.NoSuchEpisode(error.episodeNumber)
            } else {
                error.toFailureReason()
            }
        }
        _uiState.update { state ->
            if (state.pendingEpisode?.url != episode.url) {
                state
            } else {
                state.copy(
                    sourcesSearching = (state.sourcesSearching - 1).coerceAtLeast(0),
                    streamFailures = if (reason == null) {
                        state.streamFailures
                    } else {
                        state.streamFailures + SourceFailure(sourceId, sourceName, reason)
                    },
                )
            }
        }
    }

    // Merged and re-sorted on every arrival, deduplicated by url.
    //
    // The dedup is not cosmetic: two indexers routinely return the same torrent, and a
    // `torrent://<hash>/<index>` url is the same string whichever add-on produced it, so the
    // duplicate would otherwise be a second identical row that plays exactly the same bytes.
    // First writer wins, so the source that answered fastest is the one credited.
    private fun appendStreams(episode: Episode, streams: List<StreamOption>) {
        _uiState.update { state ->
            if (state.pendingEpisode?.url != episode.url) {
                state
            } else {
                state.copy(
                    pendingStreams = (state.pendingStreams + streams)
                        .distinctBy { it.video.url }
                        .sortedBestFirst(),
                )
            }
        }
    }

    // Thrown by a peer gather when the source lists this show but not this episode. An exception
    // rather than a nullable return so it travels through the same timeout-and-classify path as
    // every other outcome, instead of needing a second result channel alongside it.
    private class NoSuchEpisodeException(val episodeNumber: Float) : Exception()

    // Peers for the episode being resolved, or none if anything about assembling the list goes
    // wrong.
    //
    // Best-effort deliberately: this is an enhancement to playback, and a database or registry
    // hiccup while working out who else has the show must degrade to "the source you are on",
    // never to a failure to play.
    private suspend fun peersFor(primarySourceId: Long, episode: Episode): List<PeerSource> {
        val mediaUrl = currentMediaUrl.value ?: return emptyList()
        return runCatchingCancellable {
            findPeerSources(sourceRepository, trackingRepository, mediaUrl, primarySourceId, episode.season)
        }.getOrElse { emptyList() }
    }

    // Every source was asked and none produced a stream. What each of them said is the entire
    // content of this message: "no streams" alone is the state the pooling was built to stop
    // being the whole story.
    private fun noStreamsMessage(failures: List<SourceFailure>): String = when {
        failures.isEmpty() -> "No stream found for this episode."
        else -> "No stream found — ${failures.detail()}."
    }

    // Called once the user picks a stream from the picker sheet (or immediately by
    // resolveFromOneSource when there's only one option, so there's nothing to choose).
    fun selectStream(option: StreamOption) {
        val state = _uiState.value
        val episode = state.pendingEpisode ?: return
        // Nothing left to search for: the choice is made, and the sources still in flight would
        // only be appending to a list nobody is looking at.
        playJob?.cancel()
        _uiState.value = state.copy(
            pendingStreams = emptyList(),
            pendingEpisode = null,
            sourcesSearching = 0,
            streamFailures = emptyList(),
        )
        act(state.pendingAction, state.pendingSourceId, episode, option)
    }

    // The chosen stream may have come from a different source than the one on screen, and the two
    // roles are deliberately kept apart. The stream's own source is what produced the url, so it is
    // what a download records. Everything about *position in the show* — watch history, the AniList
    // entry, and which episode auto-play advances to — stays with the source whose episode list is
    // on screen, because that list is what the user is moving through.
    private fun act(
        action: StreamAction,
        primarySourceId: Long,
        episode: Episode,
        option: StreamOption,
    ) = when (action) {
        StreamAction.PLAY -> playVideo(primarySourceId, episode, option.video)
        StreamAction.DOWNLOAD -> enqueueDownload(option.sourceId, episode, option.video)
    }

    fun dismissVideoPicker() {
        // Dismissing stops the search. Leaving it running would keep several sources fetching for a
        // sheet that is gone, and on a metered connection that is the user's data.
        playJob?.cancel()
        _uiState.update {
            it.copy(
                pendingStreams = emptyList(),
                pendingEpisode = null,
                sourcesSearching = 0,
                streamFailures = emptyList(),
                resolvingEpisodeUrl = null,
            )
        }
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
                trackingRepository = trackingRepository,
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
    // Captured for the same reason `sources`, `library` and `tracking` already are: the resolver
    // outlives the screen, so it may hold app-scoped collaborators and nothing else. This one holds
    // a DAO and the token store, both backed by the singleton database — no Context, and no path
    // back to the ViewModel.
    trackingRepository: TrackingRepository,
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
        val video = resolveAutoPlayVideo(sources, trackingRepository, source, mediaUrl, mediaTitle, next)
            ?: return@resolver null

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
                nextVideoResolver(
                    sources = sources,
                    library = library,
                    tracking = tracking,
                    episodes = episodes,
                    current = next,
                    sourceId = sourceId,
                    mediaUrl = mediaUrl,
                    mediaTitle = mediaTitle,
                    coverUrl = coverUrl,
                    trackingRepository = trackingRepository,
                ),
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

// The stream auto-play uses for the next episode.
//
// Deliberately not the full pooled fan-out the play button runs. The user is at the end of an
// episode with nothing on screen to explain a wait, so the source they were already watching is
// asked first and its answer is used the moment it arrives — the pool is a fallback for when that
// source has nothing, not a step in front of every episode. That is the case that used to end the
// session: one add-on missing episode 9 stopped a linked show that three other sources could have
// continued.
//
// `sortedBestFirst` on the primary's own list is an improvement on its own. The previous
// `.firstOrNull()` took whatever the source happened to list first, which for a scripted extension
// is whatever order its page was written in.
//
// Nothing here can present a picker — there is no one looking at a screen — so this is the one
// place the app does choose a stream on the user's behalf, and it says so rather than pretending
// the choice was theirs.
private suspend fun resolveAutoPlayVideo(
    sources: SourceRepository,
    tracking: TrackingRepository,
    primary: VideoSource,
    mediaUrl: String,
    mediaTitle: String,
    next: Episode,
): Video? {
    runCatchingCancellable { streamOptionsFrom(primary, next) }
        .getOrElse { emptyList() }
        .sortedBestFirst()
        .firstOrNull()
        ?.let { return it.video }

    val peers = runCatchingCancellable {
        findPeerSources(sources, tracking, mediaUrl, primary.id, next.season)
    }.getOrElse { emptyList() }
    if (peers.isEmpty()) return null

    // Concurrent and bounded, for the same reason the play-button pool is: this runs between two
    // episodes, and one unresponsive add-on must not be able to hold the gap open indefinitely.
    return coroutineScope {
        peers.map { peer ->
            async {
                runCatchingCancellable {
                    withTimeoutOrNull(STREAM_FETCH_TIMEOUT_MS) {
                        val match = peerEpisodeFor(peer, mediaTitle, next) ?: return@withTimeoutOrNull emptyList()
                        streamOptionsFrom(peer.source, match)
                    }.orEmpty()
                }.getOrElse { emptyList() }
            }
        }.awaitAll().flatten().sortedBestFirst().firstOrNull()?.video
    }
}
