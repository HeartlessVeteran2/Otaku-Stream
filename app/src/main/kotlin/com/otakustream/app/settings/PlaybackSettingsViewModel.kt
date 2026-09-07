package com.otakustream.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.player.PlayerSettingsPrefs
import com.otakustream.core.player.SubtitleStyle
import com.otakustream.core.player.SubtitleStylePrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PlaybackSettingsUiState(
    val autoSkipEnabled: Boolean = false,
    val seekDurationMs: Long = 10_000L,
    val defaultSpeed: Float = 1f,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
)

// Backs the Playback settings screen.
//
// Both prefs classes read and write SharedPreferences synchronously on property access, and
// PlayerSettingsPrefs is explicitly lazy so that constructing it doesn't touch the disk on the main
// thread. Reading them straight from a composable would undo that — a settings screen is exactly
// the first place these files get opened — so the load happens on Dispatchers.IO and the screen
// renders from a StateFlow.
//
// Writes go the same way, with the in-memory value updated first so a switch flips on the frame it
// is tapped rather than a disk write later.
@HiltViewModel
class PlaybackSettingsViewModel @Inject constructor(
    private val playerSettingsPrefs: PlayerSettingsPrefs,
    private val subtitleStylePrefs: SubtitleStylePrefs,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlaybackSettingsUiState())
    val uiState: StateFlow<PlaybackSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                PlaybackSettingsUiState(
                    autoSkipEnabled = playerSettingsPrefs.autoSkipEnabled,
                    seekDurationMs = playerSettingsPrefs.seekDurationMs,
                    defaultSpeed = playerSettingsPrefs.defaultSpeed,
                    subtitleStyle = subtitleStylePrefs.load(),
                )
            }
            _uiState.value = loaded
        }
    }

    fun setAutoSkip(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(autoSkipEnabled = enabled)
        viewModelScope.launch { withContext(Dispatchers.IO) { playerSettingsPrefs.autoSkipEnabled = enabled } }
    }

    fun setSeekDuration(durationMs: Long) {
        _uiState.value = _uiState.value.copy(seekDurationMs = durationMs)
        viewModelScope.launch { withContext(Dispatchers.IO) { playerSettingsPrefs.seekDurationMs = durationMs } }
    }

    fun setDefaultSpeed(speed: Float) {
        _uiState.value = _uiState.value.copy(defaultSpeed = speed)
        viewModelScope.launch { withContext(Dispatchers.IO) { playerSettingsPrefs.defaultSpeed = speed } }
    }

    fun setSubtitleStyle(style: SubtitleStyle) {
        // The sliders in the subtitle controls emit on every drag frame, so this lands dozens of
        // times a second. State first and the write behind it keeps the drag smooth; SharedPreferences
        // coalesces the apply()s, and the last one wins, which is the value on screen.
        _uiState.value = _uiState.value.copy(subtitleStyle = style)
        viewModelScope.launch { withContext(Dispatchers.IO) { subtitleStylePrefs.save(style) } }
    }
}
