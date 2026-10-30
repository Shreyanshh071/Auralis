package com.auralis.music.windows.media

import com.auralis.music.domain.model.Track
import com.auralis.music.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/** macOS Notification Center integration for song changes and playback notifications. */
internal object MacSongNotifications {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private const val SCRIPT = "on run argv\n    display notification (item 1 of argv) with title (item 2 of argv) subtitle \"Auralis\"\nend run"

    fun show(track: Track) {
        scope.launch {
            if (!com.auralis.music.windows.settings.DesktopSystemPreferences.store.settings.value.songNotifications) return@launch
            runCatching { send(track.title, track.artist) }
                .onFailure { Log.w("AuralisNotifications", "macOS notification failed: ${it.message}") }
        }
    }

    suspend fun test() = withContext(Dispatchers.IO) {
        send("Song notifications", "Auralis is playing in the background")
    }

    fun send(title: String, subtitle: String) {
        val process = ProcessBuilder("osascript", "-e", SCRIPT, subtitle, title)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("macOS notification timed out")
        }
        val exitCode = process.exitValue()
        check(exitCode == 0) { "macOS notification exited with code $exitCode" }
    }
}

/** Unified cross-platform song notification dispatcher. */
internal object DesktopSongNotifications {
    private val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    fun show(track: Track) {
        if (isMac) {
            MacSongNotifications.show(track)
        } else {
            WindowsSongNotifications.show(track)
        }
    }

    suspend fun test() {
        if (isMac) {
            MacSongNotifications.test()
        } else {
            WindowsSongNotifications.test()
        }
    }
}
