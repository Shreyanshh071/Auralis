package com.auralis.music.windows.immersive

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.windows.settings.DesktopPreferences
import com.auralis.music.windows.settings.DesktopPreferencesStore
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.event.KeyEvent.VK_1
import java.awt.event.KeyEvent.VK_9
import java.awt.event.KeyEvent.VK_ESCAPE
import java.awt.event.KeyEvent.VK_I
import java.awt.event.KeyEvent.VK_K
import java.awt.event.KeyEvent.VK_LEFT
import java.awt.event.KeyEvent.VK_M
import java.awt.event.KeyEvent.VK_RIGHT
import java.awt.event.KeyEvent.VK_SPACE
import java.awt.event.KeyEvent.VK_V
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.chrono.IsoChronology
import java.util.Locale

/**
 * Immersive mode: a full-screen clock with the current song on each display.
 *
 * - **Manual** (the I key, or the full player's button): the windows take focus so the keyboard
 *   works. Esc steps back to the player, and switching to another app leaves.
 * - **Idle**, like a screensaver: after the chosen time without input while music plays. The
 *   windows never take focus, and any input at all leaves.
 */
internal enum class ImmersiveTrigger { MANUAL, IDLE }

internal enum class ImmersiveView(val label: String) { CLOCK("Clock"), LYRICS("Lyrics") }

@Stable
internal class ImmersiveSession {
    var trigger by mutableStateOf<ImmersiveTrigger?>(null)
        private set
    var openedAtMs = 0L
        private set

    /** Asking for it by hand while the idle version shows turns it into the manual one. */
    fun open(requested: ImmersiveTrigger, nowMs: Long = System.currentTimeMillis()) {
        val current = trigger
        if (current == null) openedAtMs = nowMs
        if (current == null || (current == ImmersiveTrigger.IDLE && requested == ImmersiveTrigger.MANUAL)) trigger = requested
    }

    fun close() { trigger = null }
}

/** One connected display, in the window coordinates AWT places windows in. */
internal data class ImmersiveDisplay(val key: String, val bounds: Rectangle)

/** The display Auralis is on first (that's where the user looks), then the rest left to right. */
internal fun orderDisplays(displays: List<ImmersiveDisplay>, appBounds: Rectangle?): List<ImmersiveDisplay> {
    val leftToRight = displays.sortedBy { it.bounds.x }
    val center = appBounds?.let { java.awt.Point(it.centerX.toInt(), it.centerY.toInt()) }
    val home = leftToRight.firstOrNull { center != null && it.bounds.contains(center) } ?: return leftToRight
    return listOf(home) + (leftToRight - home)
}

internal fun connectedDisplays(appBounds: Rectangle?): List<ImmersiveDisplay> =
    orderDisplays(GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
        val bounds = device.defaultConfiguration.bounds
        ImmersiveDisplay("${bounds.x},${bounds.y}", Rectangle(bounds))
    }, appBounds)

/** What each display shows, remembered per display; the first shows the clock, with lyrics on additional displays. */
internal class ImmersivePreferences(private val prefs: DesktopPreferencesStore = DesktopPreferences.store) {
    fun view(display: ImmersiveDisplay, index: Int): ImmersiveView =
        when (val saved = prefs.text("immersiveView.${display.key}")) {
            "ARTWORK" -> ImmersiveView.CLOCK
            else -> ImmersiveView.entries.firstOrNull { it.name == saved } ?: defaultView(index)
        }

    fun setView(display: ImmersiveDisplay, view: ImmersiveView) = prefs.set("immersiveView.${display.key}", view.name)

    fun isOff(display: ImmersiveDisplay) = prefs.bool("immersiveOff.${display.key}")

    fun setOff(display: ImmersiveDisplay, off: Boolean) = prefs.set("immersiveOff.${display.key}", off)

    /** Never nothing at all: with every display turned off, the first one still shows it. */
    fun shown(displays: List<ImmersiveDisplay>): List<ImmersiveDisplay> =
        displays.filterNot(::isOff).ifEmpty { displays.take(1) }

    var idleMinutes: Int
        get() = prefs.number("immersiveIdleMinutes", 0.0).toInt()
        set(value) = prefs.set("immersiveIdleMinutes", value)
}

internal fun defaultView(index: Int) = when (index) {
    0 -> ImmersiveView.CLOCK
    1 -> ImmersiveView.LYRICS
    else -> ImmersiveView.LYRICS
}

internal val ImmersiveIdleChoices = listOf(0, 2, 5, 10)

/** Input this soon after an idle start is the tail of what was already happening. */
internal const val IDLE_EXIT_GRACE_MS = 1_500L

internal fun shouldStartIdle(minutes: Int, playing: Boolean, idleMs: Long?, windowsAcceptsInterruptions: Boolean) =
    minutes > 0 && playing && windowsAcceptsInterruptions && idleMs != null && idleMs >= minutes * 60_000L

internal fun shouldLeaveIdle(openForMs: Long, idleMs: Long?) =
    openForMs > IDLE_EXIT_GRACE_MS && idleMs != null && idleMs < 1_000L

/** The keys immersive mode answers. */
internal interface ImmersiveKeyActions {
    fun togglePlayPause()
    fun next()
    fun previous()
    fun seekBy(deltaMs: Long)
    fun toggleMute()
    fun nextView()
    /** Number keys match the display chips: 1 is the first display. */
    fun toggleDisplay(index: Int)
    fun leaveToPlayer()
    fun leave()
}

/**
 * | Keys | Action |
 * |---|---|
 * | Esc | Back to the player |
 * | I | Leave |
 * | V | Next view on this display |
 * | 1 to 9 | Show or hide on that display |
 * | Space / K | Play / pause |
 * | Ctrl+→ / Ctrl+← | Next / previous song |
 * | → / ← | Forward / back 5 s (Shift: 15 s) |
 * | M | Mute / unmute |
 */
internal fun handleImmersiveKey(code: Int, ctrl: Boolean, shift: Boolean, actions: ImmersiveKeyActions): Boolean {
    if (ctrl) {
        when (code) {
            VK_RIGHT -> actions.next()
            VK_LEFT -> actions.previous()
            else -> return false
        }
        return true
    }
    val step = if (shift) 15_000L else 5_000L
    when (code) {
        VK_ESCAPE -> actions.leaveToPlayer()
        VK_I -> actions.leave()
        VK_V -> actions.nextView()
        VK_SPACE, VK_K -> actions.togglePlayPause()
        VK_RIGHT -> actions.seekBy(step)
        VK_LEFT -> actions.seekBy(-step)
        VK_M -> actions.toggleMute()
        in VK_1..VK_9 -> actions.toggleDisplay(code - VK_1)
        else -> return false
    }
    return true
}

/** "9:41" in the locale's 12- or 24-hour style, without AM/PM, as on a lock screen. */
internal fun clockText(now: LocalDateTime, locale: Locale = Locale.getDefault()): String {
    val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, FormatStyle.SHORT, IsoChronology.INSTANCE, locale)
    val hour = if (pattern.any { it == 'H' || it == 'k' }) now.hour else (now.hour % 12).let { if (it == 0) 12 else it }
    return "%d:%02d".format(hour, now.minute)
}

internal fun dateText(now: LocalDateTime, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("EEEE, d MMMM", locale).format(now)

/** The line being sung, for the clock when no other display shows lyrics. Null between lines. */
internal fun currentLyricLine(lines: List<LyricLine>, positionMs: Long): LyricLine? {
    // Unsynced lyrics have no times to follow.
    if (lines.none { it.time > 0 }) return null
    val line = lines.lastOrNull { !it.isBackground && it.time <= positionMs } ?: return null
    if (line.isInstrumental || line.text.isBlank()) return null
    return line.takeIf { (it.effectiveEndTime ?: Long.MAX_VALUE) >= positionMs }
}
