package com.otakustream.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.player.PlayerSettingsPrefs
import com.otakustream.core.player.SubtitleStyle
import com.otakustream.core.player.SubtitleStylePrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// Backs the Playback settings screen.
//
// The player toggles are read straight off PlayerSettingsPrefs' StateFlows rather than loaded into
// a local copy. That is not a shortcut — it is what makes this screen honest. PlayerController is
// an app-lifetime singleton that used to read those values once at startup, so a local copy here
// would have written the file while the running player kept its old value: switches that move and
// change nothing until the app restarts. Observing the same state means a change made here is the
// change the player sees, and a change made in the player's own menu shows up here.
//
// It also removes a race rather than papering over one. There is no asynchronous load to land after
// a tap and put the old value back, because there is no second copy of the value to be stale.
@HiltViewModel
class PlaybackSettingsViewModel @Inject constructor(
    private val playerSettingsPrefs: PlayerSettingsPrefs,
    private val subtitleStylePrefs: SubtitleStylePrefs,
) : ViewModel() {

    val autoSkipEnabled: StateFlow<Boolean> = playerSettingsPrefs.autoSkipEnabled
    val seekDurationMs: StateFlow<Long> = playerSettingsPrefs.seekDurationMs
    val defaultSpeed: StateFlow<Float> = playerSettingsPrefs.defaultSpeed

    // The subtitle style has no shared owner — SubtitleStylePrefs is load/save — so this one does
    // keep a local copy, with the same guard PlayerViewModel uses against its own load landing late.
    private val _subtitleStyle = MutableStateFlow(SubtitleStyle())
    val subtitleStyle: StateFlow<SubtitleStyle> = _subtitleStyle.asStateFlow()

    @Volatile
    private var styleEdited = false
    private var saveStyleJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val style = subtitleStylePrefs.load()
            if (!styleEdited) _subtitleStyle.value = style
        }
    }

    fun setAutoSkip(enabled: Boolean) = playerSettingsPrefs.setAutoSkipEnabled(enabled)

    fun setSeekDuration(durationMs: Long) = playerSettingsPrefs.setSeekDurationMs(durationMs)

    fun setDefaultSpeed(speed: Float) = playerSettingsPrefs.setDefaultSpeed(speed)

    fun setSubtitleStyle(style: SubtitleStyle) {
        // Debounced, exactly as PlayerViewModel does it. The sliders emit on every drag frame, so
        // an un-debounced save launches dozens of independent writes — enough to flood QueuedWork,
        // and with no ordering guarantee between them, so an earlier style can land last.
        styleEdited = true
        _subtitleStyle.value = style
        saveStyleJob?.cancel()
        saveStyleJob = viewModelScope.launch {
            delay(SUBTITLE_STYLE_SAVE_DEBOUNCE_MS)
            subtitleStylePrefs.save(style)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Flush a still-pending debounced change before viewModelScope is cancelled — otherwise
        // leaving the screen mid-drag discards the adjustment that is on screen.
        if (saveStyleJob?.isActive == true) {
            saveStyleJob?.cancel()
            subtitleStylePrefs.save(_subtitleStyle.value)
        }
    }

    private companion object {
        const val SUBTITLE_STYLE_SAVE_DEBOUNCE_MS = 400L
    }
}
