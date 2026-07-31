package com.otakustream.core.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import com.otakustream.core.database.skip.SkipSegmentType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    val controller: PlayerController,
    private val onboardingPrefs: PlayerOnboardingPrefs,
    private val subtitleStylePrefs: SubtitleStylePrefs,
) : ViewModel() {

    val uiState: StateFlow<PlayerUiState> = controller.uiState

    // Separate from uiState so a 2Hz position tick only invalidates the scrubber and the
    // time labels, not the whole player screen.
    val progress: StateFlow<PlaybackProgress> = controller.progress

    // Seeded with the defaults and replaced once the saved style has been read off disk.
    //
    // The read used to happen right here, in a field initializer — so constructing this ViewModel
    // opened and parsed a SharedPreferences file on the main thread, on the frame where the user has
    // just tapped an episode and is waiting for video. The defaults render correctly, and the real
    // style lands a frame or two later; subtitles are not even decoded yet at that point.
    private val _subtitleStyle = MutableStateFlow(SubtitleStyle())
    val subtitleStyle: StateFlow<SubtitleStyle> = _subtitleStyle.asStateFlow()
    private var saveStyleJob: Job? = null

    // Same reasoning, and it has to be readable synchronously by the composable that decides whether
    // to show the gesture coach — so it is read once, off-thread, rather than on every access.
    private var hasSeenGestureCoachCached = true

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val style = subtitleStylePrefs.load()
            val seenCoach = onboardingPrefs.hasSeenGestureCoach
            _subtitleStyle.value = style
            hasSeenGestureCoachCached = seenCoach
        }
    }

    fun setSubtitleStyle(style: SubtitleStyle) {
        // Slider drags emit many updates: keep the live preview/apply instant but debounce the
        // disk write so we don't flood QueuedWork with SharedPreferences commits.
        _subtitleStyle.value = style
        saveStyleJob?.cancel()
        saveStyleJob = viewModelScope.launch {
            delay(SUBTITLE_STYLE_SAVE_DEBOUNCE_MS)
            subtitleStylePrefs.save(style)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Flush a still-pending debounced change before viewModelScope is cancelled.
        if (saveStyleJob?.isActive == true) {
            saveStyleJob?.cancel()
            subtitleStylePrefs.save(_subtitleStyle.value)
        }
    }

    // Defaults to true — "already seen" — until the real value is read. Erring that way on purpose:
    // if the read has not landed yet, not showing the coach is a missing hint, whereas showing it is
    // an overlay on top of a video the user is trying to watch, every single time.
    val hasSeenGestureCoach: Boolean get() = hasSeenGestureCoachCached

    fun markGestureCoachSeen() {
        hasSeenGestureCoachCached = true
        viewModelScope.launch(Dispatchers.IO) { onboardingPrefs.hasSeenGestureCoach = true }
    }

    fun play(url: String, fromSource: Boolean = false) = controller.play(url, fromSource = fromSource)

    fun warmUpCast() = controller.warmUpCast()

    fun clearNotice() = controller.clearNotice()

    fun togglePlayPause() = controller.togglePlayPause()

    fun seekBy(deltaMs: Long) = controller.seekBy(deltaMs)

    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    fun adjustVolume(delta: Float) = controller.setVolume(controller.uiState.value.volume + delta)

    fun setPlaybackSpeed(speed: Float) = controller.setUserPlaybackSpeed(speed)

    fun skipToNext() = controller.skipToNext()

    fun setSeekDurationMs(durationMs: Long) = controller.setSeekDurationMs(durationMs)

    fun setVolumeBoost(millibels: Int) = controller.setVolumeBoostMillibels(millibels)

    fun selectAudioTrack(track: TrackInfo) = controller.selectTrack(C.TRACK_TYPE_AUDIO, track)

    fun selectSubtitleTrack(track: TrackInfo) = controller.selectTrack(C.TRACK_TYPE_TEXT, track)

    fun selectVideoQuality(track: TrackInfo) = controller.selectTrack(C.TRACK_TYPE_VIDEO, track)

    fun clearSubtitleOverride() = controller.clearTrackOverride(C.TRACK_TYPE_TEXT)

    fun clearVideoQualityOverride() = controller.clearTrackOverride(C.TRACK_TYPE_VIDEO)

    fun setSubtitlesEnabled(enabled: Boolean) = controller.setSubtitlesEnabled(enabled)

    // A user-picked subtitle file: MIME guessed from the display name (SubRip fallback — local
    // subtitle files are overwhelmingly .srt).
    fun loadSubtitleFile(uri: String, displayName: String) =
        controller.addExternalSubtitle(uri, displayName, subtitleMimeTypeForName(displayName))

    fun markSegmentStart() = controller.markSegmentStart()

    fun markSegmentEnd(type: SkipSegmentType) = controller.markSegmentEnd(type)

    fun skipActiveSegment() = controller.skipActiveSegment()

    fun setAutoSkipEnabled(enabled: Boolean) = controller.setAutoSkipEnabled(enabled)

    fun beginSpeedBoost() = controller.beginTemporarySpeedBoost()

    fun endSpeedBoost() = controller.endTemporarySpeedBoost()

    fun cycleResizeMode() = controller.cycleResizeMode()

    fun toggleStatsOverlay() = controller.toggleStatsOverlay()

    fun setEqualizerPreset(preset: EqualizerPreset) = controller.setEqualizerPreset(preset)

    private companion object {
        const val SUBTITLE_STYLE_SAVE_DEBOUNCE_MS = 300L
    }
}
