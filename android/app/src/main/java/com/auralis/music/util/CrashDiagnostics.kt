package com.auralis.music.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.WebView
import com.auralis.music.BuildConfig
import java.io.File
import java.util.Date

/**
 * Remembers why the app last died, on this phone only. Nothing is sent anywhere: the user copies
 * the report from About and shares it themselves.
 */
object CrashDiagnostics {
    private const val CRASH_FILE = "last_crash.txt"
    private const val EXIT_REASONS = 5

    fun install(context: Context) {
        val crashFile = File(context.applicationContext.filesDir, CRASH_FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                crashFile.writeText("${Date()} on thread ${thread.name}\n${Log.getStackTraceString(error)}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun report(context: Context): String = buildString {
        appendLine("Auralis ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("WebView: ${webViewVersion()}")
        appendLine()
        appendLine("Recent exits:")
        appendLine(exitReasons(context))
        appendLine()
        appendLine("Last crash:")
        append(
            runCatching { File(context.filesDir, CRASH_FILE).takeIf { it.exists() }?.readText() }.getOrNull()
                ?: "none recorded"
        )
    }

    private fun webViewVersion(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "unknown"
        val info = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull() ?: return "unknown"
        return "${info.packageName} ${info.versionName}"
    }

    private fun exitReasons(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "not available before Android 11"
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exits = runCatching {
            manager.getHistoricalProcessExitReasons(null, 0, EXIT_REASONS)
        }.getOrDefault(emptyList())
        if (exits.isEmpty()) return "none recorded"
        return exits.joinToString("\n") { exit ->
            "${Date(exit.timestamp)}: ${reasonName(exit.reason)}, status=${exit.status}, importance=${exit.importance}, ${exit.description.orEmpty()}"
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "UNKNOWN($reason)"
    }
}
