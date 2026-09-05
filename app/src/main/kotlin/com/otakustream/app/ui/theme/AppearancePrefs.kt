package com.otakustream.app.ui.theme

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

// Where the theme mode is kept. One enum, read once at startup and written when the user changes it.
//
// Read eagerly in the constructor rather than filled in later by a coroutine, which is the shape
// StremioDirectorySettings uses. That shape is wrong for this value twice over: a mode that arrives
// after the first frame means the app paints in the wrong scheme and then flips, which is the exact
// flash the setting exists to avoid; and a startup read landing after a write can overwrite the
// user's choice with the old value. Reading synchronously costs one small SharedPreferences file
// load on the way to a frame that cannot be drawn without the answer anyway, and it removes the
// race entirely — the flow and the disk are written together, in that order, from one place.
@Singleton
class AppearancePrefs @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(readThemeMode())

    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    // An unrecognised or missing stored name falls back to SYSTEM rather than throwing — the value
    // is a string on disk that a downgrade or a hand-edited prefs file could make meaningless, and
    // following the platform is the right answer when we don't know what was wanted.
    private fun readThemeMode(): ThemeMode {
        val stored = runCatching { prefs.getString(KEY_THEME_MODE, null) }.getOrNull()
        return ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
    }

    private companion object {
        const val PREFS_NAME = "appearance"
        const val KEY_THEME_MODE = "theme_mode"
    }
}
