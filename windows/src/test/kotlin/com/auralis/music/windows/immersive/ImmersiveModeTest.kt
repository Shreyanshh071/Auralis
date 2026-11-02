package com.auralis.music.windows.immersive

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.util.KeyValueStore
import com.auralis.music.windows.settings.DesktopPreferencesStore
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.time.LocalDateTime
import java.util.Locale
import kotlin.test.*

class ImmersiveModeTest {
    private val first = ImmersiveDisplay("0,0", Rectangle(0, 0, 1920, 1080))
    private val second = ImmersiveDisplay("1920,0", Rectangle(1920, 0, 2560, 1440))
    private val leftOfMain = ImmersiveDisplay("-1080,0", Rectangle(-1080, 0, 1080, 1920))

    @Test fun manualRequestTakesOverAnIdleSessionButNotTheOtherWayRound() {
        val session = ImmersiveSession()
        session.open(ImmersiveTrigger.IDLE, nowMs = 10)
        session.open(ImmersiveTrigger.MANUAL, nowMs = 20)
        assertEquals(ImmersiveTrigger.MANUAL, session.trigger)
        assertEquals(10, session.openedAtMs)
        session.open(ImmersiveTrigger.IDLE, nowMs = 30)
        assertEquals(ImmersiveTrigger.MANUAL, session.trigger)
        session.close()
        assertNull(session.trigger)
    }

    @Test fun idleStartNeedsAllItsConditions() {
        val five = 5 * 60_000L
        assertTrue(shouldStartIdle(5, playing = true, idleMs = five, windowsAcceptsInterruptions = true))
        assertFalse(shouldStartIdle(0, true, five, true), "Off by default")
        assertFalse(shouldStartIdle(5, false, five, true), "Only while music plays")
        assertFalse(shouldStartIdle(5, true, five - 1, true), "Not before the time is up")
        assertFalse(shouldStartIdle(5, true, null, true), "Unknown idle time never starts it")
        assertFalse(shouldStartIdle(5, true, five, false), "Never over a fullscreen game")
    }

    @Test fun idleSessionIgnoresInputRightAfterStarting() {
        assertFalse(shouldLeaveIdle(IDLE_EXIT_GRACE_MS, 0))
        assertTrue(shouldLeaveIdle(IDLE_EXIT_GRACE_MS + 1, 0))
        assertFalse(shouldLeaveIdle(60_000, 5_000))
    }

    @Test fun displaysStartWithTheOneAuralisIsOnThenLeftToRight() {
        assertEquals(listOf(second, leftOfMain, first), orderDisplays(listOf(first, second, leftOfMain), Rectangle(2000, 100, 800, 600)))
        assertEquals(listOf(leftOfMain, first, second), orderDisplays(listOf(first, second, leftOfMain), null))
    }

    @Test fun viewsAreRememberedPerDisplayAndOneDisplayAlwaysShows() {
        val preferences = ImmersivePreferences(store())
        assertEquals(ImmersiveView.CLOCK, preferences.view(first, 0))
        assertEquals(ImmersiveView.LYRICS, preferences.view(second, 1))
        assertEquals(ImmersiveView.ARTWORK, preferences.view(leftOfMain, 2))
        preferences.setView(second, ImmersiveView.CLOCK)
        assertEquals(ImmersiveView.CLOCK, preferences.view(second, 1))
        preferences.setOff(second, true)
        assertEquals(listOf(first), preferences.shown(listOf(first, second)))
        preferences.setOff(first, true)
        assertEquals(listOf(first), preferences.shown(listOf(first, second)))
    }

    @Test fun keysDoWhatTheyPromise() {
        val calls = mutableListOf<String>()
        val actions = object : ImmersiveKeyActions {
            override fun togglePlayPause() { calls += "toggle" }
            override fun next() { calls += "next" }
            override fun previous() { calls += "previous" }
            override fun seekBy(deltaMs: Long) { calls += "seek $deltaMs" }
            override fun toggleMute() { calls += "mute" }
            override fun nextView() { calls += "view" }
            override fun toggleDisplay(index: Int) { calls += "display $index" }
            override fun leaveToPlayer() { calls += "player" }
            override fun leave() { calls += "leave" }
        }
        fun press(code: Int, ctrl: Boolean = false, shift: Boolean = false) = handleImmersiveKey(code, ctrl, shift, actions)
        press(KeyEvent.VK_ESCAPE); press(KeyEvent.VK_I); press(KeyEvent.VK_V); press(KeyEvent.VK_SPACE)
        press(KeyEvent.VK_RIGHT, shift = true); press(KeyEvent.VK_LEFT, ctrl = true); press(KeyEvent.VK_3)
        assertEquals(listOf("player", "leave", "view", "toggle", "seek 15000", "previous", "display 2"), calls)
        assertFalse(press(KeyEvent.VK_F, ctrl = true), "App shortcuts stay out of immersive mode")
        assertFalse(press(KeyEvent.VK_0))
    }

    @Test fun clockFollowsTheLocaleWithoutAmPm() {
        val evening = LocalDateTime.of(2026, 11, 2, 21, 5)
        assertEquals("9:05", clockText(evening, Locale.US))
        assertEquals("21:05", clockText(evening, Locale.GERMANY))
        assertEquals("12:00", clockText(LocalDateTime.of(2026, 11, 2, 0, 0), Locale.US))
        assertEquals("Monday, 2 November", dateText(evening, Locale.UK))
    }

    @Test fun currentLineFollowsTimingAndStaysEmptyBetweenLines() {
        val lines = listOf(LyricLine(1_000, "One", endTime = 2_000), LyricLine(1_200, "(echo)", isBackground = true),
            LyricLine(3_000, "Two"), LyricLine(5_000, "♪", isInstrumental = true))
        assertNull(currentLyricLine(lines, 500))
        assertEquals("One", currentLyricLine(lines, 1_500)?.text)
        assertNull(currentLyricLine(lines, 2_500), "Nothing shows in the gap")
        assertEquals("Two", currentLyricLine(lines, 4_000)?.text)
        assertNull(currentLyricLine(lines, 6_000))
        assertNull(currentLyricLine(listOf(LyricLine(0, "Plain"), LyricLine(0, "Text")), 1_000), "Unsynced lyrics have no current line")
    }

    @Test fun tickCounterWrapKeepsIdleTimeRight() {
        assertEquals(100L, elapsedTicks(200, 100))
        assertEquals(100L, elapsedTicks(Int.MIN_VALUE + 49, Int.MAX_VALUE - 50))
    }

    @Test fun displayShapePicksTheLayout() {
        assertEquals(ImmersiveShape.STANDARD, immersiveShape(1920f, 1080f))
        assertEquals(ImmersiveShape.PORTRAIT, immersiveShape(1080f, 1920f))
        assertEquals(ImmersiveShape.ULTRAWIDE, immersiveShape(3440f, 1440f))
    }

    private fun store() = DesktopPreferencesStore(object : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun getString(key: String, default: String) = values[key] ?: default
        override fun putStrings(values: Map<String, String>) { this.values += values }
        override fun clear() = values.clear()
    })
}
