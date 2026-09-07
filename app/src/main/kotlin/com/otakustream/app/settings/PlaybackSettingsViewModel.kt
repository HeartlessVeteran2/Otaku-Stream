package com.otakustream.app.settings

import androidx.lifecycle.ViewModel
import com.otakustream.core.player.PlayerSettingsPrefs
import com.otakustream.core.player.SubtitleStyle
import com.otakustream.core.player.SubtitleStylePrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
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

    // Same arrangement for the subtitle style: SubtitleStylePrefs owns the value, the load, the
    // edit guard and the debounced write, so this screen and a player already on the back stack
    // read the same one.
    val subtitleStyle: StateFlow<SubtitleStyle> = subtitleStylePrefs.style

    fun setAutoSkip(enabled: Boolean) = playerSettingsPrefs.setAutoSkipEnabled(enabled)

    fun setSeekDuration(durationMs: Long) = playerSettingsPrefs.setSeekDurationMs(durationMs)

    fun setDefaultSpeed(speed: Float) = playerSettingsPrefs.setDefaultSpeed(speed)

    fun setSubtitleStyle(style: SubtitleStyle) = subtitleStylePrefs.set(style)

    override fun onCleared() {
        super.onCleared()
        // Writes a still-pending debounced change immediately, so leaving the screen mid-drag can't
        // lose the adjustment that is on screen to a process death inside the debounce window.
        subtitleStylePrefs.flush()
    }
}
