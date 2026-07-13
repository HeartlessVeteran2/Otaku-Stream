package com.otakustream.core.player

import android.content.Context
import android.content.Intent
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.otakustream.core.database.playback.PlaybackProgressRepository
import com.otakustream.core.database.skip.SkipSegment
import com.otakustream.core.database.skip.SkipSegmentRepository
import com.otakustream.core.database.skip.SkipSegmentType
import com.otakustream.core.sources.api.PendingPlayback
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
)

@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val progressRepository: PlaybackProgressRepository,
    private val skipSegmentRepository: SkipSegmentRepository,
) {
    val player: ExoPlayer = ExoPlayer.Builder(appContext).build()

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

    @OptIn(UnstableApi::class)
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
            val dataSourceFactory = DefaultHttpDataSource.Factory().apply {
                if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
            }
            val mediaSource = DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)

            player.setMediaSource(mediaSource, resumeMs)
            player.prepare()
            player.playWhenReady = true
        }
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
}
