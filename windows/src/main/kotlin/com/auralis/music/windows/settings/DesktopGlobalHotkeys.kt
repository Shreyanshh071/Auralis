package com.auralis.music.windows.settings

import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.event.KeyEvent

internal data class HotkeyAction(val id: String, val title: String, val default: String)
internal val hotkeyActions = listOf(
    HotkeyAction("play_pause", "Play / pause", "Ctrl+Alt+Space"), HotkeyAction("next_track", "Next track", "Ctrl+Alt+Right"),
    HotkeyAction("prev_track", "Previous track", "Ctrl+Alt+Left"), HotkeyAction("seek_forward", "Seek forward 5 seconds", "Ctrl+Alt+Shift+Right"),
    HotkeyAction("seek_backward", "Seek backward 5 seconds", "Ctrl+Alt+Shift+Left"), HotkeyAction("shuffle", "Shuffle queue", "Ctrl+Alt+S"),
    HotkeyAction("repeat", "Cycle repeat", "Ctrl+Alt+R"), HotkeyAction("volume_up", "Volume up", "Ctrl+Alt+Up"),
    HotkeyAction("volume_down", "Volume down", "Ctrl+Alt+Down"), HotkeyAction("mute", "Mute / unmute", "Ctrl+Alt+M"),
    HotkeyAction("show_app", "Show Auralis", "Ctrl+Alt+A"))

internal fun parseHotkey(combo: String): Pair<Int, Int> {
    val parts = combo.split('+')
    require(parts.size >= 2) { "Use Ctrl, Alt, Shift or Win with a key." }
    var mods = 0x4000
    parts.dropLast(1).forEach { mods = mods or when (it.lowercase()) {
        "ctrl", "control" -> 2; "alt" -> 1; "shift" -> 4; "win" -> 8; else -> error("Unknown modifier: $it") } }
    val name = parts.last().uppercase()
    val key = if (name.length == 1) KeyEvent.getExtendedKeyCodeForChar(name[0].code)
        else runCatching { KeyEvent::class.java.getField("VK_$name").getInt(null) }.getOrElse { error("Unknown key: $name") }
    require(key != 0) { "Choose a valid key." }
    return mods to key
}

/** RegisterHotKey keeps shortcuts working with another app in front, without recording keystrokes. */
internal class DesktopGlobalHotkeys(private val playback: DesktopPlaybackController?, private val show: () -> Unit) : AutoCloseable {
    companion object {
        private val mutableErrors = MutableStateFlow<Map<String, String>>(emptyMap())
        val errors = mutableErrors.asStateFlow()
    }
    @Volatile private var active = true
    private val thread = Thread({
        val user = User32.INSTANCE
        var revision = ""
        val registered = mutableSetOf<Int>()
        val message = WinUser.MSG()
        try {
            while (active) {
                val prefs = DesktopPreferences.store
                val next = prefs.changes.value.toString()
                if (next != revision) {
                    registered.forEach { user.UnregisterHotKey(null, it) }; registered.clear()
                    val errors = mutableMapOf<String, String>()
                    if (prefs.bool("hotkeysEnabled")) hotkeyActions.forEachIndexed { index, action ->
                        val combo = prefs.text("hotkey.${action.id}", action.default)
                        if (combo.isNotBlank()) runCatching {
                            val (mods, key) = parseHotkey(combo)
                            check(user.RegisterHotKey(null, index + 1, mods, key)) { "Shortcut is already used by Windows or another app." }
                            registered += index + 1
                        }.onFailure { errors[action.id] = it.message ?: "Couldn't register shortcut." }
                    }
                    mutableErrors.value = errors; revision = next
                }
                while (user.PeekMessage(message, null, 0, 0, 1)) {
                    if (message.message == 0x0312) hotkeyActions.getOrNull(message.wParam.toInt() - 1)?.let { action ->
                        when (action.id) {
                            "play_pause" -> playback?.togglePlayPause(); "next_track" -> playback?.next(); "prev_track" -> playback?.previous()
                            "seek_forward" -> playback?.let { it.seekTo(it.positionMs.value + 5000) }
                            "seek_backward" -> playback?.let { it.seekTo((it.positionMs.value - 5000).coerceAtLeast(0)) }
                            "shuffle" -> playback?.toggleShuffle(); "repeat" -> playback?.cycleRepeatMode()
                            "volume_up" -> playback?.let { it.setVolume((it.volume.value + .05f).coerceAtMost(1f)) }
                            "volume_down" -> playback?.let { it.setVolume((it.volume.value - .05f).coerceAtLeast(0f)) }
                            "mute" -> playback?.toggleMute()
                            "show_app" -> java.awt.EventQueue.invokeLater(show)
                        }
                    }
                }
                Thread.sleep(50)
            }
        } finally { registered.forEach { user.UnregisterHotKey(null, it) } }
    }, "auralis-global-hotkeys").apply { isDaemon = true; start() }
    override fun close() { active = false; thread.join(500) }
}
