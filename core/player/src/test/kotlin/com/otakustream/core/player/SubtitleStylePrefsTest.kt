package com.otakustream.core.player

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The subtitle style store, which used to be a bare load/save pair with two ViewModels
// reimplementing the rest around it — each with its own copy of the value, its own edit guard and
// its own debounce, at two different intervals.
@RunWith(RobolectricTestRunner::class)
class SubtitleStylePrefsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefsFile().edit().clear().commit()
    }

    private fun prefsFile(): SharedPreferences =
        context.getSharedPreferences("subtitle_style", Context.MODE_PRIVATE)

    @Test
    fun `a saved style is loaded back`() {
        prefsFile().edit()
            .putFloat("text_scale", 1.4f)
            .putString("edge_style", SubtitleEdgeStyle.RAISED.name)
            .putString("text_color", SubtitleTextColor.CYAN.name)
            .putString("background", SubtitleBackground.SOLID.name)
            .putFloat("bottom_margin", 0.2f)
            .commit()

        val style = SubtitleStylePrefs(context).loadedStyle()

        assertEquals(1.4f, style.textScale, 0f)
        assertEquals(SubtitleEdgeStyle.RAISED, style.edgeStyle)
        assertEquals(SubtitleTextColor.CYAN, style.textColor)
        assertEquals(SubtitleBackground.SOLID, style.background)
        assertEquals(0.2f, style.bottomMarginFraction, 0f)
    }

    // Out-of-range values in the file are clamped rather than trusted: a text scale of 40 would
    // render subtitles that fill the screen with no way to see the control that fixes it.
    @Test
    fun `a stored value outside its range is clamped`() {
        prefsFile().edit()
            .putFloat("text_scale", 40f)
            .putFloat("bottom_margin", 5f)
            .putString("edge_style", "NOT_AN_EDGE_STYLE")
            .commit()

        val style = SubtitleStylePrefs(context).loadedStyle()

        assertEquals(SubtitleStyle.MAX_TEXT_SCALE, style.textScale, 0f)
        assertEquals(SubtitleStyle.MAX_BOTTOM_MARGIN, style.bottomMarginFraction, 0f)
        assertEquals(SubtitleStyle().edgeStyle, style.edgeStyle)
    }

    // The bug that made this class own the value: the player and the settings screen each kept
    // their own copy, so changing the style in Settings left a player already on the back stack
    // showing the old one until the process restarted.
    @Test
    fun `every reader sees a change made by any of them`() {
        val prefs = SubtitleStylePrefs(context)
        prefs.loadedStyle()
        // Two readers of the same store, standing in for the two screens.
        val fromPlayer = prefs.style
        val fromSettings = prefs.style

        prefs.set(SubtitleStyle(textColor = SubtitleTextColor.YELLOW))

        assertEquals(SubtitleTextColor.YELLOW, fromPlayer.value.textColor)
        assertEquals(SubtitleTextColor.YELLOW, fromSettings.value.textColor)
    }

    // The edit guard, same shape as PlayerSettingsPrefs' and with the same caveat noted there:
    // dragging a slider the instant the sheet opens must not be undone by the disk read that was
    // already in flight.
    @Test
    fun `an edit made during the load is not overwritten by it`() {
        prefsFile().edit().putFloat("text_scale", 0.6f).commit()
        repeat(REPEATS) { attempt ->
            // A different scale each iteration, which matters for settle() below: polling the file
            // for a value the *previous* iteration already wrote would return immediately and prove
            // nothing about this one.
            val scale = 1.9f - attempt * 0.01f
            val prefs = SubtitleStylePrefs(context)
            prefs.set(SubtitleStyle(textScale = scale))
            prefs.loadedStyle()
            assertEquals(
                "attempt $attempt: the load put the file's value back over the user's",
                scale,
                prefs.style.value.textScale,
                0f,
            )
            // Settled before the next iteration, and this is not tidiness.
            //
            // Each `set` above arms a debounced write on that instance's *own* app-lifetime scope,
            // and every instance writes the same shared file. Left alone, fifty of them would fire
            // a few hundred milliseconds later — during whichever test ran next — and drop a stale
            // value into the file that test was asserting on. The suite would fail somewhere else,
            // intermittently, with nothing pointing back here.
            prefs.settle()
        }
    }

    // The debounce used to live on viewModelScope, and the flush that was meant to cover leaving
    // the screen mid-drag ran in onCleared() — after that scope had been cancelled — so it was
    // guarded on a job that was always already complete and never wrote anything. Owning the
    // debounce here is what makes flush() able to do its job.
    @Test
    fun `flush writes a change that is still inside the debounce window`() {
        val prefs = SubtitleStylePrefs(context)
        prefs.loadedStyle()
        prefs.set(SubtitleStyle(textScale = 1.6f))
        // Straight to flush, the way a screen destroyed mid-drag would — no waiting out the
        // debounce first. What is under test is that the change is written at all: without flush
        // this value is still sitting behind a 300ms timer.
        //
        // The poll is for flush's *dispatch*, not for its debounce. flush queues the write onto the
        // store's single-threaded scope rather than running it inline, so that it cannot become a
        // second writer racing a debounced write that has already passed its delay — the ordering
        // is what makes the result deterministic, at the cost of the write landing a scheduling hop
        // later than the call.
        prefs.settle()

        assertEquals(1.6f, prefsFile().getFloat("text_scale", -1f), 0f)
    }

    @Test
    fun `a change is written once the debounce elapses, without a flush`() {
        val prefs = SubtitleStylePrefs(context)
        prefs.loadedStyle()
        prefs.set(SubtitleStyle(textScale = 1.3f))

        var waited = 0L
        while (prefsFile().getFloat("text_scale", -1f) != 1.3f && waited < TIMEOUT_MS) {
            Thread.sleep(POLL_MS)
            waited += POLL_MS
        }
        assertEquals(1.3f, prefsFile().getFloat("text_scale", -1f), 0f)
    }

    @Test
    fun `a style written by one instance is what the next launch reads`() {
        val prefs = SubtitleStylePrefs(context)
        prefs.loadedStyle()
        prefs.set(SubtitleStyle(background = SubtitleBackground.SEMI, textScale = 1.1f))
        prefs.settle()

        val relaunched = SubtitleStylePrefs(context)
        assertNotSame(prefs, relaunched)
        val style = relaunched.loadedStyle()
        assertEquals(SubtitleBackground.SEMI, style.background)
        assertEquals(1.1f, style.textScale, 0f)
    }

    // The initial read runs on the store's own IO thread, and several of these tests are claims
    // about what is true *after* it lands. Waiting on the store's own signal rather than sleeping
    // is what keeps them from being assertions about thread scheduling.
    private fun SubtitleStylePrefs.loadedStyle(): SubtitleStyle {
        runBlocking { awaitLoaded() }
        return style.value
    }

    // Runs a pending debounced write to completion, so no instance this test built is still holding
    // a timer over the shared preferences file when the test returns.
    //
    // flush() writes inline, so by the time it returns SharedPreferences already holds this
    // instance's value and its own job is cancelled — there is nothing left to fire later. The
    // assertion is the check: if the file does not hold what this instance last set, the write did
    // not happen and every later test in this class is standing on sand.
    private fun SubtitleStylePrefs.settle() {
        flush()
        assertEquals(
            "flush() did not write; a later test would see this instance's value appear under it",
            style.value.textScale,
            prefsFile().getFloat("text_scale", -1f),
            0f,
        )
    }

    private companion object {
        const val REPEATS = 50
        const val POLL_MS = 10L
        const val TIMEOUT_MS = 5_000L
    }
}
