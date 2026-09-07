package com.otakustream.core.player

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
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

// The speeds the app offers, in one place.
//
// The player's speed menu and the Playback settings screen both present this list, and they must
// present the same one: the settings screen shows the stored default as a selected chip, so a
// speed the player can set but settings cannot show leaves that screen with nothing selected and
// no way back to the value the user actually has.
val PLAYBACK_SPEED_OPTIONS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

// Player toggles that don't warrant Room storage, exposed as state rather than as properties.
//
// They used to be plain `var`s reading and writing SharedPreferences on access, and PlayerController
// read them exactly once — in its init block, into its own uiState. PlayerController is an
// app-lifetime @Singleton, so anything else that changed a value wrote the file and nothing more:
// the player kept its cached copy for the life of the process. That was survivable while the only
// way to change them was the player's own menus, which updated both. It stops being survivable the
// moment a Settings screen can change them, which is exactly what it would have done — a screen
// whose switches move and change nothing until the app is restarted.
//
// So the prefs own the value and everyone observes. One source of truth, both directions.
@Singleton
class PlayerSettingsPrefs @Inject constructor(@ApplicationContext context: Context) {
    // Lazy so constructing this @Singleton (which happens when MainActivity injects PlayerController)
    // doesn't load the prefs file on the main thread; the file loads on first actual read/write.
    private val prefs by lazy { context.getSharedPreferences("player_settings", Context.MODE_PRIVATE) }

    // Single-threaded, so the initial load and every subsequent write reach the file in the order
    // they were asked for rather than racing each other.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _autoSkipEnabled = MutableStateFlow(false)
    val autoSkipEnabled: StateFlow<Boolean> = _autoSkipEnabled.asStateFlow()

    // Double-tap-to-seek step, in milliseconds. Default 10s.
    private val _seekDurationMs = MutableStateFlow(DEFAULT_SEEK_DURATION_MS)
    val seekDurationMs: StateFlow<Long> = _seekDurationMs.asStateFlow()

    // Playback speed applied at the start of every video. Default 1x.
    private val _defaultSpeed = MutableStateFlow(DEFAULT_SPEED)
    val defaultSpeed: StateFlow<Float> = _defaultSpeed.asStateFlow()

    // Set the moment a user changes something, so the asynchronous initial load cannot land
    // afterwards and put the old value back on screen. Same guard PlayerViewModel uses for the
    // subtitle style, and needed for the same reason: the load is a disk read racing a tap.
    //
    // Guarded by `editLock` rather than merely @Volatile. The load ran `if (!edited) value = disk`
    // as two separate steps, so a setter landing between them set the flag, published the user's
    // value, and then had the load overwrite it with the stale one from disk anyway — the exact
    // failure the flag was added to prevent, just narrowed to a window instead of closed. Reading
    // the flag and writing the value have to happen together, on both sides.
    private val editLock = Any()

    private var autoSkipEdited = false

    private var seekEdited = false

    private var speedEdited = false

    // Completed once the initial read has finished (or failed), so a caller that needs the *stored*
    // value rather than the placeholder can wait for it. `defaultSpeed.value` is indistinguishable
    // from a real 1x until this completes, and the player applies it to the first video of the
    // session — reading it early silently discarded the user's saved speed on exactly that video.
    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch {
            runCatching {
                val storedAutoSkip = prefs.getBoolean(KEY_AUTO_SKIP, false)
                val storedSeek = prefs.getLong(KEY_SEEK_DURATION_MS, DEFAULT_SEEK_DURATION_MS)
                val storedSpeed = prefs.getFloat(KEY_DEFAULT_SPEED, DEFAULT_SPEED)
                synchronized(editLock) {
                    if (!autoSkipEdited) _autoSkipEnabled.value = storedAutoSkip
                    if (!seekEdited) _seekDurationMs.value = storedSeek
                    if (!speedEdited) _defaultSpeed.value = storedSpeed
                }
            }
            // Completed even when the read threw: a caller waiting on it would otherwise wait
            // forever, and the placeholder is the right answer if there is nothing readable.
            loaded.complete(Unit)
        }
    }

    // The stored default speed, waiting for the initial read if it has not landed yet.
    suspend fun awaitDefaultSpeed(): Float {
        loaded.await()
        return _defaultSpeed.value
    }

    fun setAutoSkipEnabled(enabled: Boolean) {
        synchronized(editLock) {
            autoSkipEdited = true
            _autoSkipEnabled.value = enabled
        }
        scope.launch { runCatching { prefs.edit().putBoolean(KEY_AUTO_SKIP, enabled).apply() } }
    }

    fun setSeekDurationMs(durationMs: Long) {
        synchronized(editLock) {
            seekEdited = true
            _seekDurationMs.value = durationMs
        }
        scope.launch { runCatching { prefs.edit().putLong(KEY_SEEK_DURATION_MS, durationMs).apply() } }
    }

    fun setDefaultSpeed(speed: Float) {
        synchronized(editLock) {
            speedEdited = true
            _defaultSpeed.value = speed
        }
        scope.launch { runCatching { prefs.edit().putFloat(KEY_DEFAULT_SPEED, speed).apply() } }
    }

    private companion object {
        const val KEY_AUTO_SKIP = "auto_skip_enabled"
        const val KEY_SEEK_DURATION_MS = "seek_duration_ms"
        const val KEY_DEFAULT_SPEED = "default_speed"
        const val DEFAULT_SEEK_DURATION_MS = 10_000L
        const val DEFAULT_SPEED = 1f
    }
}
