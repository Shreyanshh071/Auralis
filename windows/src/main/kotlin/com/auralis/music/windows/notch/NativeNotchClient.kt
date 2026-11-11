package com.auralis.music.windows.notch

import com.auralis.music.util.Log
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

internal enum class NotchAction(val wire: String) {
    PLAY_PAUSE("playPause"), PREVIOUS("previous"), NEXT("next"), SEEK("seek"),
    OPEN_PLAYER("openPlayer"), OPEN_SETTINGS("openSettings")
}
internal data class NotchCommand(val action: NotchAction, val positionMs: Long? = null)

/** Reject stale-track, unknown, oversized, and invalid numeric commands at the process boundary. */
internal fun parseNotchCommand(line: String, trackId: String?, durationMs: Long): NotchCommand? = runCatching {
    if (line.length > 16_384) return null
    val json = JSONObject(line)
    if (json.optInt("version") != 1 || json.optString("type") != "command") return null
    val action = NotchAction.entries.firstOrNull { it.wire == json.optString("action") } ?: return null
    if (action == NotchAction.OPEN_PLAYER || action == NotchAction.OPEN_SETTINGS) return NotchCommand(action)
    if (trackId == null || json.optString("trackId") != trackId) return null
    if (action != NotchAction.SEEK) return NotchCommand(action)
    val raw = json.opt("positionMs") as? Number ?: return null
    val position = raw.toDouble()
    if (!position.isFinite() || position < 0 || durationMs <= 0) return null
    NotchCommand(action, position.coerceAtMost(durationMs.toDouble()).toLong())
}.getOrNull()

internal data class NotchSnapshot(
    val visible: Boolean, val trackId: String, val title: String, val artist: String, val artwork: String,
    val playing: Boolean, val buffering: Boolean, val positionMs: Long, val durationMs: Long,
    val canPrevious: Boolean, val canNext: Boolean
) {
    fun json(): String = JSONObject().put("version", 1).put("type", "state").put("visible", visible)
        .put("trackId", trackId.take(1024)).put("title", title.take(4096)).put("artist", artist.take(4096))
        .put("artwork", artwork.take(8192)).put("playing", playing).put("buffering", buffering)
        .put("positionMs", positionMs.coerceAtLeast(0)).put("durationMs", durationMs.coerceAtLeast(0))
        .put("canPrevious", canPrevious).put("canNext", canNext).toString()
}

/** One private child, no ports/login items/global source selection. EOF also cleans up on a crash. */
internal class NativeNotchClient(private val onCommand: (String) -> Unit) : AutoCloseable {
    private val readers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var lastStartMs: Long? = null
    private var closed = false

    @Synchronized fun prepare(): Boolean {
        if (process?.isAlive == true) return true
        return update(NotchSnapshot(false, "", "", "", "", false, false, 0, 0, false, false), startIfHidden = true)
    }

    @Synchronized fun update(snapshot: NotchSnapshot, startIfHidden: Boolean = false): Boolean {
        if (closed) return false
        if (process?.isAlive != true) {
            writer?.runCatching { close() }
            writer = null
            process = null
            if (!snapshot.visible && !startIfHidden) return true
            val now = System.nanoTime() / 1_000_000
            if (lastStartMs?.let { now - it < 5_000 } == true) return false
            lastStartMs = now
            val binary = nativeNotchExecutable() ?: return false
            try {
                val child = ProcessBuilder(binary.absolutePath).start()
                process = child
                writer = child.outputStream.bufferedWriter(Charsets.UTF_8)
                readers.launch {
                    runCatching {
                        child.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                            while (true) {
                                val line = input.readLine() ?: break
                                if (line.length <= 16_384) onCommand(line)
                            }
                        }
                    }
                }
                readers.launch {
                    runCatching {
                        child.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                            lines.forEach { Log.w("AuralisNotch", it.take(2000)) }
                        }
                    }
                }
            } catch (error: Exception) {
                Log.w("AuralisNotch", "Could not launch bundled native notch", error)
                return false
            }
        }
        return try {
            writer!!.apply { write(snapshot.json()); newLine(); flush() }
            true
        } catch (error: Exception) {
            Log.w("AuralisNotch", "Native notch connection ended", error)
            stop()
            false
        }
    }

    @Synchronized fun stop() {
        val child = process
        writer?.runCatching { close() }
        writer = null
        process = null
        if (child != null) CoroutineScope(Dispatchers.IO).launch {
            if (!child.waitFor(1500, TimeUnit.MILLISECONDS)) {
                child.destroy()
                if (!child.waitFor(500, TimeUnit.MILLISECONDS)) child.destroyForcibly()
            }
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        val child = process
        writer?.runCatching { close() }
        writer = null
        process = null
        if (child != null) CoroutineScope(Dispatchers.IO).launch {
            if (!child.waitFor(1500, TimeUnit.MILLISECONDS)) child.destroyForcibly()
        }
        readers.cancel()
    }
}

internal fun nativeNotchExecutable(): File? {
    if (!System.getProperty("os.name").orEmpty().lowercase().contains("mac")) return null
    if (System.getProperty("os.arch") !in setOf("aarch64", "arm64")) return null
    if ((System.getProperty("os.version").substringBefore('.').toIntOrNull() ?: 0) < 14) return null
    val suffix = "auralis-notch/Auralis Notch.app/Contents/MacOS/AuralisNotch"
    val candidates = listOfNotNull(
        System.getProperty("auralis.notch.helper")?.let(::File),
        System.getProperty("compose.application.resources.dir")?.let { File(it, suffix) },
        if (System.getProperty("jpackage.app-path") == null) File("windows/build/notch-resources/macos-arm64", suffix) else null,
        if (System.getProperty("jpackage.app-path") == null) File("build/notch-resources/macos-arm64", suffix) else null
    )
    return candidates.firstOrNull { it.isFile && it.canExecute() }
}
