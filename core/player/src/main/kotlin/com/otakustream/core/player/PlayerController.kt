package com.otakustream.core.player

import android.content.Context
import android.content.Intent
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.otakustream.core.database.library.DIRECT_PLAY_SOURCE_ID
import com.otakustream.core.database.library.LibraryRepository
import com.otakustream.core.database.library.WatchHistoryEntry
import com.otakustream.core.database.playback.PlaybackProgressRepository
import com.otakustream.core.database.skip.SkipSegment
import com.otakustream.core.database.skip.SkipSegmentRepository
import com.otakustream.core.database.skip.SkipSegmentType
import com.otakustream.core.sources.api.PendingPlayback
import com.otakustream.core.sources.api.PlayableUrl
import com.otakustream.core.sources.api.PlaybackCompletion
import com.otakustream.core.sources.api.PlaybackQueue
import com.otakustream.core.sources.api.SkipMark
import kotlinx.coroutines.CancellationException
import com.otakustream.core.player.torrent.TorrentDataSource
import com.otakustream.core.torrent.TorrentUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val PROGRESS_PERSIST_INTERVAL_MS = 5_000L
private const val FINISHED_THRESHOLD_FRACTION = 0.95
private const val SPEED_BOOST_MULTIPLIER = 2f

// How often to check whether the torrent's own subtitle files have arrived, and how long to keep
// looking. Generous on the timeout: the torrent has to find peers and fetch metadata before the file
// list even exists. Cheap on the interval — it's an in-memory read of the reader's file progress.
private const val TORRENT_SUBTITLE_POLL_MS = 2_000L
private const val TORRENT_SUBTITLE_TIMEOUT_MS = 120_000L

enum class ResizeMode { FIT, ZOOM, STRETCH }

enum class EqualizerPreset { FLAT, BASS_BOOST, TREBLE_BOOST }

// Position/duration tick ~2x a second for the whole length of a video. They live in their own
// state holder so only the scrubber and the time labels re-read them — folding them into
// PlayerUiState made every tick invalidate the entire player screen (controls, the skip-segment
// canvas, and the AndroidView update block) for the duration of playback.
data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val playbackSpeed: Float = 1f,
    val volume: Float = 1f,
    val error: String? = null,
    val audioTracks: List<TrackInfo> = emptyList(),
    val subtitleTracks: List<TrackInfo> = emptyList(),
    val videoQualityTracks: List<TrackInfo> = emptyList(),
    val subtitlesEnabled: Boolean = true,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val activeSkipSegment: PlayerSkipSegment? = null,
    val skipSegments: List<PlayerSkipSegment> = emptyList(),
    val autoSkipEnabled: Boolean = false,
    val isMarkingSegment: Boolean = false,
    val resizeMode: ResizeMode = ResizeMode.FIT,
    val statsOverlayVisible: Boolean = false,
    val codecName: String? = null,
    val videoBitrateBps: Int = 0,
    val droppedFrameCount: Int = 0,
    val equalizerPreset: EqualizerPreset = EqualizerPreset.FLAT,
    val volumeBoostMillibels: Int = 0,
    val hasNext: Boolean = false,
    val seekDurationMs: Long = 10_000L,
    // True while a Cast session is playing this media instead of the local player.
    val isCasting: Boolean = false,
    // A brief message for the user about something that just happened but isn't a playback failure —
    // shown as an on-screen label, not the error overlay. Cleared once shown.
    val notice: String? = null,
)

@OptIn(UnstableApi::class)
@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val progressRepository: PlaybackProgressRepository,
    private val skipSegmentRepository: SkipSegmentRepository,
    private val libraryRepository: LibraryRepository,
    private val playerSettingsPrefs: PlayerSettingsPrefs,
    private val castManager: com.otakustream.core.player.cast.CastManager,
    private val torrentEngine: com.otakustream.core.torrent.TorrentEngine,
    // Injected rather than constructed here: SimpleCache takes an exclusive lock on its directory,
    // so the downloader and the player must be looking at the same instance, not two views of the
    // same folder.
    private val downloadStore: com.otakustream.core.download.DownloadStore,
) {
    val player: ExoPlayer = ExoPlayer.Builder(appContext, PlayerRenderersFactory(appContext)).build()

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    // ExoPlayer must only be touched from the thread it was created on (the main thread here) —
    // pin the scope to Main.immediate so every launch{} below stays off Dispatchers.Default.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var currentMediaUrl: String? = null
    // Kept so addExternalSubtitle can rebuild the current item (same headers/factory) with an
    // extra subtitle track mid-playback.
    private var currentMediaItem: MediaItem? = null
    private var currentDataSourceFactory: DataSource.Factory? = null
    private var lastPersistAtMs = 0L
    // Whether PlaybackService has been started for the current playback session (see
    // onIsPlayingChanged). Reset when new media is loaded.
    private var foregroundServiceStarted = false
    private var segmentsJob: Job? = null

    // The coroutine that resolves the resume position and hands the media item to ExoPlayer.
    // Tracked so a second play() cancels the first: it awaits a Room read before touching the
    // player, so two quick plays — tapping an episode, backing out, tapping another — could
    // otherwise both complete, in either order. Losing that race leaves the player showing one
    // episode with the resume position, headers and subtitle tracks of the other.
    private var loadJob: Job? = null
    // Waits for subtitle files inside a torrent to download; cancelled when new media is loaded.
    private var torrentSubtitleJob: Job? = null
    // The AniSkip lookup for the current media. Tracked for the same reason as loadJob: it is a
    // network call that outlives the playback that started it, and its result is written straight
    // into aniSkipSegments — so an untracked one publishes the previous episode's intro and outro
    // markers over whatever is playing by the time it lands.
    private var aniSkipJob: Job? = null
    // The auto-play hand-off. Cancelled by stop(), because clearing the queue is not enough on its
    // own: a resolver that is already suspended fetching the next stream will still return, and this
    // coroutine would then call play() and restart playback the user has just walked away from.
    private var playNextJob: Job? = null

    // Identifies the current playback, so a caller can ask to stop *the playback it started* rather
    // than whatever happens to be playing now.
    //
    // The player screen is a composable over a process-lifetime singleton, and composition disposal
    // is not the same thing as owning what is playing. Navigating from one video straight to another
    // composes the new screen and disposes the old one, in an order Compose does not promise — so an
    // unconditional stop() on dispose can kill the playback that just started. The token makes the
    // outcome the same whichever way the race falls.
    private val playbackSession = java.util.concurrent.atomic.AtomicLong(0)
    val currentPlaybackSession: Long get() = playbackSession.get()
    // Manual (database) and AniSkip-fetched segments are tracked separately, then merged into
    // currentSegments with AniSkip winning on overlap.
    private var manualSegments: List<PlayerSkipSegment> = emptyList()
    private var aniSkipSegments: List<PlayerSkipSegment> = emptyList()
    private var currentSegments: List<PlayerSkipSegment> = emptyList()
    private var currentSkipLookup: (suspend (durationMs: Long) -> List<SkipMark>)? = null
    private var aniSkipFetched = false
    private var pendingSegmentStartMs: Long? = null
    private var speedBeforeBoost: Float? = null
    private var equalizer: Equalizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    init {
        instantiated = true
        // Read persisted toggles off the main thread — this @Singleton is built during activity
        // creation, so touching the prefs file here would be a StrictMode disk read on cold start.
        scope.launch(Dispatchers.IO) {
            val autoSkip = playerSettingsPrefs.autoSkipEnabled
            val seek = playerSettingsPrefs.seekDurationMs
            _uiState.value = _uiState.value.copy(autoSkipEnabled = autoSkip, seekDurationMs = seek)
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
                // Only start the service once per playback session: onIsPlayingChanged(true) fires
                // on every play/pause toggle and after post-seek buffering, and each call is a
                // binder round-trip that Android then dedupes anyway.
                if (isPlaying && !foregroundServiceStarted) {
                    foregroundServiceStarted = true
                    ContextCompat.startForegroundService(appContext, Intent(appContext, PlaybackService::class.java))
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _uiState.value = _uiState.value.copy(isBuffering = playbackState == Player.STATE_BUFFERING)
                // Duration becomes known during buffering/ready — publish it through the progress
                // flow so the scrubber's range updates without touching the rest of the UI state.
                publishProgress(_progress.value.positionMs)
                if (playbackState == Player.STATE_READY) {
                    maybeFetchAniSkip()
                }
                if (playbackState == Player.STATE_ENDED && PlaybackQueue.autoPlayEnabled) {
                    playNextJob?.cancel()
                    playNextJob = scope.launch { playNext() }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _uiState.value = _uiState.value.copy(error = error.message)
            }

            override fun onTracksChanged(tracks: Tracks) {
                _uiState.value = _uiState.value.copy(
                    audioTracks = tracks.toTrackInfoList(C.TRACK_TYPE_AUDIO),
                    subtitleTracks = tracks.toTrackInfoList(C.TRACK_TYPE_TEXT),
                    videoQualityTracks = tracks.toTrackInfoList(C.TRACK_TYPE_VIDEO),
                )
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                _uiState.value = _uiState.value.copy(
                    videoWidth = videoSize.width,
                    videoHeight = videoSize.height,
                )
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: androidx.media3.common.Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                _uiState.value = _uiState.value.copy(
                    codecName = format.codecs ?: format.sampleMimeType,
                    videoBitrateBps = format.bitrate,
                )
            }

            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                // Frames drop precisely when the device is already struggling, so only pay for the
                // state write when the nerd-stats overlay is on screen to read it.
                if (!_uiState.value.statsOverlayVisible) return
                _uiState.value = _uiState.value.copy(droppedFrameCount = _uiState.value.droppedFrameCount + droppedFrames)
            }

            override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
                rebuildEqualizer(audioSessionId)
            }
        })
        startPositionTicker()
        observeCastSession()
    }

    // Hand the current playback to/from a Cast device as the session connects and disconnects.
    // On connect: pause local and load the same media item + position onto the Cast device. On
    // disconnect: resume local playback from wherever casting left off.
    private fun observeCastSession() {
        scope.launch {
            castManager.isConnected.collect { connected ->
                if (connected) {
                    // Only remote http(s) media can be cast — the receiver can't reach a phone-local
                    // content://file:// URI, and casting a null item would just black the screen out
                    // under a "casting" overlay. In those cases leave local playback running.
                    val item = currentMediaItem
                    val url = currentMediaUrl
                    if (item != null && url != null && isCastableUrl(url)) {
                        val position = player.currentPosition.coerceAtLeast(0L)
                        player.pause()
                        castManager.castItem(item, position)
                        _uiState.value = _uiState.value.copy(isCasting = true)
                    } else if (url != null && !isCastableUrl(url)) {
                        // Gated on the url actually being uncastable, not just on the branch being
                        // taken: a castable url with no media item yet — a Cast session connecting
                        // while playback is still starting — would otherwise be told the TV can't
                        // reach a stream it can reach perfectly well.
                        //
                        // Say why. Connecting to a Cast device and having playback simply stay on the
                        // phone looks like the Cast button is broken — and a torrent is the case where
                        // a user is most likely to try, since the stream came from the internet and
                        // looks castable from the outside. It isn't: the receiver fetches the URL
                        // itself, and only this device can resolve a torrent:// one.
                        _uiState.value = _uiState.value.copy(
                            notice = if (TorrentUri.isTorrentUrl(url)) {
                                "Can't cast a torrent — the TV can't reach it. Playing here instead."
                            } else {
                                "Can't cast this video — the TV can't reach it. Playing here instead."
                            },
                        )
                    }
                } else if (_uiState.value.isCasting) {
                    val resumeMs = castManager.currentPositionMs().coerceAtLeast(0L)
                    castManager.stop()
                    if (resumeMs > 0) player.seekTo(resumeMs)
                    player.play()
                    _uiState.value = _uiState.value.copy(isCasting = false)
                }
            }
        }
    }

    // Bring the Cast session listener online so the Cast button reflects device availability.
    fun warmUpCast() = castManager.warmUp()

    // Dismisses a notice once the UI has shown it, so it doesn't reappear on the next recomposition.
    fun clearNotice() {
        if (_uiState.value.notice != null) _uiState.value = _uiState.value.copy(notice = null)
    }

    // A Cast receiver can only fetch remote http(s) URLs — local file/content URIs on the phone
    // aren't reachable from the TV.
    private fun isCastableUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    private fun startPositionTicker() {
        scope.launch {
            while (isActive) {
                if (!player.isPlaying) {
                    // Idle (paused / ended / nothing loaded): publish the position once so a paused
                    // seek still shows, then go quiet. Re-emitting an unchanged value every second
                    // would wake the scrubber for nothing — this loop lives for the whole process.
                    publishProgress(player.currentPosition.coerceAtLeast(0L))
                    delay(1_000)
                    continue
                }
                val position = player.currentPosition.coerceAtLeast(0L)
                val active = currentSegments.firstOrNull { position in it.startMs until it.endMs }
                if (active != null && _uiState.value.autoSkipEnabled) {
                    // Auto-skip jumps past the segment instead of surfacing the manual button.
                    seekTo(active.endMs)
                    publishProgress(active.endMs)
                    setActiveSkipSegment(null)
                } else {
                    publishProgress(position)
                    setActiveSkipSegment(active)
                }
                maybePersistProgress(position, force = false)
                delay(500)
            }
        }
    }

    // Writes the progress flow only when a value actually changed, so an unchanged tick costs
    // nothing downstream (StateFlow dedupes equal values, and this keeps the copy allocation away
    // from the common case too).
    private fun publishProgress(positionMs: Long) {
        val durationMs = player.duration.coerceAtLeast(0L)
        val current = _progress.value
        if (current.positionMs != positionMs || current.durationMs != durationMs) {
            _progress.value = PlaybackProgress(positionMs = positionMs, durationMs = durationMs)
        }
    }

    // The active segment drives the manual "Skip" button, so it belongs in the main UI state — but
    // it changes only when playback crosses a boundary, not on every tick.
    private fun setActiveSkipSegment(segment: PlayerSkipSegment?) {
        if (_uiState.value.activeSkipSegment != segment) {
            _uiState.value = _uiState.value.copy(activeSkipSegment = segment)
        }
    }

    private fun maybePersistProgress(positionMs: Long, force: Boolean) {
        val url = currentMediaUrl ?: return
        if (!force && !player.isPlaying) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPersistAtMs < PROGRESS_PERSIST_INTERVAL_MS) return
        lastPersistAtMs = now
        val duration = player.duration.coerceAtLeast(0L)
        scope.launch {
            if (duration > 0 && positionMs >= duration * FINISHED_THRESHOLD_FRACTION) {
                progressRepository.clear(url)
                // Episode genuinely watched to the end — hand off to whoever registered a
                // completion handler (feature:sources syncs AniList progress here, never at
                // play-start). takeHandler pops it so it fires at most once per play.
                PlaybackCompletion.takeHandler(url)?.let { onFinished ->
                    runCatching { onFinished() }.exceptionOrNull()?.let { e ->
                        if (e is CancellationException) throw e
                    }
                }
            } else {
                progressRepository.save(url, positionMs, duration)
            }
        }
    }

    fun play(url: String, startPositionMs: Long? = null, fromSource: Boolean = false) {
        // Before anything is mutated. What the player will open depends on who chose the URL: a
        // source may only point at http, https or the app's own torrent:// identity, while file://
        // and content:// belong to URLs the user picked, and refusing those would break on-device
        // playback.
        //
        // Peeked rather than consumed. Consuming here and validating afterwards meant a rejected
        // source URL lost its stash, so pressing Retry re-entered with no stash, fell through to the
        // permissive default, and played the file — one tap around the whole check.
        //
        // No stash means nothing source-originated got here: PendingPlayback is the only channel
        // headers and subtitle tracks travel on, so it is the only route a source has to the player.
        // A file from the picker, an "Open with", or a replay from history all arrive without one.
        // Either signal saying "a source chose this" is enough, and neither alone is sufficient. The
        // stash is richer but in-memory, so it is gone after process death; the route argument
        // survives that but is only set where the app knows a source was involved.
        val provenance = if (fromSource || PendingPlayback.peek(url)?.provenance == PendingPlayback.Provenance.SOURCE) {
            PendingPlayback.Provenance.SOURCE
        } else {
            PendingPlayback.Provenance.USER
        }
        if (!PlayableUrl.isAllowed(url, provenance)) {
            // Returning before currentMediaUrl is reassigned and before segmentsJob is restarted:
            // a refused URL must not become the key progress and completion are recorded against,
            // and must not leave a Room observation running for a playback that never starts. Any
            // playback already in progress is left alone.
            _uiState.value = _uiState.value.copy(error = PlayableUrl.rejectionMessage(provenance))
            return
        }

        currentMediaUrl = url
        pendingSegmentStartMs = null
        // Retires whatever the player screen that started the previous video is holding, so its
        // eventual disposal cannot stop this one.
        playbackSession.incrementAndGet()
        // New media, new playback session: the foreground service must be (re)started when this
        // one begins playing, and the scrubber must not briefly show the previous video's position.
        foregroundServiceStarted = false
        _progress.value = PlaybackProgress()
        // Clear per-video state carried over from the previous playback: a stale "Playback failed"
        // overlay must not sit over the next (working) episode, and the stats overlay must not show
        // the previous video's dropped-frame count / codec / bitrate until a new format arrives.
        _uiState.value = _uiState.value.copy(
            isMarkingSegment = false,
            error = null,
            droppedFrameCount = 0,
            codecName = null,
            videoBitrateBps = 0,
            notice = null,
        )

        // Reset skip state for the new media before either source repopulates it. The lookup is
        // cancelled as well as the flag reset — the previous episode's request is still in flight,
        // and its markers would otherwise be written over this video's.
        aniSkipJob?.cancel()
        aniSkipJob = null
        manualSegments = emptyList()
        aniSkipSegments = emptyList()
        aniSkipFetched = false
        recomputeSegments()

        segmentsJob?.cancel()
        segmentsJob = scope.launch {
            skipSegmentRepository.observeForMedia(url).collect { segments ->
                manualSegments = segments.map { it.toPlayerSegment() }
                recomputeSegments()
            }
        }

        val stashed = PendingPlayback.consume(url)
        val pending = stashed?.video
        currentSkipLookup = stashed?.skipLookup

        // No stash (file picker, pasted link, "Open with") — or a stash that explicitly left
        // history to us: record the play here, and drop any auto-play resolver left over from
        // an earlier catalog session so finishing this video can't chain into a stale episode.
        if (stashed == null || !stashed.historyHandled) {
            PlaybackQueue.clear()
            recordDirectPlay(url, stashed?.directPlayTitle)
        }

        // Read *after* the clear above, not before. Reading first meant a direct play that had just
        // discarded a stale resolver still reported hasNext = true, so the Next button appeared and
        // did nothing — on every file-picker play, pasted link and "Open with" that followed a
        // catalog session.
        _uiState.value = _uiState.value.copy(hasNext = PlaybackQueue.hasResolver())

        loadJob?.cancel()
        loadJob = scope.launch {
            val resumeMs = startPositionMs ?: progressRepository.getSavedPositionMs(url) ?: 0L
            val subtitles = pending?.subtitleTracks.orEmpty().map { it.toPlayerTrack() }
            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setSubtitleConfigurations(subtitles.map { it.toMedia3Config() })
                .apply { if (pending?.isM3U8 == true) setMimeType(MimeTypes.APPLICATION_M3U8) }
                .build()

            // A single ExoPlayer instance is shared across every playback, but headers are
            // per-video — build a fresh DataSource.Factory per call rather than baking one
            // into the player at construction time.
            val headers = pending?.headers.orEmpty()
            val httpDataSourceFactory = DefaultHttpDataSource.Factory().apply {
                if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
            }
            // DefaultDataSource delegates to file/content/asset data sources by URI scheme and
            // falls back to the HTTP factory (headers intact) for http(s) — so local files and
            // content:// URIs from the file picker / "Open with" play, not just remote URLs.
            val baseDataSourceFactory = DefaultDataSource.Factory(appContext, httpDataSourceFactory)
            // Downloaded episodes play from disk without any of the code above knowing.
            //
            // CacheDataSource serves whatever the download store already holds and falls through to
            // the network for anything it does not, so there is no separate "offline player" and no
            // branch at the call site: the same url plays the same way whether or not it was
            // downloaded. That also keeps resume position, skip markers and history working, since
            // they all key on that url.
            //
            // Read-only, deliberately — the write sink is null. Without that, ordinary streaming
            // would fill the download store, and a store whose evictor is NoOp (so that a saved
            // episode is never silently deleted) would then grow without limit from content the user
            // never asked to keep.
            val offlineFirstFactory = CacheDataSource.Factory()
                .setCache(downloadStore.cache)
                .setUpstreamDataSourceFactory(baseDataSourceFactory)
                .setCacheWriteDataSinkFactory(null)
            // torrent:// urls can't be fetched by any of the above, so they route through the torrent
            // engine instead. The trackers come from the stashed Video rather than the url: the url is
            // deliberately just the torrent's identity, so that everything keyed on it above — resume
            // position, skip segments, history — stays stable across sessions.
            val dataSourceFactory = if (TorrentUri.isTorrentUrl(url)) {
                // Torrents keep their own storage and are never in the download store, so they skip
                // the cache layer rather than paying a lookup that can only miss.
                TorrentDataSource.Factory(
                    engine = torrentEngine,
                    saveDir = com.otakustream.core.torrent.torrentCacheDir(appContext),
                    trackers = pending?.trackers.orEmpty(),
                    delegate = baseDataSourceFactory,
                )
            } else {
                offlineFirstFactory
            }
            val mediaSource = DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)

            currentMediaItem = mediaItem
            currentDataSourceFactory = dataSourceFactory

            player.setMediaSource(mediaSource, resumeMs)
            player.prepare()
            player.playWhenReady = true

            // Apply the remembered default speed to every new video (boost is separate and resets).
            val defaultSpeed = playerSettingsPrefs.defaultSpeed
            player.setPlaybackSpeed(defaultSpeed)
            _uiState.value = _uiState.value.copy(playbackSpeed = defaultSpeed)
        }

        torrentSubtitleJob?.cancel()
        if (TorrentUri.isTorrentUrl(url)) {
            torrentSubtitleJob = scope.launch { offerTorrentSubtitles(url) }
        }
    }

    // Offers subtitle files carried inside the torrent as selectable tracks, once they arrive.
    //
    // They can't be added at play() time: the torrent's file list isn't known until metadata comes
    // back from a peer, and the subtitle files themselves then have to download. So this waits, and
    // adds them in one batch — each add costs a rebuffer, and four separate ones would stutter
    // playback four times at the same spot.
    private suspend fun offerTorrentSubtitles(url: String) {
        val deadline = SystemClock.elapsedRealtime() + TORRENT_SUBTITLE_TIMEOUT_MS
        while (true) {
            delay(TORRENT_SUBTITLE_POLL_MS)
            // Another video started. Adding tracks now would put them on the wrong playback.
            if (currentMediaUrl != url) return
            val progress = torrentEngine.subtitleProgress()
            val expired = SystemClock.elapsedRealtime() > deadline
            when {
                // No reader yet — still resolving metadata. Nothing to conclude either way.
                progress == null -> if (expired) return
                // The torrent carries none, so there is nothing to wait for.
                progress.total == 0 -> return
                progress.isComplete || expired -> {
                    addExternalSubtitles(
                        progress.ready.map { subtitle ->
                            SubtitleTrack(
                                url = Uri.fromFile(java.io.File(subtitle.path)).toString(),
                                label = subtitle.label,
                                mimeType = subtitleMimeTypeForName(subtitle.path),
                            )
                        },
                    )
                    return
                }
            }
        }
    }

    // Adds a user-picked subtitle file to the current playback.
    fun addExternalSubtitle(uri: String, label: String, mimeType: String) =
        addExternalSubtitles(listOf(SubtitleTrack(url = uri, label = label, mimeType = mimeType)))

    // Rebuilds the media item with extra tracks (same data source factory, headers intact) and
    // re-prepares at the current position. A brief rebuffer at the same spot is the accepted cost.
    //
    // Takes a list rather than one track because each call costs a rebuffer: adding four subtitle
    // files from a torrent one at a time would stutter playback four times at the same spot.
    fun addExternalSubtitles(tracks: List<SubtitleTrack>) {
        if (tracks.isEmpty()) return
        val item = currentMediaItem ?: return
        val factory = currentDataSourceFactory ?: return
        val existing = item.localConfiguration?.subtitleConfigurations.orEmpty()
        val existingUris = existing.mapTo(mutableSetOf()) { it.uri.toString() }
        val added = tracks
            .filter { it.url !in existingUris }
            .map { track ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
                    .setMimeType(track.mimeType)
                    .setLabel(track.label)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            }
        // Nothing new: return before re-preparing, or a repeated call would rebuffer for no reason.
        if (added.isEmpty()) return
        val rebuilt = item.buildUpon().setSubtitleConfigurations(existing + added).build()
        currentMediaItem = rebuilt

        val position = player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = player.playWhenReady
        player.setMediaSource(DefaultMediaSourceFactory(factory).createMediaSource(rebuilt), position)
        player.prepare()
        player.playWhenReady = wasPlaying
    }

    private suspend fun playNext() {
        val next = PlaybackQueue.resolveNext() ?: return
        PendingPlayback.stash(next)
        play(next.url)
    }

    // A play that arrived outside the catalog flow (local file, pasted URL, "Open with") still
    // belongs in watch history / continue watching — recorded here since play() is the one
    // choke point every path funnels through.
    // Title resolution, best first:
    //
    //  1. what the caller told us — it knows things the URL doesn't, like a magnet's `dn`;
    //  2. for a torrent:// url only, what this media was called last time — replaying from Continue
    //     Watching then keeps the name it already had rather than degrading to whatever the URL
    //     yields;
    //  3. derived from the URL, which is right for a file and useless for an identity — a
    //     torrent://<hash>/0 has no name in it, and its last path segment is "0".
    //
    // Step 2 is what makes step 1 stick: without it the first play of a magnet was titled correctly
    // and every replay afterwards overwrote that with "0". It is restricted to torrent:// because
    // for a file the derived name is authoritative — a content:// file that has since been renamed
    // should pick up its new display name, not stay pinned to whatever history remembers.
    private fun recordDirectPlay(url: String, title: String?) {
        scope.launch {
            libraryRepository.recordWatch(
                WatchHistoryEntry(
                    sourceId = DIRECT_PLAY_SOURCE_ID,
                    mediaUrl = url,
                    mediaTitle = title?.trim()?.takeIf { it.isNotEmpty() }
                        ?: rememberedTitleFor(url)
                        ?: deriveDisplayTitle(url),
                    episodeUrl = url,
                    episodeName = "",
                    episodeNumber = 0f,
                    watchedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    private suspend fun rememberedTitleFor(url: String): String? =
        if (TorrentUri.isTorrentUrl(url)) {
            libraryRepository.lastTitleFor(url)?.takeIf { it.isNotBlank() }
        } else {
            null
        }

    // Best human-readable name available without any caller plumbing: content:// resolves its
    // provider display name (covers SAF picks and MediaStore items alike); other schemes fall
    // back to the decoded filename, then the host, then the raw URL.
    private suspend fun deriveDisplayTitle(url: String): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse(url)
        if (uri.scheme == "content") {
            val resolved = runCatching {
                appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull()
            if (!resolved.isNullOrBlank()) return@withContext resolved
        }
        uri.lastPathSegment?.takeUnless { it.isBlank() } ?: uri.host ?: url
    }

    fun pause() {
        player.playWhenReady = false
        maybePersistProgress(player.currentPosition.coerceAtLeast(0L), force = true)
    }

    // Ends the current playback outright — what leaving the player screen means. Until this existed,
    // backing out of the player left the episode playing: audio continued over the details screen,
    // the media notification stayed up, and a torrent kept streaming, with no route back to the
    // controls that would have stopped any of it.
    //
    // Deliberately not release(). The ExoPlayer is a @Singleton shared by every playback in the
    // process, so releasing it here would leave the next one with a dead player. What is torn down
    // is the *playback*: position saved, media dropped, jobs cancelled, service stopped.
    //
    // Takes the session the caller believes it is stopping (see currentPlaybackSession). A screen
    // that has already been superseded by a newer play() holds an old token and is refused, which
    // is what stops a departing player screen from killing the video that replaced it.
    fun stop(session: Long) {
        if (session != playbackSession.get()) return
        // Retire the session here too, so a second stop for the same one — a disposal racing an
        // explicit stop — cannot run this twice.
        playbackSession.incrementAndGet()

        // Before player.stop(), which resets the position to zero — reading it afterwards would
        // write "the very beginning" over the user's real place in the episode.
        maybePersistProgress(player.currentPosition.coerceAtLeast(0L), force = true)

        loadJob?.cancel()
        loadJob = null
        torrentSubtitleJob?.cancel()
        torrentSubtitleJob = null
        segmentsJob?.cancel()
        segmentsJob = null
        aniSkipJob?.cancel()
        aniSkipJob = null
        // Clearing the queue below retires the chain, but a resolver already suspended mid-fetch
        // still returns — and this coroutine would then call play() and restart a video the user
        // has just left. PlaybackQueue.resolveNext discards a superseded chain's result as well;
        // cancelling is the direct answer, that is the one that holds if this lands mid-await.
        playNextJob?.cancel()
        playNextJob = null

        player.stop()
        // Releases the media source, and with it the DataSources it opened. That is what closes a
        // torrent reader, so the engine can retire the session instead of streaming a video nobody
        // is watching.
        player.clearMediaItems()

        currentMediaUrl = null
        currentMediaItem = null
        currentDataSourceFactory = null
        currentSkipLookup = null
        manualSegments = emptyList()
        aniSkipSegments = emptyList()
        aniSkipFetched = false
        recomputeSegments()
        // The chain belongs to the playback that just ended. Left armed, the next thing to open the
        // player would find a Next button offering an episode of the show the user walked away from.
        PlaybackQueue.clear()

        if (foregroundServiceStarted) {
            foregroundServiceStarted = false
            appContext.stopService(Intent(appContext, PlaybackService::class.java))
        }

        _uiState.value = _uiState.value.copy(
            isPlaying = false,
            isBuffering = false,
            hasNext = false,
            activeSkipSegment = null,
            error = null,
            notice = null,
        )
        _progress.value = PlaybackProgress()
    }

    fun resume() {
        player.playWhenReady = true
    }

    fun togglePlayPause() {
        if (player.playWhenReady) pause() else resume()
    }

    fun seekBy(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs).coerceIn(0L, player.duration.coerceAtLeast(0L))
        player.seekTo(target)
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceIn(0L, player.duration.coerceAtLeast(0L)))
    }

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        player.volume = clamped
        _uiState.value = _uiState.value.copy(volume = clamped)
    }

    fun setPlaybackSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        _uiState.value = _uiState.value.copy(playbackSpeed = speed)
    }

    fun selectTrack(trackType: Int, track: TrackInfo) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(TrackSelectionOverride(track.group, track.trackIndexInGroup))
            .build()
    }

    fun clearTrackOverride(trackType: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(trackType)
            .build()
    }

    fun setSubtitlesEnabled(enabled: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
        _uiState.value = _uiState.value.copy(subtitlesEnabled = enabled)
    }

    fun markSegmentStart() {
        pendingSegmentStartMs = player.currentPosition.coerceAtLeast(0L)
        _uiState.value = _uiState.value.copy(isMarkingSegment = true)
    }

    fun markSegmentEnd(type: SkipSegmentType) {
        val url = currentMediaUrl ?: return
        val startMs = pendingSegmentStartMs ?: return
        val endMs = player.currentPosition.coerceAtLeast(0L)
        pendingSegmentStartMs = null
        _uiState.value = _uiState.value.copy(isMarkingSegment = false)
        if (endMs <= startMs) return
        scope.launch { skipSegmentRepository.insert(url, startMs, endMs, type) }
    }

    fun skipActiveSegment() {
        _uiState.value.activeSkipSegment?.let { seekTo(it.endMs) }
    }

    fun setAutoSkipEnabled(enabled: Boolean) {
        playerSettingsPrefs.autoSkipEnabled = enabled
        _uiState.value = _uiState.value.copy(autoSkipEnabled = enabled)
    }

    // A user-chosen speed also becomes the remembered default for future videos (unlike the
    // transient long-press boost, which uses setPlaybackSpeed directly).
    fun setUserPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceAtLeast(0.25f)
        playerSettingsPrefs.defaultSpeed = clamped
        setPlaybackSpeed(clamped)
    }

    fun setSeekDurationMs(durationMs: Long) {
        playerSettingsPrefs.seekDurationMs = durationMs
        _uiState.value = _uiState.value.copy(seekDurationMs = durationMs)
    }

    fun setVolumeBoostMillibels(millibels: Int) {
        _uiState.value = _uiState.value.copy(volumeBoostMillibels = millibels)
        applyVolumeBoost(millibels)
    }

    fun skipToNext() {
        scope.launch { playNext() }
    }

    // AniSkip is fetched once per playback, after the real duration is known (STATE_READY).
    private fun maybeFetchAniSkip() {
        if (aniSkipFetched) return
        val lookup = currentSkipLookup ?: return
        val duration = player.duration
        if (duration <= 0) return
        aniSkipFetched = true
        aniSkipJob?.cancel()
        aniSkipJob = scope.launch {
            val marks = runCatching { lookup(duration) }.getOrElse { error ->
                if (error is CancellationException) throw error
                emptyList()
            }
            aniSkipSegments = marks.mapNotNull { it.toPlayerSegment() }
            recomputeSegments()
        }
    }

    // AniSkip segments take precedence; manual markers fill in only where AniSkip has nothing.
    private fun recomputeSegments() {
        val merged = aniSkipSegments +
            manualSegments.filterNot { manual -> aniSkipSegments.any { it.overlaps(manual) } }
        currentSegments = merged.sortedBy { it.startMs }
        _uiState.value = _uiState.value.copy(skipSegments = currentSegments)
    }

    fun beginTemporarySpeedBoost() {
        if (speedBeforeBoost != null) return
        speedBeforeBoost = _uiState.value.playbackSpeed
        setPlaybackSpeed(SPEED_BOOST_MULTIPLIER)
    }

    fun endTemporarySpeedBoost() {
        val previousSpeed = speedBeforeBoost ?: return
        speedBeforeBoost = null
        setPlaybackSpeed(previousSpeed)
    }

    fun cycleResizeMode() {
        val next = when (_uiState.value.resizeMode) {
            ResizeMode.FIT -> ResizeMode.ZOOM
            ResizeMode.ZOOM -> ResizeMode.STRETCH
            ResizeMode.STRETCH -> ResizeMode.FIT
        }
        _uiState.value = _uiState.value.copy(resizeMode = next)
    }

    fun toggleStatsOverlay() {
        _uiState.value = _uiState.value.copy(statsOverlayVisible = !_uiState.value.statsOverlayVisible)
    }

    fun setEqualizerPreset(preset: EqualizerPreset) {
        _uiState.value = _uiState.value.copy(equalizerPreset = preset)
        applyEqualizerPreset(preset)
    }

    private fun rebuildEqualizer(sessionId: Int) {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        equalizer?.release()
        equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()?.apply { enabled = true }
        applyEqualizerPreset(_uiState.value.equalizerPreset)
        loudnessEnhancer?.release()
        loudnessEnhancer = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()?.apply { enabled = true }
        applyVolumeBoost(_uiState.value.volumeBoostMillibels)
    }

    private fun applyVolumeBoost(millibels: Int) {
        runCatching { loudnessEnhancer?.setTargetGain(millibels) }
    }

    private fun applyEqualizerPreset(preset: EqualizerPreset) {
        val eq = equalizer ?: return
        // Even a successfully-constructed Equalizer can throw from these calls on some OEM audio
        // stacks (getBandLevelRange/setBandLevel RuntimeExceptions). This runs from ExoPlayer's
        // onAudioSessionIdChanged during playback, so an unguarded throw would crash mid-play —
        // swallow it and leave the EQ flat, matching applyVolumeBoost's guarded style.
        runCatching {
            val bandCount = eq.numberOfBands.toInt()
            val maxGain = eq.bandLevelRange[1]
            for (band in 0 until bandCount) {
                val level: Short = when (preset) {
                    EqualizerPreset.FLAT -> 0
                    EqualizerPreset.BASS_BOOST -> if (band < bandCount / 3) maxGain else 0
                    EqualizerPreset.TREBLE_BOOST -> if (band >= bandCount - bandCount / 3) maxGain else 0
                }
                eq.setBandLevel(band.toShort(), level)
            }
        }
    }

    companion object {
        @Volatile
        private var instantiated = false

        // "Has a controller been built yet?", answerable without building one. Dagger's Lazy has no
        // isInitialized(), and callers on a latency-sensitive path (MainActivity.onUserLeaveHint,
        // which runs during the leave transition) must not pay for ExoPlayer construction —
        // renderers, track selector and an audio-capability probe — just to read state that is
        // definitionally "not playing" when no controller exists. Safe as a static: this is a
        // @Singleton, so it goes true exactly once per process and never back.
        val exists: Boolean get() = instantiated
    }
}

private fun SkipSegment.toPlayerSegment(): PlayerSkipSegment = PlayerSkipSegment(
    startMs = startMs,
    endMs = endMs,
    kind = when (type) {
        SkipSegmentType.INTRO -> SkipKind.INTRO
        SkipSegmentType.OUTRO -> SkipKind.OUTRO
    },
)

private fun SkipMark.toPlayerSegment(): PlayerSkipSegment? {
    val kind = when (type) {
        SkipMark.TYPE_INTRO -> SkipKind.INTRO
        SkipMark.TYPE_OUTRO -> SkipKind.OUTRO
        SkipMark.TYPE_RECAP -> SkipKind.RECAP
        else -> return null
    }
    return PlayerSkipSegment(startMs, endMs, kind)
}
