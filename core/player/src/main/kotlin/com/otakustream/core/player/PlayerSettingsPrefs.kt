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

    private companion object {
        const val KEY_AUTO_SKIP = "auto_skip_enabled"
    }
}
