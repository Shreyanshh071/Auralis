package com.auralis.music.data.connect

import com.auralis.music.domain.model.RepeatMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Three (or more) clients on one account, run against [FakeServer] in virtual time. Covers the
 * Connect scenarios: silent mirrors, remote controls, handoffs, failures, reconnects, sign-out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectEngineTest {
    @Test fun hiddenIdleDeviceStaysOfflineUntilItReturns() = runTest {
        val (w, phone, _) = threeDevices()
        w.run(2_000)
        phone.engine.setDeviceAvailable(false)
        w.run(1_000)
        assertFalse(assertNotNull(w.server.devices["phone-1"]).online)
        w.run(12_000)
        assertFalse(assertNotNull(w.server.devices["phone-1"]).online)
        phone.engine.setDeviceAvailable(true)
        w.run(1_000)
        assertTrue(assertNotNull(w.server.devices["phone-1"]).online)
    }

    @Test fun advancingPlaybackSavesRegularAccountBookmarks() = runTest {
        val (w, phone, _) = threeDevices()
        phone.user(ConnectIntent.Load(songs(3), 0))
        w.run(3_000)
        val before = assertNotNull(w.server.snapshot().session).positionMs
        w.run(7_000)
        val after = assertNotNull(w.server.snapshot().session).positionMs
        assertTrue(after >= before + 4_000L, "Playback must checkpoint without a manual control: $before -> $after")
    }

    @Test fun handoffStartsAtTheFrozenSourcePosition() = runTest {
        val (w, phone, others) = threeDevices()
        phone.user(ConnectIntent.Load(songs(3), 0))
        w.run(10_000)
        phone.user(ConnectIntent.Seek(42_000))
        w.run(500)
        phone.engine.transferTo(others.first.engine.me.id)
        w.run(12_000)
        val loaded = assertNotNull(others.first.player.lastLoadedPositionMs)
        assertTrue(loaded in 42_000..44_000, "Network handoff time must not be added to frozen position: $loaded")
        assertFalse(phone.player.playing)
        assertTrue(others.first.player.playing)
        assertTrue(w.doubleAudio.isEmpty())
    }

    @Test fun reopeningPreviousOutputRestoresItsAccountBookmarkSilently() = runTest {
        val (w, phone, _) = threeDevices()
        phone.user(ConnectIntent.Load(songs(3), 1))
        w.run(5_000)
        phone.user(ConnectIntent.Seek(67_000))
        w.run(500)
        phone.engine.stop(signOut = false)
        w.run(1_000)
        val bookmark = assertNotNull(w.server.snapshot().session)
        val reopened = w.device("phone-1", "Pixel 8", ConnectPlatform.ANDROID, ConnectDeviceType.PHONE)
        reopened.start()
        w.run(3_000)
        assertEquals(bookmark.track?.id, reopened.player.track?.id)
        assertEquals(bookmark.positionMs, reopened.player.positionMs)
        assertFalse(reopened.player.playing)
        assertEquals(bookmark.track?.id, w.server.snapshot().session?.track?.id)
    }

    private class World(val test: TestScope) {
        val server = FakeServer { test.testScheduler.currentTime }
        val devices = mutableListOf<SimDevice>()
        val doubleAudio = mutableListOf<Long>()

        fun device(id: String, name: String, platform: ConnectPlatform, type: ConnectDeviceType, skew: Long = 0L, latency: Long = 100L) =
            SimDevice(server, test.backgroundScope, test, id, name, platform, type, skew, latency).also { devices += it }

        fun watchDoubleAudio() {
            test.backgroundScope.launch {
                while (true) {
                    delay(20)
                    if (devices.count { !it.offline && it.player.playing } > 1) doubleAudio += test.testScheduler.currentTime
                }
            }
        }

        fun run(ms: Long) { test.advanceTimeBy(ms); test.runCurrent() }
    }

    private fun TestScope.threeDevices(): Triple<World, SimDevice, Pair<SimDevice, SimDevice>> {
        val w = World(this)
        val phone = w.device("phone-1", "Pixel 8", ConnectPlatform.ANDROID, ConnectDeviceType.PHONE, skew = 0)
        // A laptop whose clock runs 4 s fast and which only sees changes by polling (2 s).
        val laptop = w.device("laptop-1", "SHREY-LAPTOP", ConnectPlatform.WINDOWS, ConnectDeviceType.COMPUTER, skew = 4_000, latency = 2_000)
        val desktop = w.device("desktop-1", "STUDIO-PC", ConnectPlatform.WINDOWS, ConnectDeviceType.COMPUTER, skew = -2_500, latency = 1_500)
        w.devices.forEach { it.start() }
        w.watchDoubleAudio()
        w.run(3_000)
        return Triple(w, phone, laptop to desktop)
    }

    @Test
    fun handoffRetriesAReadyAcknowledgementAfterTransientFailure() = runTest {
        val (w, phone, others) = threeDevices()
        val laptop = others.first
        laptop.user(ConnectIntent.Load(songs(5), 0))
        w.run(5_000)
        phone.rejectNextReady = true
        // Asked from a third device, so the phone has to acknowledge before the laptop lets go.
        others.second.engine.transferTo(phone.engine.me.id)
        w.run(15_000)
        assertTrue(phone.log.any { it.contains("Transient acknowledgement failure") })
        assertTrue(phone.engine.isOutput)
        assertTrue(phone.player.playing)
        assertFalse(laptop.player.playing)
        assertNull(laptop.engine.ui.value.transfer)
        assertTrue(w.doubleAudio.isEmpty())
        assertTrue(w.server.violations.isEmpty())
    }

    @Test
    fun theOutputHandsOverInOneStepAndAPickedDeviceIsReadyAtOnce() = runTest {
        val (w, phone, others) = threeDevices()
        val laptop = others.first
        laptop.user(ConnectIntent.Load(songs(5), 0))
        w.run(5_000)
        // The laptop moves its own audio: no "ready" round trip, the phone starts straight away.
        laptop.engine.transferTo(phone.engine.me.id)
        w.run(2_500)
        assertTrue(phone.player.playing, "the phone plays within one round trip")
        assertFalse(laptop.player.playing)
        assertNull(w.server.snapshot().session?.transfer?.takeIf { it.status == TransferStatus.READY })
        // The laptop asks for the audio back: its request is already its "ready".
        laptop.engine.transferTo(laptop.engine.me.id)
        w.run(4_000)
        assertTrue(laptop.player.playing, "the laptop (2 s polling) has the audio back within one poll or two")
        assertFalse(phone.player.playing)
        assertTrue(w.doubleAudio.isEmpty())
        assertTrue(w.server.violations.isEmpty())
    }

    @Test
    fun pickingADeviceShowsConnectingAtOnce() = runTest {
        val (w, phone, others) = threeDevices()
        val laptop = others.first
        laptop.user(ConnectIntent.Load(songs(5), 0))
        w.run(5_000)
        // The laptop's writes take 2 s here: the picker must not wait for them to react.
        laptop.engine.transferTo(phone.engine.me.id)
        val ui = laptop.engine.ui.value
        assertEquals(phone.engine.me.id, ui.transfer?.deviceId)
        assertEquals(ConnectRowState.CONNECTING, ui.devices.first { it.device.id == phone.engine.me.id }.state)
        w.run(15_000)
        assertTrue(phone.engine.isOutput)
        assertNull(laptop.engine.ui.value.transfer)
        assertTrue(w.doubleAudio.isEmpty())
    }

    @Test
    fun androidPlaysAndOtherClientsMirrorSilently() = runTest {
        val (w, phone, others) = threeDevices()
        val (laptop, desktop) = others
        phone.user(ConnectIntent.Load(songs(5), 0))
        w.run(61_000)
        assertTrue(phone.player.playing)
        for (d in listOf(laptop, desktop)) {
            val remote = assertNotNull(d.engine.ui.value.remote, "${d.engine.me.name} shows the session")
            assertEquals("song1", remote.track.id)
            assertTrue(remote.playing)
            assertFalse(d.player.playing, "mirrors stay silent")
            // The seek bar keeps moving between updates and agrees with the phone within a second
            // despite the laptop's clock being 4 s off.
            val shown = remote.positionAt(d.localNow())
            assertTrue(kotlin.math.abs(shown - phone.player.positionMs) < 1_000, "shown $shown vs ${phone.player.positionMs}")
            assertEquals("phone-1", remote.output.deviceId)
        }
        val before = laptop.engine.ui.value.remote!!.positionAt(laptop.localNow())
        w.run(2_000)
        assertTrue(laptop.engine.ui.value.remote!!.positionAt(laptop.localNow()) > before)
        assertTrue(w.doubleAudio.isEmpty())
        assertTrue(w.server.violations.isEmpty(), w.server.violations.toString())
    }

    @Test
    fun deviceListShowsDistinctNamesAndTheOutput() = runTest {
        val (w, phone, others) = threeDevices()
        phone.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        val rows = others.first.engine.ui.value.devices
        assertEquals(3, rows.size)
        assertEquals(setOf("Pixel 8", "SHREY-LAPTOP", "STUDIO-PC"), rows.map { it.label }.toSet())
        assertTrue(rows.first().isThis)
        assertEquals("phone-1", rows.single { it.isOutput }.device.id)
        assertTrue(phone.engine.ui.value.devices.first { it.isThis }.isOutput)
    }

    @Test
    fun controlsFromAnyClientReachTheOutput() = runTest {
        val (w, phone, others) = threeDevices()
        val (laptop, desktop) = others
        phone.user(ConnectIntent.Load(songs(5), 0))
        w.run(10_000)

        laptop.user(ConnectIntent.Pause)
        assertFalse(laptop.engine.ui.value.remote!!.playing, "the button flips at once")
        w.run(4_000)
        assertFalse(phone.player.playing)
        val paused = phone.player.positionMs
        w.run(3_000)
        for (d in listOf(laptop, desktop)) assertEquals(paused, d.engine.ui.value.remote!!.positionAt(d.localNow()), "settles at the paused position")

        desktop.user(ConnectIntent.Seek(90_000))
        w.run(4_000)
        assertEquals(90_000, phone.player.positionMs)

        desktop.user(ConnectIntent.Play)
        w.run(4_000)
        assertTrue(phone.player.playing)

        laptop.user(ConnectIntent.Next)
        w.run(4_000)
        assertEquals("song2", phone.player.track?.id)
        w.run(3_000)
        assertEquals("song2", desktop.engine.ui.value.remote!!.track.id)

        desktop.user(ConnectIntent.PlayIndex(3, "song4"))
        w.run(4_000)
        assertEquals("song4", phone.player.track?.id)
        assertFalse(laptop.player.playing || desktop.player.playing)
        assertTrue(w.server.violations.isEmpty(), w.server.violations.toString())
    }

    @Test
    fun handoffMovesAudioWithoutOverlap() = runTest {
        val (w, phone, others) = threeDevices()
        val (laptop, desktop) = others
        phone.user(ConnectIntent.Load(songs(5), 1))
        phone.user(ConnectIntent.Repeat(RepeatMode.ALL))
        w.run(61_000)
        val atRequest = phone.player.positionMs

        // The desktop asks to move the phone's audio to the laptop.
        desktop.engine.transferTo("laptop-1")
        w.run(1_000)
        assertEquals("laptop-1", desktop.engine.ui.value.transfer?.deviceId, "connecting is shown")
        assertEquals("phone-1", desktop.engine.ui.value.output?.deviceId, "no success before the ack")
        w.run(12_000)

        assertTrue(laptop.player.playing)
        assertFalse(phone.player.playing)
        assertEquals("song2", laptop.player.track?.id)
        assertEquals(5, laptop.player.queue.size)
        assertEquals(RepeatMode.ALL, laptop.player.repeatMode)
        // Continues near where the phone was, not from the start.
        assertTrue(laptop.player.positionMs > atRequest, "${laptop.player.positionMs} vs $atRequest")
        assertTrue(laptop.player.positionMs < atRequest + 20_000)
        for (d in w.devices) assertEquals("laptop-1", d.engine.ui.value.output?.deviceId)
        assertNull(phone.engine.ui.value.transfer)
        assertNotNull(phone.engine.ui.value.remote)
        assertTrue(w.doubleAudio.isEmpty(), "two devices sounded at ${w.doubleAudio}")
        assertTrue(w.server.violations.isEmpty(), w.server.violations.toString())

        // And on to the desktop, picked on the desktop itself.
        desktop.engine.transferTo("desktop-1")
        w.run(12_000)
        assertTrue(desktop.player.playing)
        assertFalse(laptop.player.playing)
        assertEquals("song2", desktop.player.track?.id)
        assertTrue(w.doubleAudio.isEmpty(), "two devices sounded at ${w.doubleAudio}")
    }

    @Test
    fun pausedHandoffStaysPaused() = runTest {
        val (w, phone, others) = threeDevices()
        val (laptop, _) = others
        phone.user(ConnectIntent.Load(songs(3), 0))
        w.run(30_000)
        phone.user(ConnectIntent.Pause)
        val pausedAt = phone.player.positionMs
        w.run(3_000)
        phone.engine.transferTo("laptop-1")
        w.run(12_000)
        assertEquals("laptop-1", phone.engine.ui.value.output?.deviceId)
        assertFalse(laptop.player.playing)
        assertFalse(phone.player.playing)
        assertEquals("song1", laptop.player.track?.id)
        assertEquals(pausedAt, laptop.player.positionMs)
    }

    @Test
    fun queueShuffleAndRepeatStayConsistent() = runTest {
        val (w, phone, others) = threeDevices()
        val (laptop, desktop) = others
        phone.user(ConnectIntent.Load(songs(4), 0))
        w.run(5_000)
        laptop.user(ConnectIntent.Shuffle(true))
        desktop.user(ConnectIntent.Repeat(RepeatMode.ONE))
        laptop.user(ConnectIntent.Add(songs(1, "extra").single()))
        w.run(6_000)
        assertTrue(phone.player.shuffled)
        assertEquals(RepeatMode.ONE, phone.player.repeatMode)
        assertEquals("extra1", phone.player.queue.last().id)
        for (d in listOf(laptop, desktop)) {
            val r = d.engine.ui.value.remote!!
            assertTrue(r.shuffle)
            assertEquals(RepeatMode.ONE, r.repeat)
            assertEquals(phone.player.queue.map { it.id }, r.queue.map { it.id })
        }
        desktop.user(ConnectIntent.Remove(1, "song2"))
        w.run(5_000)
        assertEquals(listOf("song1", "song3", "song4", "extra1"), phone.player.queue.map { it.id })
        laptop.engine.transferTo("laptop-1")
        w.run(12_000)
        assertEquals(listOf("song1", "song3", "song4", "extra1"), laptop.player.queue.map { it.id })
        assertTrue(laptop.player.shuffled)
        assertEquals(RepeatMode.ONE, laptop.player.repeatMode)
    }

    @Test
    fun openingAnotherClientNeverStealsPlayback() = runTest {
        val (w, phone, _) = threeDevices()
        phone.user(ConnectIntent.Load(songs(3), 0))
        w.run(10_000)
        val late = w.device("tablet-1", "", ConnectPlatform.ANDROID, ConnectDeviceType.TABLET)
        // It had its own song paused from before; opening the app must not play it or take over.
        late.player.load(songs(1, "old"), 0, 5_000, false, false, RepeatMode.OFF, null)
        late.start()
        w.run(10_000)
        assertTrue(phone.player.playing)
        assertFalse(late.player.playing)
        assertEquals("phone-1", late.engine.ui.value.output?.deviceId)
        assertEquals("song1", late.engine.ui.value.remote?.track?.id)
        assertTrue(late.engine.ui.value.devices.first().isThis)
        assertEquals("Android tablet", late.engine.ui.value.devices.first().label)
    }

    @Test
    fun failedHandoffKeepsThePreviousOutput() = runTest {
        val (w, phone, others) = threeDevices()
        val (laptop, desktop) = others
        phone.user(ConnectIntent.Load(songs(3), 0))
        w.run(10_000)
        laptop.player.playable = false
        desktop.engine.transferTo("laptop-1")
        w.run(10_000)
        assertTrue(phone.player.playing, "the phone keeps playing")
        assertFalse(laptop.player.playing)
        assertEquals("phone-1", desktop.engine.ui.value.output?.deviceId)
        assertTrue(desktop.engine.ui.value.transfer?.failed == true || w.server.snapshot().session?.transfer?.status == TransferStatus.FAILED)

        // The output hands over directly to a target that never answers (closed, asleep): its short
        // lease lapses and the audio comes back to the phone, which plays again.
        desktop.offline = true
        phone.engine.transferTo("desktop-1")
        w.run(1_000)
        assertFalse(phone.player.playing, "the phone stops at once for the handoff")
        w.run(20_000)
        assertTrue(phone.player.playing, "the phone took its audio back")
        assertEquals("phone-1", w.server.snapshot().session?.ownerId)
        assertTrue(phone.log.any { it.contains("never picked up") })
        assertTrue(w.doubleAudio.isEmpty())
    }

    @Test
    fun simultaneousCommandsAndTransfersNeverLeaveTwoOutputs() = runTest {
        val (w, phone, others) = threeDevices()
        val (laptop, desktop) = others
        phone.user(ConnectIntent.Load(songs(5), 0))
        w.run(10_000)
        // Both computers press things at the same moment, and both ask for the audio.
        laptop.user(ConnectIntent.Pause)
        desktop.user(ConnectIntent.Next)
        laptop.engine.transferTo("laptop-1")
        desktop.engine.transferTo("desktop-1")
        repeat(30) { w.run(500) }
        val sounding = w.devices.filter { it.player.playing }
        assertTrue(sounding.size <= 1, "sounding: ${sounding.map { it.engine.me.id }}")
        val owner = w.server.snapshot().session!!.ownerId
        for (d in w.devices) assertEquals(owner, d.engine.ui.value.output?.deviceId)
        assertTrue(w.doubleAudio.isEmpty(), "two devices sounded at ${w.doubleAudio}")
        assertTrue(w.server.violations.isEmpty(), w.server.violations.toString())
    }

    @Test
    fun onlyTheOutputAdvancesTheQueue() = runTest {
        val (w, phone, others) = threeDevices()
        phone.user(ConnectIntent.Load(songs(3), 0))
        w.run(185_000)
        assertEquals("song2", phone.player.track?.id)
        w.run(3_000)
        assertEquals("song2", others.first.engine.ui.value.remote?.track?.id)
        assertEquals("song2", others.second.engine.ui.value.remote?.track?.id)
        assertTrue(others.first.player.track == null && others.second.player.track == null, "mirrors never load songs")
    }

    @Test
    fun outputThatLostItsConnectionDoesNotReclaimPlayback() = runTest {
        val (w, phone, others) = threeDevices()
        val (laptop, desktop) = others
        phone.user(ConnectIntent.Load(songs(3), 0))
        w.run(10_000)
        phone.offline = true // keeps playing offline (downloads), but can't renew its lease
        w.run(100_000)
        val remote = laptop.engine.ui.value.remote!!
        assertFalse(remote.output.active, "shown as last played on the phone")
        assertFalse(remote.playing)
        // Play on the laptop takes the session over here (the phone's lease ran out).
        laptop.user(ConnectIntent.Play)
        w.run(6_000)
        assertTrue(laptop.player.playing)
        assertEquals("laptop-1", w.server.snapshot().session?.ownerId)
        phone.reconnect()
        w.run(5_000)
        assertFalse(phone.player.playing, "the old output stops and doesn't take the session back")
        assertEquals("laptop-1", w.server.snapshot().session?.ownerId)
        assertEquals("laptop-1", desktop.engine.ui.value.output?.deviceId)
        assertTrue(w.server.violations.isEmpty(), w.server.violations.toString())
    }

    @Test
    fun unresponsiveOutputIsReplacedAfterTheTimeout() = runTest {
        val (w, phone, others) = threeDevices()
        val (laptop, _) = others
        phone.user(ConnectIntent.Load(songs(3), 0))
        w.run(10_000)
        laptop.engine.transferTo("laptop-1")
        w.run(150) // the request is written...
        phone.offline = true // ...and the phone drops off before it can hand over
        w.run(15_000)
        assertEquals("laptop-1", w.server.snapshot().session?.ownerId)
        assertTrue(laptop.player.playing)
        phone.reconnect()
        w.run(4_000)
        assertFalse(phone.player.playing)
    }

    @Test
    fun songThatWontStartIsHandedBack() = runTest {
        val (w, phone, others) = threeDevices()
        val (laptop, _) = others
        phone.user(ConnectIntent.Load(songs(3), 0))
        w.run(10_000)
        laptop.player.failStart = true
        phone.engine.transferTo("laptop-1")
        w.run(40_000)
        assertEquals("phone-1", w.server.snapshot().session?.ownerId)
        assertTrue(phone.player.playing)
    }

    @Test
    fun signingOutReleasesTheSessionAndControl() = runTest {
        val (w, phone, others) = threeDevices()
        val (laptop, _) = others
        phone.user(ConnectIntent.Load(songs(3), 0))
        w.run(10_000)
        phone.engine.stop(signOut = true)
        w.run(3_000)
        assertFalse(phone.engine.ui.value.enabled)
        assertFalse(phone.engine.intercept(ConnectIntent.Pause), "a signed-out client controls nothing")
        assertFalse("phone-1" in w.server.devices)
        val s = w.server.snapshot().session!!
        assertFalse(s.playing)
        assertFalse(s.ownerActive(w.test.testScheduler.currentTime))
        // Others see it as no longer active and may take over with an explicit Play.
        laptop.user(ConnectIntent.Play)
        w.run(5_000)
        assertTrue(laptop.player.playing)
    }

    @Test
    fun anotherAccountsDeviceCannotBeTargeted() = runTest {
        val (w, phone, _) = threeDevices()
        phone.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        // A device id from someone else's account (known, e.g. seen on the same Wi-Fi) is not on
        // this account's device list, so nothing is requested and nothing moves.
        phone.engine.transferTo("other-account-laptop")
        w.run(5_000)
        assertNull(w.server.snapshot().session?.transfer)
        assertTrue(phone.player.playing)
    }

    @Test
    fun staleWritesNeverOverwriteNewerState() = runTest {
        val (w, phone, others) = threeDevices()
        phone.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        others.first.user(ConnectIntent.Pause); w.run(3_000)
        others.second.user(ConnectIntent.Play); w.run(3_000)
        assertTrue(w.server.version > 2)
        val versions = mutableListOf<Long>()
        repeat(20) { w.run(500); versions += w.server.version }
        assertEquals(versions.sorted(), versions)
        // An old snapshot arriving late is ignored.
        val old = ConnectSnapshot(ConnectSession(version = 1, ownerId = "desktop-1", leaseUntilMs = Long.MAX_VALUE, track = ConnectTrack("x", "X", "Y")))
        others.first.engine.onSnapshot(old)
        w.run(100)
        assertEquals("phone-1", others.first.engine.ui.value.output?.deviceId)
    }
}
