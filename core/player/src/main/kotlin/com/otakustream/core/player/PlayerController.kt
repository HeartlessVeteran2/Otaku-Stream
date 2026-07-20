package com.otakustream.core.player

import android.content.Context
import android.content.Intent
import android.media.audiofx.Equalizer
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
import com.otakustream.core.sources.api.PlaybackQueue
import com.otakustream.core.sources.api.SkipMark
import kotlinx.coroutines.CancellationException
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

enum class ResizeMode { FIT, ZOOM, STRETCH }

enum class EqualizerPreset { FLAT, BASS_BOOST, TREBLE_BOOST }

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
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
)

@OptIn(UnstableApi::class)
@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val progressRepository: PlaybackProgressRepository,
    private val skipSegmentRepository: SkipSegmentRepository,
    private val libraryRepository: LibraryRepository,
    private val playerSettingsPrefs: PlayerSettingsPrefs,
) {
    val player: ExoPlayer = ExoPlayer.Builder(appContext, PlayerRenderersFactory(appContext)).build()

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // ExoPlayer must only be touched from the thread it was created on (the main thread here) —
    // pin the scope to Main.immediate so every launch{} below stays off Dispatchers.Default.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var currentMediaUrl: String? = null
    // Kept so addExternalSubtitle can rebuild the current item (same headers/factory) with an
    // extra subtitle track mid-playback.
    private var currentMediaItem: MediaItem? = null
    private var currentDataSourceFactory: DataSource.Factory? = null
    private var lastPersistAtMs = 0L
    private var segmentsJob: Job? = null
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

    init {
        _uiState.value = _uiState.value.copy(autoSkipEnabled = playerSettingsPrefs.autoSkipEnabled)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
                if (isPlaying) {
                    ContextCompat.startForegroundService(appContext, Intent(appContext, PlaybackService::class.java))
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _uiState.value = _uiState.value.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    durationMs = player.duration.coerceAtLeast(0L),
                )
                if (playbackState == Player.STATE_READY) {
                    maybeFetchAniSkip()
                }
                if (playbackState == Player.STATE_ENDED && PlaybackQueue.autoPlayEnabled) {
                    scope.launch { playNext() }
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
                _uiState.value = _uiState.value.copy(droppedFrameCount = _uiState.value.droppedFrameCount + droppedFrames)
            }

            override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
                rebuildEqualizer(audioSessionId)
            }
        })
        startPositionTicker()
    }

    private fun startPositionTicker() {
        scope.launch {
            while (isActive) {
                val position = player.currentPosition.coerceAtLeast(0L)
                val active = currentSegments.firstOrNull { position in it.startMs until it.endMs }
                if (active != null && _uiState.value.autoSkipEnabled && player.isPlaying) {
                    // Auto-skip jumps past the segment instead of surfacing the manual button.
                    seekTo(active.endMs)
                    _uiState.value = _uiState.value.copy(
                        positionMs = active.endMs,
                        durationMs = player.duration.coerceAtLeast(0L),
                        activeSkipSegment = null,
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        positionMs = position,
                        durationMs = player.duration.coerceAtLeast(0L),
                        activeSkipSegment = active,
                    )
                }
                maybePersistProgress(position, force = false)
                delay(500)
            }
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
            } else {
                progressRepository.save(url, positionMs, duration)
            }
        }
    }

    fun play(url: String, startPositionMs: Long? = null) {
        currentMediaUrl = url
        pendingSegmentStartMs = null
        _uiState.value = _uiState.value.copy(isMarkingSegment = false)

        // Reset skip state for the new media before either source repopulates it.
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
            recordDirectPlay(url)
        }

        scope.launch {
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
            val dataSourceFactory = DefaultDataSource.Factory(appContext, httpDataSourceFactory)
            val mediaSource = DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)

            currentMediaItem = mediaItem
            currentDataSourceFactory = dataSourceFactory

            player.setMediaSource(mediaSource, resumeMs)
            player.prepare()
            player.playWhenReady = true
        }
    }

    // Adds a user-picked subtitle file to the current playback: rebuild the media item with the
    // extra track (same data source factory, headers intact) and re-prepare at the current
    // position. A brief rebuffer at the same spot is the accepted cost.
    fun addExternalSubtitle(uri: String, label: String, mimeType: String) {
        val item = currentMediaItem ?: return
        val factory = currentDataSourceFactory ?: return
        val existing = item.localConfiguration?.subtitleConfigurations.orEmpty()
        val added = MediaItem.SubtitleConfiguration.Builder(Uri.parse(uri))
            .setMimeType(mimeType)
            .setLabel(label)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
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
    private fun recordDirectPlay(url: String) {
        scope.launch {
            libraryRepository.recordWatch(
                WatchHistoryEntry(
                    sourceId = DIRECT_PLAY_SOURCE_ID,
                    mediaUrl = url,
                    mediaTitle = deriveDisplayTitle(url),
                    episodeUrl = url,
                    episodeName = "",
                    episodeNumber = 0f,
                    watchedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
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

    // AniSkip is fetched once per playback, after the real duration is known (STATE_READY).
    private fun maybeFetchAniSkip() {
        if (aniSkipFetched) return
        val lookup = currentSkipLookup ?: return
        val duration = player.duration
        if (duration <= 0) return
        aniSkipFetched = true
        scope.launch {
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
    }

    private fun applyEqualizerPreset(preset: EqualizerPreset) {
        val eq = equalizer ?: return
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
