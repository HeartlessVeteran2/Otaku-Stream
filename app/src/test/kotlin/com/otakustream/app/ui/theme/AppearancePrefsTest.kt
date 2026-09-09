package com.otakustream.app.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // Two taps in a row, and the second one has to win on disk.
    //
    // This is the one test here that does not use the test dispatcher, and it cannot: a
    // StandardTestDispatcher runs everything on one thread in submission order, so it satisfies the
    // ordering for free and would pass with limitedParallelism(1) deleted. The regression needs two
    // threads to exist at all.
    //
    // So: a real two-thread pool, and a SharedPreferences whose commit() for the first value sleeps.
    // With the writes serialized, Dark commits (slowly), then Light — Light lands last, whatever the
    // sleep is, because the queue decides the order and not the clock. Without it, both writes start
    // at once on separate threads, Light commits immediately and Dark commits after its sleep, and
    // the app comes back in the mode the user had just changed away from. Verified by deleting
    // limitedParallelism(1): this fails and nothing else does.
    //
    // The sleep only exists to make the broken ordering observable. Correct code passes on any
    // timing, so a loaded runner cannot turn this red — the property being asserted is the queue,
    // not the delay.
    @Test
    fun `the second of two rapid taps is what lands on disk`() {
        val writes = CountDownLatch(2)
        val slowContext = SlowCommitContext(context, slowValue = ThemeMode.DARK.name, delayMs = 300, commits = writes)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val appearance = AppearancePrefs(slowContext, pool.asCoroutineDispatcher())

            appearance.setThemeMode(ThemeMode.DARK)
            appearance.setThemeMode(ThemeMode.LIGHT)
            assertTrue("both writes should have run", writes.await(10, TimeUnit.SECONDS))

            assertEquals(ThemeMode.LIGHT, appearance.themeMode.value)
            assertEquals(
                "the older write overtook the newer one: the app comes back in the mode the user left",
                ThemeMode.LIGHT,
                storedThemeMode(slowContext),
            )
        } finally {
            pool.shutdownNow()
        }
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

// A Context whose preferences file takes its time over one particular value, so the order two
// concurrent writes land in becomes observable. Everything else delegates untouched.
private class SlowCommitContext(
    base: Context,
    private val slowValue: String,
    private val delayMs: Long,
    private val commits: CountDownLatch,
) : ContextWrapper(base) {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        SlowCommitPrefs(super.getSharedPreferences(name, mode), slowValue, delayMs, commits)
}

private class SlowCommitPrefs(
    private val delegate: SharedPreferences,
    private val slowValue: String,
    private val delayMs: Long,
    private val commits: CountDownLatch,
) : SharedPreferences by delegate {
    override fun edit(): SharedPreferences.Editor =
        SlowCommitEditor(delegate.edit(), slowValue, delayMs, commits)
}

private class SlowCommitEditor(
    private val delegate: SharedPreferences.Editor,
    private val slowValue: String,
    private val delayMs: Long,
    private val commits: CountDownLatch,
) : SharedPreferences.Editor by delegate {
    private var pending: String? = null

    override fun putString(key: String, value: String?): SharedPreferences.Editor {
        pending = value
        delegate.putString(key, value)
        // `this`, not the delegate: the chain has to stay on the wrapper or commit() below is never
        // the one that runs.
        return this
    }

    override fun commit(): Boolean {
        if (pending == slowValue) Thread.sleep(delayMs)
        val committed = delegate.commit()
        commits.countDown()
        return committed
    }
}
