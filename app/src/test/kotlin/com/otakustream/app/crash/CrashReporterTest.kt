package com.otakustream.app.crash

import android.app.ActivityManager.RunningAppProcessInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// When the crash screen is worth trying, and when handing the crash to the platform is the better
// answer.
//
// The failure this guards: since API 29 a background process may not start an activity, and the
// platform declines by writing a log line rather than throwing. So the old code's try/catch never
// fired, nothing retried, and the crash disappeared — no screen, and no handler chain either,
// because the fallback lived only inside that catch. It cannot be detected after the fact; the only
// lever is to predict it and prefer a logcat trace over silence.
class CrashReporterTest {

    @Test
    fun `a visible app gets the crash screen`() {
        assertTrue(CrashReporter.shouldLaunchCrashScreen(RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertTrue(CrashReporter.shouldLaunchCrashScreen(RunningAppProcessInfo.IMPORTANCE_VISIBLE))
    }

    // A download running with the screen off is a foreground service. Background-activity-launch
    // rules exempt it, and a crash there is exactly the kind that otherwise vanishes.
    @Test
    fun `a foreground service still gets the crash screen`() {
        assertTrue(
            CrashReporter.shouldLaunchCrashScreen(RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE),
        )
    }

    // Audio playing with no window is PERCEPTIBLE — not exempt from background-activity-launch,
    // so the screen would be declined and the trace lost.
    @Test
    fun `a merely perceptible process falls back`() {
        assertFalse(CrashReporter.shouldLaunchCrashScreen(RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE))
    }

    @Test
    fun `a backgrounded or cached process falls back to the platform handler`() {
        assertFalse(CrashReporter.shouldLaunchCrashScreen(RunningAppProcessInfo.IMPORTANCE_BACKGROUND))
        assertFalse(CrashReporter.shouldLaunchCrashScreen(RunningAppProcessInfo.IMPORTANCE_CACHED))
        assertFalse(CrashReporter.shouldLaunchCrashScreen(RunningAppProcessInfo.IMPORTANCE_SERVICE))
    }

    // "We could not tell" must not read as foreground. runningAppProcesses returns null on some OEM
    // builds, and guessing optimistically puts us back to killing the process after a launch that
    // never happened — the exact silence this is about.
    @Test
    fun `an unknown importance falls back rather than guessing`() {
        assertFalse(CrashReporter.shouldLaunchCrashScreen(CrashReporter.IMPORTANCE_UNKNOWN))
    }
}
