package com.otakustream.app.crash

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

// Turns silent crashes into a visible, copyable report. Installs a process-wide uncaught-exception
// handler that captures the stack trace, launches CrashActivity (which runs in a separate ":crash"
// process so it survives the crashing process being killed), then kills the crashed process.
object CrashReporter {

    const val EXTRA_REPORT = "crash_report"

    fun install(application: Application) {
        // Never install the handler inside the crash-reporter's own process, or a crash there would
        // recurse into itself.
        if (currentProcessName(application)?.endsWith(CRASH_PROCESS_SUFFIX) == true) return

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Asked before the launch, not after, because there is no "after" to inspect.
            //
            // startActivity is a synchronous binder call, so a launch that is *accepted* is
            // committed before killProcess runs — that part was never the gap. The gap is a launch
            // the platform declines: since API 29 a background process may not start an activity,
            // and the refusal is a log line, not an exception. The catch below never fired, nothing
            // retried, and the crash vanished — no screen, and no handler chain either, because the
            // fallback sat only inside that catch.
            //
            // It cannot be detected after the fact, so it is predicted instead: if this process is
            // not in the foreground, the screen is not going to appear, and handing the crash
            // straight to the platform's handler is strictly better than killing the process in
            // silence.
            val canShowScreen = shouldLaunchCrashScreen(
                sdkInt = Build.VERSION.SDK_INT,
                importance = processImportance(application),
            )
            val launched = canShowScreen && runCatching {
                val intent = Intent(application, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(EXTRA_REPORT, buildReport(application, throwable))
                }
                application.startActivity(intent)
            }.isSuccess

            if (!launched) {
                // Don't swallow the crash — hand it to the platform's default handler so it still
                // surfaces (logcat / system dialog).
                previous?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            // Tear down the crashed process so the reporter process's CrashActivity comes up clean.
            Process.killProcess(Process.myPid())
            exitProcess(CRASH_EXIT_CODE)
        }
    }

    private fun buildReport(context: Context, throwable: Throwable): String {
        val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("Otaku-Stream crash report")
            appendLine("Time: $timestamp")
            appendLine("App version: $versionName")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            append(stackTrace)
        }
    }

    // Whether a crash screen launched from this process would actually be shown.
    //
    // Split out and internal so the rule can be tested: the handler around it needs a real
    // Application, a real Looper and a process to kill, none of which a JVM test has.
    //
    // Only where the restriction exists, and only for the states it actually exempts.
    //
    // The restriction arrived in Q. minSdk here is 24, and on API 24-28 a background process may
    // start an activity freely — so gating those devices would suppress the crash screen for a rule
    // that does not apply to them. Below Q, always try.
    //
    // From Q up, the exemption list is narrower than it is tempting to assume. A process with a
    // *visible window* qualifies: IMPORTANCE_FOREGROUND (an activity the user is interacting with)
    // and IMPORTANCE_VISIBLE (a window on screen but not focused). A bare foreground service does
    // not — which is the whole reason full-screen-intent notifications exist — so
    // IMPORTANCE_FOREGROUND_SERVICE is excluded even though it is numerically "more important" than
    // VISIBLE. An earlier version let it through on the strength of a `<=` and a wrong belief about
    // the exemption list.
    //
    // Being wrong in this direction is the only affordable one. The prediction protects nothing it
    // predicts optimistically: a declined launch is a log line, not an exception, so
    // `startActivity` still "succeeds", `launched` is still true, and the process is still killed
    // in silence — the exact failure this exists to fix. A conservative miss costs a logcat trace
    // instead of a nice screen; an optimistic one costs the whole crash.
    //
    // (ActivityManager numbers importance so that *lower is more important* — FOREGROUND 100,
    // FOREGROUND_SERVICE 125, VISIBLE 200, PERCEPTIBLE 230, SERVICE 300, CACHED 400 — which is why
    // this is a membership test rather than a comparison.)
    internal fun shouldLaunchCrashScreen(sdkInt: Int, importance: Int): Boolean {
        if (sdkInt < Build.VERSION_CODES.Q) return true
        return importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    // IMPORTANCE_UNKNOWN rather than a default that guesses: runningAppProcesses can return null on
    // some OEM builds, and treating "we could not tell" as foreground would put us back to killing
    // the process on a launch that never happened.
    private fun processImportance(context: Context): Int {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return IMPORTANCE_UNKNOWN
        val pid = Process.myPid()
        return runCatching {
            manager.runningAppProcesses?.firstOrNull { it.pid == pid }?.importance
        }.getOrNull() ?: IMPORTANCE_UNKNOWN
    }

    private fun currentProcessName(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val pid = Process.myPid()
        return manager.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
    }

    private const val CRASH_PROCESS_SUFFIX = ":crash"
    private const val CRASH_EXIT_CODE = 10

    // Not a platform constant: ActivityManager has no "don't know". Above every real importance
    // value so it can never pass the comparison above by accident.
    internal const val IMPORTANCE_UNKNOWN = Int.MAX_VALUE
}
