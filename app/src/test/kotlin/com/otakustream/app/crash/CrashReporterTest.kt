package com.otakustream.app.crash

import android.app.ActivityManager.RunningAppProcessInfo
import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// When the crash screen is worth trying, and when handing the crash to the platform is the better
// answer.
//
// The failure this guards: since API 29 a background process may not start an activity, and the
// platform declines by writing a log line rather than throwing. So the old code's try/catch never
// fired, nothing retried, and the crash disappeared — no screen, and no handler chain either,
// because the fallback lived only inside that catch.
//
// It cannot be detected after the fact, only predicted — and the prediction is only worth anything
// while it errs conservatively. Predicting "this will show" when it will not ends in the same
// silent kill, because a declined launch still leaves startActivity looking successful. So every
// case below that is not certainly showable is expected to fall back.
class CrashReporterTest {

    private val q = Build.VERSION_CODES.Q

    @Test
    fun `a visible app gets the crash screen`() {
        assertTrue(CrashReporter.shouldLaunchCrashScreen(q, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertTrue(CrashReporter.shouldLaunchCrashScreen(q, RunningAppProcessInfo.IMPORTANCE_VISIBLE))
    }

    // Below Q the restriction does not exist, and minSdk here is 24. Gating those devices would
    // suppress the screen for a rule that does not apply to them.
    @Test
    fun `before Android 10 the screen is always attempted`() {
        val pieces = listOf(
            RunningAppProcessInfo.IMPORTANCE_BACKGROUND,
            RunningAppProcessInfo.IMPORTANCE_CACHED,
            RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
            CrashReporter.IMPORTANCE_UNKNOWN,
        )
        pieces.forEach { importance ->
            assertTrue(
                "importance $importance on API 28 should still try",
                CrashReporter.shouldLaunchCrashScreen(Build.VERSION_CODES.P, importance),
            )
        }
    }

    // A bare foreground service is not on the background-activity-launch exemption list — which is
    // the whole reason full-screen-intent notifications exist. It reads as "more important" than
    // VISIBLE numerically, and an earlier version let it through on the strength of a `<=`; the
    // launch would have been declined and the crash lost.
    @Test
    fun `a foreground service falls back, despite ranking above visible`() {
        assertFalse(
            CrashReporter.shouldLaunchCrashScreen(q, RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE),
        )
    }

    // Audio playing with no window is PERCEPTIBLE — also not exempt.
    @Test
    fun `a merely perceptible process falls back`() {
        assertFalse(CrashReporter.shouldLaunchCrashScreen(q, RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE))
    }

    @Test
    fun `a backgrounded or cached process falls back to the platform handler`() {
        assertFalse(CrashReporter.shouldLaunchCrashScreen(q, RunningAppProcessInfo.IMPORTANCE_BACKGROUND))
        assertFalse(CrashReporter.shouldLaunchCrashScreen(q, RunningAppProcessInfo.IMPORTANCE_CACHED))
        assertFalse(CrashReporter.shouldLaunchCrashScreen(q, RunningAppProcessInfo.IMPORTANCE_SERVICE))
    }

    // "We could not tell" must not read as showable. runningAppProcesses returns null on some OEM
    // builds, and guessing optimistically puts us back to killing the process after a launch that
    // never happened — the exact silence this is about.
    @Test
    fun `an unknown importance falls back rather than guessing`() {
        assertFalse(CrashReporter.shouldLaunchCrashScreen(q, CrashReporter.IMPORTANCE_UNKNOWN))
    }
}
