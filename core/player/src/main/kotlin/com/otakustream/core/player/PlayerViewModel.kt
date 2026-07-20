package com.otakustream.core.player

import androidx.lifecycle.ViewModel
import androidx.media3.common.C
import com.otakustream.core.database.skip.SkipSegmentType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    val controller: PlayerController,
    private val onboardingPrefs: PlayerOnboardingPrefs,
    private val subtitleStylePrefs: SubtitleStylePrefs,
) : ViewModel() {

    val uiState: StateFlow<PlayerUiState> = controller.uiState

    private val _subtitleStyle = MutableStateFlow(subtitleStylePrefs.load())
    val subtitleStyle: StateFlow<SubtitleStyle> = _subtitleStyle.asStateFlow()

    fun setSubtitleStyle(style: SubtitleStyle) {
        subtitleStylePrefs.save(style)
        _subtitleStyle.value = style
    }

    val hasSeenGestureCoach: Boolean get() = onboardingPrefs.hasSeenGestureCoach

    fun markGestureCoachSeen() { onboardingPrefs.hasSeenGestureCoach = true }

    fun play(url: String) = controller.play(url)

    fun togglePlayPause() = controller.togglePlayPause()

    fun seekBy(deltaMs: Long) = controller.seekBy(deltaMs)

    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    fun adjustVolume(delta: Float) = controller.setVolume(controller.uiState.value.volume + delta)

    fun setPlaybackSpeed(speed: Float) = controller.setPlaybackSpeed(speed)

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

    fun beginSpeedBoost() = controller.beginTemporarySpeedBoost()

    fun endSpeedBoost() = controller.endTemporarySpeedBoost()

    fun cycleResizeMode() = controller.cycleResizeMode()

    fun toggleStatsOverlay() = controller.toggleStatsOverlay()

    fun setEqualizerPreset(preset: EqualizerPreset) = controller.setEqualizerPreset(preset)
}
