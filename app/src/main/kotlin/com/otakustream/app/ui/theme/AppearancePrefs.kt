package com.otakustream.app.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "appearance"
private const val KEY_THEME_MODE = "theme_mode"

// Reads the stored mode without needing the singleton, so the activity can know which scheme it is
// about to draw *before* Hilt has injected anything.
//
// That ordering is the whole reason this is a free function. Field injection into an
// @AndroidEntryPoint activity happens inside super.onCreate(), but the window's theme has to be
// chosen before it — otherwise the first frame is painted from the system's night setting rather
// than the user's choice, and someone who forces light on a dark phone gets a dark flash on every
// cold start.
//
// An unrecognised or missing value falls back to SYSTEM rather than throwing: it is a string on
// disk that a downgrade or a hand-edited file could make meaningless, and following the platform is
// the right answer when we don't know what was wanted.
fun storedThemeMode(context: Context): ThemeMode {
    val stored = runCatching {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_THEME_MODE, null)
    }.getOrNull()
    return ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
}

// Resolves SYSTEM against a configuration, for the callers that need the answer outside a
// composition. isDark() in Theme.kt is the same question asked from inside one.
fun ThemeMode.isDark(configuration: Configuration): Boolean = when (this) {
    ThemeMode.SYSTEM ->
        configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
}

// Tells the platform which mode this app is in, so resource qualifiers resolve to the app's choice
// rather than the phone's.
//
// This is what fixes the cold-start flash properly on API 31 and above — including the system
// splash screen, which is drawn by the system before the app has a process, and so is reachable no
// other way. Below 31 there is no equivalent and the splash follows the phone; setTheme() in the
// activity still corrects the window behind it, which is the part the app can reach.
fun applyAppNightMode(context: Context, mode: ThemeMode) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val manager = context.getSystemService(UiModeManager::class.java) ?: return
    runCatching {
        manager.setApplicationNightMode(
            when (mode) {
                ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            },
        )
    }
}

// Where the theme mode is kept. One enum, read once at startup and written when the user changes it.
//
// Read eagerly in the constructor rather than filled in later by a coroutine, which is the shape
// StremioDirectorySettings uses. That shape is wrong for this value twice over: a mode that arrives
// after the first frame means the app paints in the wrong scheme and then flips, which is the exact
// flash the setting exists to avoid; and a startup read landing after a write can overwrite the
// user's choice with the old value. Reading synchronously costs one small SharedPreferences file
// load on the way to a frame that cannot be drawn without the answer anyway, and it removes the
// race entirely.
@Singleton
class AppearancePrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _themeMode = MutableStateFlow(storedThemeMode(context))

    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    // A scope of its own because this outlives any screen: the write must finish even if the
    // settings screen that started it is gone by the time the disk gets to it.
    //
    // limitedParallelism(1), so the writes are a queue rather than a race. Plain Dispatchers.IO
    // would run two taps' commits concurrently and let them land in either order — System then
    // Dark could persist as System while the app showed Dark, and the setting would silently undo
    // itself on the next launch. That is the same failure the switch to commit() was meant to
    // remove, so leaving the ordering open would have half-fixed it.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    fun setThemeMode(mode: ThemeMode) {
        // In-memory first, so the UI turns over on the same frame as the tap. The flow is the only
        // copy anything reads during a session, so nothing here waits on the disk.
        _themeMode.value = mode
        applyAppNightMode(context, mode)
        // commit(), not apply(): apply() returns before the write lands, and a process death in
        // that window would bring the app back in the mode the user just changed away from — a
        // setting that silently un-sets itself. Off the main thread because commit() is a
        // synchronous disk write and this is called from a tap handler.
        writeScope.launch {
            runCatching {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_THEME_MODE, mode.name)
                    .commit()
            }
        }
    }
}
