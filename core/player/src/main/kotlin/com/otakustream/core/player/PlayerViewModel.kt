package com.otakustream.core.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import com.otakustream.core.database.skip.SkipSegmentType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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

    // The saved subtitle style, owned by SubtitleStylePrefs — the loading, the edit guard and the
    // debounced write all live there now. Observing it rather than copying it is what makes a style
    // changed on the Playback settings screen show up here without a restart.
    val subtitleStyle: StateFlow<SubtitleStyle> = subtitleStylePrefs.style

    // Null while unknown, so the overlay can tell "not loaded yet" from "genuinely not seen".
    // A StateFlow rather than a plain field: the screen reads this into remembered state, so a value
    // that arrives later has to be observable or a first-time user would simply never see the coach.
    private val _hasSeenGestureCoach = MutableStateFlow<Boolean?>(null)
    val hasSeenGestureCoach: StateFlow<Boolean?> = _hasSeenGestureCoach.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _hasSeenGestureCoach.value = onboardingPrefs.hasSeenGestureCoach
        }
    }

    fun setSubtitleStyle(style: SubtitleStyle) = subtitleStylePrefs.set(style)

    override fun onCleared() {
        super.onCleared()
        // Write a still-pending debounced change now rather than leaving it to a timer that a
        // process death in the next few hundred milliseconds would beat. This works where the
        // previous version didn't: the debounce lives on an app-lifetime scope, so viewModelScope
        // being cancelled just above no longer decides whether there is anything left to save.
        subtitleStylePrefs.flush()
    }

    fun markGestureCoachSeen() {
        _hasSeenGestureCoach.value = true
        // Synchronous, deliberately. The setter is backed by SharedPreferences.apply(), which
        // already returns immediately and flushes on its own thread — so putting it on
        // viewModelScope bought nothing and added a way to lose it: dismissing the coach and
        // leaving the player straight away cancels the scope before the write runs, and the coach
        // comes back next time.
        onboardingPrefs.hasSeenGestureCoach = true
    }

    fun play(url: String, fromSource: Boolean = false) = controller.play(url, fromSource = fromSource)

    fun retryCurrent() = controller.retryCurrent()

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

}
