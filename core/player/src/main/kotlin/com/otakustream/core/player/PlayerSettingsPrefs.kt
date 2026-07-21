package com.otakustream.core.player

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// SharedPreferences-backed player toggles that don't warrant Room storage (mirrors
// PlayerOnboardingPrefs / SubtitleStylePrefs).
@Singleton
class PlayerSettingsPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("player_settings", Context.MODE_PRIVATE)

    var autoSkipEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SKIP, false)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_SKIP, value).apply() }

    // Double-tap-to-seek step, in milliseconds. Default 10s.
    var seekDurationMs: Long
        get() = prefs.getLong(KEY_SEEK_DURATION_MS, 10_000L)
        set(value) { prefs.edit().putLong(KEY_SEEK_DURATION_MS, value).apply() }

    // Playback speed applied at the start of every video. Default 1x.
    var defaultSpeed: Float
        get() = prefs.getFloat(KEY_DEFAULT_SPEED, 1f)
        set(value) { prefs.edit().putFloat(KEY_DEFAULT_SPEED, value).apply() }

    private companion object {
        const val KEY_AUTO_SKIP = "auto_skip_enabled"
        const val KEY_SEEK_DURATION_MS = "seek_duration_ms"
        const val KEY_DEFAULT_SPEED = "default_speed"
    }
}
