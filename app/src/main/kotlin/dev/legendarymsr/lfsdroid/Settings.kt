package dev.legendarymsr.lfsdroid

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Settings {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun defaultJobs(c: Context): Int {
        val mem = ActivityManager.MemoryInfo().also { c.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return Chroot.jobsFor(Runtime.getRuntime().availableProcessors(), mem.totalMem)
    }

    /** make -j for the build; the user's choice, else a RAM-safe default. */
    fun jobs(c: Context): Int = prefs(c).getInt("jobs", 0).takeIf { it in 1..MAX_JOBS } ?: defaultJobs(c)

    fun setJobs(c: Context, n: Int) = prefs(c).edit().putInt("jobs", n.coerceIn(1, MAX_JOBS)).apply()

    const val MAX_JOBS = 8

    /** Terminal font size in sp; pinch-to-zoom changes it. */
    fun termFont(c: Context): Float = TermGrid.clampFont(prefs(c).getFloat("term_font", TermGrid.DEFAULT_FONT))

    fun setTermFont(c: Context, sp: Float) = prefs(c).edit().putFloat("term_font", TermGrid.clampFont(sp)).apply()
}

/** Stack traces from crashes, kept in a file and shown on the next launch. */
object CrashLog {
    fun file(c: Context) = File(c.filesDir, "crash.txt")

    fun install(c: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write(c, "uncaught on thread ${t.name}", e) }
            prev?.uncaughtException(t, e)
        }
    }

    fun write(c: Context, what: String, e: Throwable) {
        file(c).appendText(format(what, e, System.currentTimeMillis()))
        // Keep only the newest ~64 KB.
        val f = file(c)
        if (f.length() > 64 * 1024) f.writeText(f.readText().takeLast(32 * 1024))
    }

    fun format(what: String, e: Throwable, now: Long): String {
        val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
        return "=== ${stamp(now)} · $what\n$sw\n"
    }

    fun read(c: Context): String? = file(c).takeIf { it.exists() && it.length() > 0 }?.readText()?.takeLast(16 * 1024)

    fun clear(c: Context) { file(c).delete() }

    fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(ms))
}

/** Why the app's process last died, from Android itself. */
object ExitReason {
    fun last(c: Context): String? = runCatching {
        val info = c.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(c.packageName, 0, 1).firstOrNull() ?: return null
        describe(info.reason, info.description, info.timestamp, info.pss)
    }.getOrNull()

    fun describe(reason: Int, description: String?, timestamp: Long, pssKb: Long): String {
        val name = when (reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory (lmkd)"
            ApplicationExitInfo.REASON_CRASH -> "crashed (Java)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed (native)"
            ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for excessive resource use"
            ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user"
            ApplicationExitInfo.REASON_USER_STOPPED -> "force-stopped"
            ApplicationExitInfo.REASON_EXIT_SELF -> "exited normally"
            ApplicationExitInfo.REASON_FREEZER -> "frozen and killed"
            ApplicationExitInfo.REASON_OTHER -> "killed by the system"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a dependency died"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
            else -> "reason $reason"
        }
        val mem = if (pssKb > 0) " · ${pssKb / 1024} MB" else ""
        return "${CrashLog.stamp(timestamp)} · $name${description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}$mem"
    }
}
