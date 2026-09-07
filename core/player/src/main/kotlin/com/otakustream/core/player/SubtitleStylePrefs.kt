package com.otakustream.core.player

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import javax.inject.Inject
import javax.inject.Singleton

enum class SubtitleEdgeStyle(val label: String) {
    NONE("None"),
    OUTLINE("Outline"),
    DROP_SHADOW("Shadow"),
    RAISED("Raised"),
}

enum class SubtitleTextColor(val label: String, val argb: Int) {
    WHITE("White", 0xFFFFFFFF.toInt()),
    YELLOW("Yellow", 0xFFFFEB3B.toInt()),
    CYAN("Cyan", 0xFF4DD0E1.toInt()),
    GREEN("Green", 0xFF81C784.toInt()),
}

enum class SubtitleBackground(val label: String, val argb: Int) {
    TRANSPARENT("None", 0x00000000),
    SEMI("Dim", 0x80000000.toInt()),
    SOLID("Black", 0xFF000000.toInt()),
}

data class SubtitleStyle(
    val textScale: Float = 1f,
    val edgeStyle: SubtitleEdgeStyle = SubtitleEdgeStyle.OUTLINE,
    val textColor: SubtitleTextColor = SubtitleTextColor.WHITE,
    val background: SubtitleBackground = SubtitleBackground.TRANSPARENT,
    // 0.08 is Media3 SubtitleView's own default bottom padding.
    val bottomMarginFraction: Float = 0.08f,
) {
    // Adjustment ranges live on the model, not the persistence class, so the UI can bound its
    // controls without depending on SubtitleStylePrefs.
    companion object {
        const val MIN_TEXT_SCALE = 0.5f
        const val MAX_TEXT_SCALE = 2f
        const val MAX_BOTTOM_MARGIN = 0.3f
    }
}

// SharedPreferences keeps subtitle appearance out of the Room schema, same as
// PlayerOnboardingPrefs — a handful of scalars doesn't warrant a migration.
//
// This owns the value, the debounce and the write, the way PlayerSettingsPrefs owns the player
// toggles — it used to be a bare load/save pair, and every caller reimplemented the rest around it.
// Two did: PlayerViewModel and PlaybackSettingsViewModel each kept a private copy, each guarded it
// against its own late load, and each debounced its own writes at a different interval (300ms and
// 400ms). Three things followed from that, all of them bugs:
//
//  - Two copies, no sync. Changing the style in Settings while the player sat in the back stack
//    left the player's copy untouched, and its load had already run, so returning to the video
//    showed the old subtitles until the process restarted. That is precisely the failure
//    PlayerSettingsPrefs exists to prevent, still live for the one setting it didn't cover.
//  - Both flush-on-exit paths were dead code. They ran `if (saveJob?.isActive == true)` inside
//    onCleared(), which ViewModel calls *after* cancelling viewModelScope — so the job is always
//    complete by then, the branch never runs, and a slider adjustment made in the last few hundred
//    milliseconds before leaving the screen was silently dropped.
//  - The debounced write ran on viewModelScope's Main dispatcher.
//
// Owning it here fixes all three at once: one StateFlow both screens observe, an app-lifetime
// single-threaded IO scope that no screen's disposal can cancel, and writes that are ordered
// against each other because they share that one thread.
@Singleton
class SubtitleStylePrefs @Inject constructor(@ApplicationContext context: Context) {
    // Lazy so injecting this @Singleton doesn't open and parse the file on whichever thread built
    // it — which, for the player, is the frame where the user has just tapped an episode.
    private val prefs by lazy { context.getSharedPreferences("subtitle_style", Context.MODE_PRIVATE) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    // Seeded with the defaults and replaced once the saved style has been read off disk. The
    // defaults render correctly and the real style lands a frame or two later, before subtitles
    // are decoded.
    private val _style = MutableStateFlow(SubtitleStyle())
    val style: StateFlow<SubtitleStyle> = _style.asStateFlow()

    // Guards the "has the user edited this yet" flag together with the value it protects. Opening
    // the subtitle sheet and dragging a slider can beat a slow disk read, and the load landing
    // afterwards would visibly snap the text back — so the load must see the flag and skip its
    // write in one indivisible step, not check the flag and then assign.
    private val editLock = Any()
    private var edited = false
    private var saveJob: Job? = null

    // Completed once the initial read has finished, or failed. Only the tests wait on it, and they
    // have to: "the load did not overwrite the user's edit" is a claim about what happens *after*
    // the load lands, so a test that does not know when that is can only sleep and hope.
    private val loaded = CompletableDeferred<Unit>()

    internal suspend fun awaitLoaded() = loaded.await()

    init {
        scope.launch {
            runCatching {
                val stored = read()
                synchronized(editLock) { if (!edited) _style.value = stored }
            }
            loaded.complete(Unit)
        }
    }

    fun set(style: SubtitleStyle) {
        // Slider drags emit on every frame: the preview updates instantly, the disk write waits, so
        // one drag is one commit instead of dozens racing through QueuedWork.
        synchronized(editLock) {
            edited = true
            _style.value = style
            saveJob?.cancel()
            saveJob = scope.launch {
                delay(SAVE_DEBOUNCE_MS)
                // Writes the current value rather than the captured one, so that a write which
                // slips past a concurrent cancel still stores the newest style, not an older frame
                // of the same drag.
                runCatching { write(_style.value) }
                // Cleared so flush() can tell "a change is waiting" from "the last change already
                // went". Under the lock, and by identity — read out of the context here because a
                // synchronized block is not a suspending one — so a newer job installed while this
                // one was finishing is not discarded by the one it replaced.
                val self = coroutineContext[Job]
                synchronized(editLock) { if (saveJob === self) saveJob = null }
            }
        }
    }

    // Writes any pending debounced change immediately, for a screen that is being destroyed and
    // would otherwise leave the change to a timer that a process death could beat.
    //
    // The write goes onto the same single-threaded scope as the debounce rather than running here,
    // which is what makes it safe to call from anywhere. Cancelling a job that has already passed
    // its delay does nothing — it is inside write() by then — so a flush that wrote inline would be
    // a second writer on a second thread. Queued, it is simply the next write on the one thread
    // that does them, and it writes _style.value, so whichever order they land in the file ends up
    // holding the newest style.
    //
    // Nothing is queued when no change is pending: saveJob is null before the first set and again
    // after each debounce completes, so an onCleared() on a screen where nothing was adjusted does
    // not rewrite the file.
    fun flush() {
        synchronized(editLock) {
            val job = saveJob ?: return
            job.cancel()
            saveJob = null
        }
        scope.launch { runCatching { write(_style.value) } }
    }

    private fun read(): SubtitleStyle {
        // Reference the data class's own defaults so there's a single source of truth.
        val default = SubtitleStyle()
        return SubtitleStyle(
            textScale = prefs.getFloat(KEY_TEXT_SCALE, default.textScale)
                .coerceIn(SubtitleStyle.MIN_TEXT_SCALE, SubtitleStyle.MAX_TEXT_SCALE),
            edgeStyle = enumFromPrefs(KEY_EDGE_STYLE, default.edgeStyle),
            textColor = enumFromPrefs(KEY_TEXT_COLOR, default.textColor),
            background = enumFromPrefs(KEY_BACKGROUND, default.background),
            bottomMarginFraction = prefs.getFloat(KEY_BOTTOM_MARGIN, default.bottomMarginFraction)
                .coerceIn(0f, SubtitleStyle.MAX_BOTTOM_MARGIN),
        )
    }

    private fun write(style: SubtitleStyle) {
        prefs.edit()
            .putFloat(KEY_TEXT_SCALE, style.textScale)
            .putString(KEY_EDGE_STYLE, style.edgeStyle.name)
            .putString(KEY_TEXT_COLOR, style.textColor.name)
            .putString(KEY_BACKGROUND, style.background.name)
            .putFloat(KEY_BOTTOM_MARGIN, style.bottomMarginFraction)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumFromPrefs(key: String, default: T): T {
        val stored = prefs.getString(key, null) ?: return default
        return runCatching { enumValueOf<T>(stored) }.getOrDefault(default)
    }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 300L
        const val KEY_TEXT_SCALE = "text_scale"
        const val KEY_EDGE_STYLE = "edge_style"
        const val KEY_TEXT_COLOR = "text_color"
        const val KEY_BACKGROUND = "background"
        const val KEY_BOTTOM_MARGIN = "bottom_margin"
    }
}
