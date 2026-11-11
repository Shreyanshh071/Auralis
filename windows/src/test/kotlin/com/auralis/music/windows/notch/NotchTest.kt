package com.auralis.music.windows.notch

import com.auralis.music.desktop.playback.VlcAudioPlayer.State
import com.auralis.music.util.KeyValueStore
import com.auralis.music.windows.settings.DesktopPreferencesStore
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class NotchTest {
    private fun event(action: String, track: String = "song", position: Any? = null): String = org.json.JSONObject()
        .put("version", 1).put("type", "command").put("action", action).put("trackId", track)
        .apply { if (position != null) put("positionMs", position) }.toString()

    @Test fun commandsAreRestrictedToAuralisAndTheCurrentTrack() {
        assertEquals(NotchCommand(NotchAction.PLAY_PAUSE), parseNotchCommand(event("playPause"), "song", 100_000))
        assertEquals(NotchCommand(NotchAction.NEXT), parseNotchCommand(event("next"), "song", 100_000))
        assertEquals(NotchCommand(NotchAction.PREVIOUS), parseNotchCommand(event("previous"), "song", 100_000))
        assertNull(parseNotchCommand(event("playPause", "old-song"), "song", 100_000))
        assertNull(parseNotchCommand(event("next"), null, 100_000))
        assertNull(parseNotchCommand(event("openURL"), "song", 100_000))
        assertNull(parseNotchCommand("not json", "song", 100_000))
        assertNull(parseNotchCommand("x".repeat(20_000), "song", 100_000))
        assertNull(parseNotchCommand(event("next").replace("\"version\":1", "\"version\":2"), "song", 100_000))
        assertEquals(NotchCommand(NotchAction.OPEN_SETTINGS), parseNotchCommand(event("openSettings", ""), null, 0))
        assertEquals(NotchCommand(NotchAction.OPEN_PLAYER), parseNotchCommand(event("openPlayer", ""), null, 0))
    }

    @Test fun seekCommandsClampToDurationAndRejectInvalidPositions() {
        assertEquals(NotchCommand(NotchAction.SEEK, 32_000), parseNotchCommand(event("seek", position = 32_000), "song", 100_000))
        assertEquals(NotchCommand(NotchAction.SEEK, 100_000), parseNotchCommand(event("seek", position = 999_999), "song", 100_000))
        assertNull(parseNotchCommand(event("seek", position = -1), "song", 100_000))
        assertNull(parseNotchCommand(event("seek", position = "NaN"), "song", 100_000))
        assertNull(parseNotchCommand(event("seek", position = "100"), "song", 100_000))
        assertNull(parseNotchCommand(event("seek"), "song", 100_000))
        assertNull(parseNotchCommand(event("seek", position = 10), "song", 0))
    }

    @Test fun nativeSwiftAndKotlinAgreeOnTheWireFormat() {
        val binary = nativeNotchExecutable() ?: return
        val state = NotchSnapshot(false, "song", "Auralis \"quoted\" 🎵", "Artist\n第二行", "", true, false,
            32_000, 100_000, true, true)
        val child = ProcessBuilder(binary.absolutePath, "--check-snapshot").start()
        try {
            child.outputStream.bufferedWriter().use { it.write(state.json()) }
            assertTrue(child.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, child.exitValue(), child.errorStream.bufferedReader().readText())
            val decoded = org.json.JSONObject(child.inputStream.bufferedReader().readText())
            assertEquals(state.title, decoded.getString("title"))
            assertEquals(state.artist, decoded.getString("artist"))
            assertEquals(state.trackId, decoded.getString("trackId"))
            assertEquals(state.positionMs, decoded.getLong("positionMs"))
            assertFalse(decoded.getBoolean("visible"))
        } finally { child.destroyForcibly() }
    }

    @Test fun nativeHelperValidatesPanelAndExitsWhenItsInputCloses() {
        val binary = nativeNotchExecutable() ?: return
        val check = ProcessBuilder(binary.absolutePath, "--self-test").redirectErrorStream(true).start()
        try {
            assertTrue(check.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, check.exitValue(), check.inputStream.bufferedReader().readText())
        } finally { check.destroyForcibly() }
        val child = ProcessBuilder(binary.absolutePath).start()
        try {
            val state = NotchSnapshot(false, "song", "Hidden lifecycle check", "Test", "", false, false, 0, 1000, false, false)
            child.outputStream.bufferedWriter().use { it.write(state.json()); it.newLine() }
            assertTrue(child.waitFor(5, TimeUnit.SECONDS), "The helper must exit on EOF, without showing a panel")
            assertEquals(0, child.exitValue(), child.errorStream.bufferedReader().readText())
        } finally { child.destroyForcibly() }
    }

    @Test fun nativeClientStartsHiddenAndDoesNotLeaveAnOrphan() {
        if (nativeNotchExecutable() == null) return
        fun children() = ProcessHandle.current().children().use { stream -> stream.toList().filter {
            it.info().command().orElse("").endsWith("/AuralisNotch")
        } }
        val existing = children().map { it.pid() }.toSet()
        val client = NativeNotchClient { error("A hidden helper must not emit player commands") }
        var child: ProcessHandle? = null
        try {
            assertTrue(client.update(NotchSnapshot(false, "", "", "", "", false, false, 0, 0, false, false)))
            assertEquals(existing, children().map { it.pid() }.toSet(), "Idle playback must not launch a helper")
            assertTrue(client.prepare())
            child = assertNotNull(children().firstOrNull { it.pid() !in existing })
        } finally { client.close() }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (child!!.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
        assertFalse(child.isAlive, "Closing Auralis must terminate its native notch child")
    }

    @Test fun pauseDeadlineResumeBufferingAndSessionEnd() {
        val session = NotchSession()
        fun tick(state: State, now: Long, resolving: Boolean = false) =
            session.update(true, false, true, true, state, resolving, now)
        assertFalse(tick(State.PAUSED, 0), "A restored paused queue must not take over the notch")
        assertTrue(tick(State.PLAYING, 10))
        assertTrue(tick(State.PAUSED, 100))
        assertTrue(tick(State.PAUSED, 10_099))
        assertFalse(tick(State.PAUSED, 10_100))
        assertTrue(tick(State.PLAYING, 11_000))
        assertTrue(tick(State.PAUSED, 12_000))
        assertTrue(tick(State.BUFFERING, 15_000))
        assertTrue(tick(State.PAUSED, 30_000), "Buffering resets the pause timer")
        assertTrue(tick(State.IDLE, 31_000, resolving = true), "Track resolution keeps ownership")
        assertFalse(tick(State.ENDED, 32_000))
        assertFalse(tick(State.ERROR, 33_000))
    }

    @Test fun lockDisableQueueClearAndRemotePlaybackCannotShowTheNotch() {
        val session = NotchSession()
        assertTrue(session.update(true, false, true, true, State.PLAYING, false, 0))
        assertFalse(session.update(true, true, true, true, State.PLAYING, false, 1))
        assertTrue(session.update(true, false, true, true, State.PLAYING, false, 2))
        assertFalse(session.update(true, false, true, false, State.PLAYING, false, 3))
        assertFalse(session.update(false, false, true, true, State.PLAYING, false, 4))
        assertFalse(session.update(true, false, false, true, State.PLAYING, false, 5))
    }

    @Test fun refusedHideNeverAllowsDuplicationOrRepeatedlyDisturbsThePeer() {
        val handoff = NotchHandoff()
        var hides = 0
        var restores = 0
        repeat(8) { index ->
            assertFalse(handoff.update(true, listOf(NotchPeer(771, false, true)), index * 250L,
                hide = { hides++; false }, restore = { restores++ }))
        }
        assertEquals(1, hides)
        assertEquals(0, restores)
        assertTrue(handoff.update(true, emptyList(), 3000, { false }, { restores++ }),
            "The notch becomes available when Boring Notch quits")
    }

    @Test fun successfulHideMustBeConfirmedAndIsRestoredExactlyOnce() {
        val handoff = NotchHandoff()
        val restored = mutableListOf<Int>()
        assertFalse(handoff.update(true, listOf(NotchPeer(1, false)), 0, { true }, restored::add))
        assertTrue(handoff.update(true, listOf(NotchPeer(1, true)), 250, { error("Already hiding") }, restored::add))
        assertFalse(handoff.update(false, listOf(NotchPeer(1, true)), 500, { false }, restored::add))
        handoff.release(restored::add)
        assertEquals(listOf(1), restored)
    }

    @Test fun hiddenFlagAloneDoesNotProveTheNotchWindowDisappeared() {
        val handoff = NotchHandoff()
        assertFalse(handoff.update(true, listOf(NotchPeer(1, true, visibleWindows = true)), 0, { false }, {}))
    }

    @Test fun preexistingHiddenAppsAndQuitAppsAreNeverRestored() {
        val handoff = NotchHandoff()
        val restored = mutableListOf<Int>()
        assertTrue(handoff.update(true, listOf(NotchPeer(1, true)), 0, { false }, restored::add))
        handoff.release(restored::add)
        assertTrue(restored.isEmpty())
        handoff.update(true, listOf(NotchPeer(2, false)), 10, { true }, restored::add)
        handoff.update(true, listOf(NotchPeer(2, true)), 20, { false }, restored::add)
        handoff.update(false, emptyList(), 30, { false }, restored::add)
        assertTrue(restored.isEmpty())
    }

    @Test fun oneRefusingInstanceReleasesAnyOtherInstanceWeHid() {
        val handoff = NotchHandoff()
        val restored = mutableListOf<Int>()
        assertFalse(handoff.update(true, listOf(NotchPeer(1, false), NotchPeer(2, false)), 0,
            hide = { it == 1 }, restore = restored::add))
        assertEquals(listOf(1), restored)
        assertFalse(handoff.update(true, listOf(NotchPeer(1, false), NotchPeer(2, false)), 250,
            hide = { error("Do not disturb apps while handoff is blocked") }, restore = restored::add))
    }

    @Test fun delayedOrCancelledHideIsUndoneAndManualUnhideIsRespected() {
        val handoff = NotchHandoff()
        val restored = mutableListOf<Int>()
        handoff.update(true, listOf(NotchPeer(1, false)), 0, { true }, restored::add)
        assertFalse(handoff.update(true, listOf(NotchPeer(1, false)), 1000, { true }, restored::add))
        assertEquals(listOf(1), restored)
        handoff.update(true, emptyList(), 1100, { true }, restored::add)
        handoff.update(true, listOf(NotchPeer(2, false)), 1200, { true }, restored::add)
        handoff.update(false, listOf(NotchPeer(2, false)), 1201, { true }, restored::add)
        assertEquals(listOf(1, 2), restored)
        handoff.update(true, listOf(NotchPeer(3, false)), 2000, { true }, restored::add)
        handoff.update(true, listOf(NotchPeer(3, true)), 2250, { true }, restored::add)
        assertFalse(handoff.update(true, listOf(NotchPeer(3, false)), 2500, { error("User revealed it") }, restored::add))
    }



    @Test fun notchSettingPersistsWithoutChangingOtherPreferences() {
        val disk = object : KeyValueStore {
            val values = mutableMapOf<String, String>()
            override fun getString(key: String, default: String) = values[key] ?: default
            override fun putStrings(values: Map<String, String>) { this.values += values }
            override fun clear() = values.clear()
        }
        val store = DesktopPreferencesStore(disk)
        store.set("macLockScreenOverlay", true)
        assertTrue(NotchPreferences(store).enabled)
        NotchPreferences(store).enabled = false
        assertFalse(NotchPreferences(DesktopPreferencesStore(disk)).enabled)
        assertTrue(store.bool("macLockScreenOverlay"))
    }



    @Test fun publicMacBridgeReadsScreenGeometryWithoutChangingOtherApps() {
        if (!System.getProperty("os.name").orEmpty().lowercase().contains("mac") || java.awt.GraphicsEnvironment.isHeadless()) return
        javax.swing.SwingUtilities.invokeAndWait { java.awt.Toolkit.getDefaultToolkit() }
        val bridge = assertNotNull(MacNotchBridge.create())
        try {
            val environment = runBlocking { withTimeout(5000) { bridge.update(false, 0) } }
            assertFalse(environment.allowed)
            val display = assertNotNull(environment.display)
            assertTrue(display.width > 0 && display.height > 0)
            assertTrue(display.topInset >= 0 && display.cutoutWidth >= 0)
        } finally { bridge.close() }
    }




}
