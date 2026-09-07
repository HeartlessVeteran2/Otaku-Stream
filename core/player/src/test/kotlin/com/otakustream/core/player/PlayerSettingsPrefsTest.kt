package com.otakustream.core.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The stored player toggles, tested against a real SharedPreferences on the real dispatcher the
// production code uses.
//
// That combination is the point. Every bug this class has had is about *timing* — a value read
// before the load that supplies it, a load landing after the tap that changed it — and neither
// reproduces against an in-memory fake that answers instantly.
@RunWith(RobolectricTestRunner::class)
class PlayerSettingsPrefsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefsFile().edit().clear().commit()
    }

    private fun prefsFile() = context.getSharedPreferences("player_settings", Context.MODE_PRIVATE)

    // The regression this file exists for.
    //
    // PlayerController applies the default speed to every new video, and it read `defaultSpeed.value`
    // to get it. That field starts at the 1x placeholder and is replaced when the asynchronous load
    // lands, so the very first video of a session — the one the user opened the app to watch — was
    // played at 1x however the setting was configured. Constructing and reading in the same breath
    // is exactly the shape of that failure.
    @Test
    fun `the saved speed is available to the first read, not only to later ones`() {
        prefsFile().edit().putFloat("default_speed", 1.5f).commit()
        // Repeated because a single pass could win the race by luck. Reading `.value` instead of
        // awaiting returns the placeholder on essentially every one of these: the load has not even
        // been dispatched to its thread by the time the constructor returns.
        repeat(REPEATS) { attempt ->
            val prefs = PlayerSettingsPrefs(context)
            val speed = runBlocking { prefs.awaitDefaultSpeed() }
            assertEquals("attempt $attempt read the placeholder, not the saved speed", 1.5f, speed, 0f)
        }
    }

    // An edit made while the initial load is still in flight must win. The load is a disk read
    // racing a tap, and the store's job is to make sure the tap survives.
    //
    // What this covers is the ordinary interleaving, repeatedly: the two really do run on different
    // threads here, so the guard is exercised rather than assumed. What it cannot cover is the
    // nanosecond-wide interleaving the lock was added for — the load reading the flag, the setter
    // running in full, and the load then assigning over it. Hitting that from a test needs a seam
    // inside the load to park on, and a seam that exists only for a test is worse than the narrow
    // window it would demonstrate; the lock closes it by construction instead.
    @Test
    fun `an edit made during the load is not overwritten by it`() {
        prefsFile().edit().putFloat("default_speed", 0.5f).commit()
        repeat(REPEATS) { attempt ->
            val prefs = PlayerSettingsPrefs(context)
            prefs.setDefaultSpeed(2f)
            runBlocking { prefs.awaitDefaultSpeed() }
            assertEquals(
                "attempt $attempt: the load put the file's value back over the user's",
                2f,
                prefs.defaultSpeed.value,
                0f,
            )
        }
    }

    @Test
    fun `a speed set here is the speed a later launch reads`() {
        PlayerSettingsPrefs(context).setDefaultSpeed(1.75f)
        assertEquals(1.75f, awaitFileValue { it.getFloat("default_speed", -1f) == 1.75f }
            .getFloat("default_speed", -1f), 0f)
        assertEquals(1.75f, runBlocking { PlayerSettingsPrefs(context).awaitDefaultSpeed() }, 0f)
    }

    @Test
    fun `auto-skip and seek duration survive a relaunch`() {
        val prefs = PlayerSettingsPrefs(context)
        prefs.setAutoSkipEnabled(true)
        prefs.setSeekDurationMs(30_000L)
        awaitFileValue { it.getBoolean("auto_skip_enabled", false) && it.getLong("seek_duration_ms", 0L) == 30_000L }

        val relaunched = PlayerSettingsPrefs(context)
        // The three values load together on one coroutine, so awaiting the speed is enough to know
        // all three have been read back.
        runBlocking { relaunched.awaitDefaultSpeed() }
        assertEquals(true, relaunched.autoSkipEnabled.value)
        assertEquals(30_000L, relaunched.seekDurationMs.value)
    }

    // The setters queue their write onto the store's own scope, so "has it been written yet" is a
    // question about coroutine scheduling rather than about the value. Polling the file answers it
    // without the test asserting anything about thread timing.
    private fun awaitFileValue(satisfied: (android.content.SharedPreferences) -> Boolean) = prefsFile().also {
        var waited = 0L
        while (!satisfied(it) && waited < TIMEOUT_MS) {
            Thread.sleep(POLL_MS)
            waited += POLL_MS
        }
    }

    private companion object {
        const val REPEATS = 50
        const val POLL_MS = 20L
        const val TIMEOUT_MS = 5_000L
    }
}
