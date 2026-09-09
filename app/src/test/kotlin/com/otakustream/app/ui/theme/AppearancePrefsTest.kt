package com.otakustream.app.ui.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The theme setting has one job on top of storing an enum: never make the app paint in the wrong
// scheme. Every rule below exists because breaking it produces a visible flash or a setting that
// silently un-sets itself, and none of them are visible in the type signature.
//
// AppearancePrefs takes its dispatcher injected for these. With Dispatchers.IO written into the
// class the only way to observe a write was to sleep and hope, which is a flake waiting for a slow
// CI runner.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppearancePrefsTest {

    private val dispatcher = StandardTestDispatcher()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun prefs() = AppearancePrefs(context, dispatcher)

    private fun writeRawMode(value: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, value)
            .commit()
    }

    // The whole reason the constructor reads the file synchronously instead of launching.
    //
    // The activity picks the window's theme before super.onCreate(), and a mode that arrives one
    // dispatch later arrives after the first frame — so the app paints in the phone's scheme and
    // then flips to the user's. Nothing is advanced here on purpose: if the read ever moves into a
    // coroutine, this reads SYSTEM and fails.
    @Test
    fun `the stored mode is known before anything is dispatched`() = runTest(dispatcher) {
        writeRawMode(ThemeMode.LIGHT.name)

        assertEquals(
            "a mode that arrives a dispatch later arrives after the first frame",
            ThemeMode.LIGHT,
            prefs().themeMode.value,
        )
    }

    // Same rule from the other side: the tap turns the UI over now, and the disk write is a
    // consequence rather than the source of truth. Move the flow update inside the launch and this
    // fails, because that is a frame of the old scheme after every tap.
    @Test
    fun `the flow turns over on the tap, not on the write`() = runTest(dispatcher) {
        val appearance = prefs()

        appearance.setThemeMode(ThemeMode.DARK)

        assertEquals(
            "the setting must apply on the same frame as the tap",
            ThemeMode.DARK,
            appearance.themeMode.value,
        )
    }

    @Test
    fun `the choice survives to disk`() = runTest(dispatcher) {
        prefs().setThemeMode(ThemeMode.DARK)
        advanceUntilIdle()

        assertEquals(
            "a setting that does not reach disk is a setting that resets on every launch",
            ThemeMode.DARK,
            storedThemeMode(context),
        )
    }

    // Two taps in a row, and the second one wins in both places.
    //
    // Honest about what this pins: a StandardTestDispatcher is single-threaded, so it cannot
    // falsify the limitedParallelism(1) that makes the writes a queue — remove it and this still
    // passes. What it does catch is the end state disagreeing with itself, which is how the bug
    // showed up: the flow saying Dark while the file said System, so the app came back in the mode
    // the user had just changed away from. Proving the ordering itself needs real threads and a
    // slow write, which is a flake on a shared runner rather than a test.
    @Test
    fun `after two taps the flow and the file agree on the second one`() = runTest(dispatcher) {
        val appearance = prefs()

        appearance.setThemeMode(ThemeMode.DARK)
        appearance.setThemeMode(ThemeMode.LIGHT)
        advanceUntilIdle()

        assertEquals(ThemeMode.LIGHT, appearance.themeMode.value)
        assertEquals("the file must not disagree with what the app is showing", ThemeMode.LIGHT, storedThemeMode(context))
    }

    // A string on disk that means nothing: a downgrade that wrote an enum name this build no longer
    // has, or a hand-edited file. Following the phone is the right answer when we do not know what
    // was wanted — and it has to be an answer, not a crash on the way to the first frame.
    @Test
    fun `a mode this build does not recognise follows the phone`() = runTest(dispatcher) {
        writeRawMode("MIDNIGHT_OLED")

        assertEquals(ThemeMode.SYSTEM, storedThemeMode(context))
        assertEquals(ThemeMode.SYSTEM, prefs().themeMode.value)
    }

    @Test
    fun `a phone that has never been told follows the phone`() = runTest(dispatcher) {
        assertEquals(ThemeMode.SYSTEM, storedThemeMode(context))
        assertEquals(ThemeMode.SYSTEM, prefs().themeMode.value)
    }
}
