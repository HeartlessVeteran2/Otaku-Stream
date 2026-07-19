package com.otakustream.core.player

import android.content.Context
import android.content.Intent
import android.media.audiofx.Equalizer
import android.os.SystemClock
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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.otakustream.core.database.playback.PlaybackProgressRepository
import com.otakustream.core.database.skip.SkipSegment
import com.otakustream.core.database.skip.SkipSegmentRepository
import com.otakustream.core.database.skip.SkipSegmentType
import com.otakustream.core.sources.api.PendingPlayback
import com.otakustream.core.sources.api.PlaybackQueue
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
    val activeSkipSegment: SkipSegment? = null,
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
) {
    val player: ExoPlayer = ExoPlayer.Builder(appContext, PlayerRenderersFactory(appContext)).build()

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // ExoPlayer must only be touched from the thread it was created on (the main thread here) —
    // pin the scope to Main.immediate so every launch{} below stays off Dispatchers.Default.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var currentMediaUrl: String? = null
    private var lastPersistAtMs = 0L
    private var segmentsJob: Job? = null
    private var currentSegments: List<SkipSegment> = emptyList()
    private var pendingSegmentStartMs: Long? = null
    private var speedBeforeBoost: Float? = null
    private var equalizer: Equalizer? = null

    init {
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
                _uiState.value = _uiState.value.copy(
                    positionMs = position,
                    durationMs = player.duration.coerceAtLeast(0L),
                    activeSkipSegment = currentSegments.firstOrNull { position in it.startMs until it.endMs },
                )
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

        segmentsJob?.cancel()
        segmentsJob = scope.launch {
            skipSegmentRepository.observeForMedia(url).collect { segments -> currentSegments = segments }
        }

        val pending = PendingPlayback.consume(url)

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

            player.setMediaSource(mediaSource, resumeMs)
            player.prepare()
            player.playWhenReady = true
        }
    }

    private suspend fun playNext() {
        val next = PlaybackQueue.resolveNext() ?: return
        PendingPlayback.stash(next)
        play(next.url)
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
