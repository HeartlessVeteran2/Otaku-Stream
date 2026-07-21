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
            try {
                val intent = Intent(application, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(EXTRA_REPORT, buildReport(application, throwable))
                }
                application.startActivity(intent)
            } catch (secondary: Throwable) {
                // If we can't show the screen for any reason, don't swallow the crash — hand it to
                // the platform's default handler so it still surfaces (logcat / system dialog).
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

    private fun currentProcessName(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val pid = Process.myPid()
        return manager.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
    }

    private const val CRASH_PROCESS_SUFFIX = ":crash"
    private const val CRASH_EXIT_CODE = 10
}
