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
    @Test fun aloneTheOutputBarelyWritesAndASecondDeviceGetsTheRealStateAtOnce() = runTest {
        val w = World(this)
        val pc = w.device("pc", "PC", ConnectPlatform.WINDOWS, ConnectDeviceType.COMPUTER)
        val phone = w.device("phone", "Phone", ConnectPlatform.ANDROID, ConnectDeviceType.PHONE)
        pc.start()
        pc.user(ConnectIntent.Load(songs(40), 0))
        w.run(10_000)
        val before = w.server.writes
        w.run(600_000)
        val written = w.server.writes - before
        assertTrue(written <= 8, "Ten solo minutes must not cost a write every few seconds: $written")

        phone.start()
        w.run(3_000)
        val mirror = assertNotNull(phone.engine.ui.value.remote)
        assertEquals(pc.player.track?.id, mirror.track.id)
        assertTrue(kotlin.math.abs(mirror.positionAt(phone.localNow()) - pc.player.positionMs) < 2_000)
        assertFalse(phone.player.playing)
        assertTrue(w.server.violations.isEmpty())
    }

    @Test fun lyricsSelectionFollowsTheOutputAndClearsOnSongChange() = runTest {
        val w = World(this)
        val pc = w.device("pc", "PC", ConnectPlatform.WINDOWS, ConnectDeviceType.COMPUTER)
        val phone = w.device("phone", "Phone", ConnectPlatform.ANDROID, ConnectDeviceType.PHONE)
        pc.start()
        pc.user(ConnectIntent.Load(songs(3), 0))
        w.run(4000)
        phone.start()
        w.run(3000)
        val selection = ConnectLyricsSelection("song1", com.auralis.music.domain.model.LyricsProvider.QQMUSIC)
        pc.player.lyricsSelection = selection
        w.run(1500)
        assertEquals(selection, phone.engine.ui.value.remote?.lyricsSelection)
        val spicy = selection.copy(provider = com.auralis.music.domain.model.LyricsProvider.SPICY_LYRICS,
            spotifyId = "abcdefghijklmnopqrstuv", title = "Song 1 - 2011 Remaster", artist = "Artist", durationMs = 180000)
        pc.player.lyricsSelection = spicy
        w.run(1500)
        assertEquals(spicy, phone.engine.ui.value.remote?.lyricsSelection)
        pc.user(ConnectIntent.Next)
        w.run(2000)
        assertEquals("song2", phone.engine.ui.value.remote?.track?.id)
        assertNull(phone.engine.ui.value.remote?.lyricsSelection, "Never attach the old song's selection to the new song")
        assertTrue(w.server.violations.isEmpty())
    }

    @Test fun activePlaybackMirrorsButIdleAppsSelectThemselvesInBothDirections() = runTest {
        for (phoneStarts in listOf(true, false)) {
            val w = World(this)
            val phone = w.device("phone", "Phone", ConnectPlatform.ANDROID, ConnectDeviceType.PHONE)
            val pc = w.device("pc", "PC", ConnectPlatform.WINDOWS, ConnectDeviceType.COMPUTER)
            val source = if (phoneStarts) phone else pc
            val target = if (phoneStarts) pc else phone
            w.watchDoubleAudio()
            source.start()
            source.user(ConnectIntent.Load(songs(3), 1))
            w.run(12_000)
            target.start()
            w.run(3_000)
            val mirror = assertNotNull(target.engine.ui.value.remote)
            assertEquals(source.engine.me.id, mirror.output.deviceId)
            assertEquals("song2", mirror.track.id)
            assertTrue(mirror.playing)
            assertTrue(kotlin.math.abs(mirror.positionAt(target.localNow()) - source.player.positionMs) < 1_000)
            assertFalse(target.player.playing)

            target.engine.transferTo(target.engine.me.id)
            w.run(12_000)
            assertTrue(target.player.playing)
            assertFalse(source.player.playing)
            assertTrue(target.player.positionMs > 10_000)
            assertEquals(target.engine.me.id, source.engine.ui.value.output?.deviceId)

            target.user(ConnectIntent.Pause)
            w.run(3_000)
            val saved = assertNotNull(w.server.snapshot().session)
            for (device in listOf(phone, pc)) {
                assertNull(device.engine.ui.value.remote)
                assertEquals(device.engine.me.id, device.engine.ui.value.output?.deviceId)
                assertTrue(device.engine.ui.value.devices.single { it.isThis }.isOutput)
                assertFalse(device.player.playing)
                assertEquals(saved.positionMs, device.player.positionMs)
            }
            // The idle non-owner starts locally even while the paused owner still has a lease.
            source.user(ConnectIntent.Play)
            w.run(12_000)
            assertTrue(source.player.playing)
            assertFalse(target.player.playing)
            assertEquals(source.engine.me.id, target.engine.ui.value.output?.deviceId)
            assertNotNull(target.engine.ui.value.remote)
            assertTrue(source.player.positionMs >= saved.positionMs)
            assertTrue(w.doubleAudio.isEmpty(), w.doubleAudio.toString())
            assertTrue(w.server.violations.isEmpty(), w.server.violations.toString())
            phone.engine.stop(false)
            pc.engine.stop(false)
        }
    }

    @Test fun openingAnotherDeviceAfterTheOutputClosedRestoresTheWholeQueueAndPosition() = runTest {
        val w = World(this)
        val phone = w.device("phone-1", "Phone", ConnectPlatform.ANDROID, ConnectDeviceType.PHONE)
        phone.start()
        w.run(2_000)
        phone.user(ConnectIntent.Load(songs(4), 2))
        w.run(3_000)
        phone.user(ConnectIntent.Seek(67_000))
        phone.user(ConnectIntent.Pause)
        w.run(2_000)
        phone.engine.stop(false)
        val saved = assertNotNull(w.server.snapshot().session)
        val pc = w.device("pc", "This PC", ConnectPlatform.WINDOWS, ConnectDeviceType.COMPUTER)
        pc.start()
        w.run(3_000)
        assertNull(pc.engine.ui.value.remote)
        assertEquals("pc", pc.engine.ui.value.output?.deviceId)
        assertEquals(4, pc.player.queue.size)
        assertEquals(2, pc.player.index)
        assertEquals(saved.positionMs, pc.player.positionMs)
        assertFalse(pc.player.playing)
        assertEquals(saved.ownerId, w.server.snapshot().session?.ownerId, "Restoration alone must not claim the account")
        pc.user(ConnectIntent.Play)
        w.run(3_000)
        assertEquals("pc", w.server.snapshot().session?.ownerId)
        assertTrue(pc.player.playing)
        assertTrue(pc.player.positionMs >= saved.positionMs)
        assertTrue(w.server.violations.isEmpty(), w.server.violations.toString())
    }

    @Test fun unavailablePausedOutputDefaultsToTheCurrentDeviceWithItsBookmark() = runTest {
        val (w, phone, computers) = threeDevices()
        for ((source, target) in listOf(phone to computers.first, computers.first to phone)) {
            source.user(ConnectIntent.Load(songs(3), 1))
            w.run(3_000)
            source.user(ConnectIntent.Seek(42_000))
            source.user(ConnectIntent.Pause)
            w.run(3_000)
            val bookmark = assertNotNull(w.server.snapshot().session).positionMs
            source.engine.setDeviceAvailable(false)
            w.run(4_000)
            assertNull(target.engine.ui.value.remote, "An unavailable output must not stay selected")
            assertTrue(assertNotNull(target.engine.ui.value.output).isThis)
            assertEquals(target.engine.ui.value.me?.id, target.engine.ui.value.output?.deviceId)
            assertEquals("song2", target.player.track?.id)
            assertEquals(bookmark, target.player.positionMs)
            assertFalse(target.player.playing, "Opening a device restores silently")
            assertTrue(target.engine.ui.value.devices.first { it.isThis }.isOutput)
            target.user(ConnectIntent.Play)
            w.run(3_000)
            assertEquals(target.engine.ui.value.me?.id, w.server.snapshot().session?.ownerId)
            assertTrue(target.player.playing)
            assertTrue(target.player.positionMs >= bookmark)
            target.user(ConnectIntent.Pause)
            source.engine.setDeviceAvailable(true)
            w.run(3_000)
        }
        assertTrue(w.server.violations.isEmpty(), w.server.violations.toString())
        assertTrue(w.doubleAudio.isEmpty())
    }

    @Test fun committedSeekDoesNotWaitForTheOutputsSlowPositionWrite() = runTest {
        val w = World(this)
        val output = w.device("phone", "Phone", ConnectPlatform.ANDROID, ConnectDeviceType.PHONE, roundTripMs = 2_000L)
        val remote = w.device("pc", "PC", ConnectPlatform.WINDOWS, ConnectDeviceType.COMPUTER)
        output.start(); remote.start()
        output.user(ConnectIntent.Load(songs(1), 0))
        w.run(18_000)
        output.player.seekTo(30_000)
        output.engine.onLocalSeek()
        w.run(20) // The output is now waiting on a two-second backend write.
        remote.user(ConnectIntent.Seek(90_000))
        w.run(500)
        assertTrue(output.player.positionMs in 90_000L..91_000L,
            "The realtime listener must apply a seek while the position write is still in flight")
        w.run(6_000)
        assertTrue(output.player.positionMs in 90_000L..97_000L)
        assertTrue(w.server.violations.isEmpty())
    }

    @Test fun explicitSmallSeekPublishesWithoutWaitingForDriftOrCheckpoint() = runTest {
        val w = World(this)
        val output = w.device("phone", "Phone", ConnectPlatform.ANDROID, ConnectDeviceType.PHONE)
        val remote = w.device("pc", "PC", ConnectPlatform.WINDOWS, ConnectDeviceType.COMPUTER)
        output.start(); remote.start()
        output.user(ConnectIntent.Load(songs(1), 0))
        w.run(2_000)
        output.user(ConnectIntent.Pause)
        w.run(500)
        val target = output.player.positionMs + 500L
        output.player.seekTo(target)
        output.engine.onLocalSeek()
        w.run(300)
        assertNull(remote.engine.ui.value.remote)
        assertEquals(target, remote.player.positionMs)
        assertEquals(target, w.server.snapshot().session?.positionMs)
    }

    @Test fun rapidSeeksReachLatestTimestampInBothDirectionsWithoutReplayingEveryTap() = runTest {
        for (paused in listOf(true, false)) for (phoneIsOutput in listOf(true, false)) {
            val w = World(this)
            val phone = w.device("phone", "Phone", ConnectPlatform.ANDROID, ConnectDeviceType.PHONE)
            val pc = w.device("pc", "PC", ConnectPlatform.WINDOWS, ConnectDeviceType.COMPUTER)
            phone.start(); pc.start()
            val output = if (phoneIsOutput) phone else pc
            val remote = if (phoneIsOutput) pc else phone
            output.user(ConnectIntent.Load(songs(1), 0))
            w.run(2_000)
            if (paused) output.user(ConnectIntent.Pause)
            w.run(500)
            remote.user(ConnectIntent.Seek(40_000))
            w.run(20)
            repeat(30) { remote.user(ConnectIntent.Seek(50_000L + it * 1_000L)) }
            w.run(3_000)
            if (paused) {
                assertEquals(79_000L, remote.player.positionMs)
                assertNull(remote.engine.ui.value.remote)
                assertFalse(remote.player.playing)
                assertFalse(output.player.playing)
                assertEquals(remote.engine.me.id, w.server.snapshot().session?.ownerId)
            } else {
                assertTrue(output.player.positionMs in 79_000L..83_000L)
                assertTrue(output.player.playing)
                assertFalse(remote.player.playing)
                assertEquals(output.engine.me.id, remote.engine.ui.value.output?.deviceId)
                assertNotNull(remote.engine.ui.value.remote)
            }
            assertTrue(w.server.violations.isEmpty())
            phone.engine.stop(false); pc.engine.stop(false)
        }
    }

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
        w.run(17_000)
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

        fun device(id: String, name: String, platform: ConnectPlatform, type: ConnectDeviceType, skew: Long = 0L, latency: Long = 100L,
                   roundTripMs: Long = 80L) =
            SimDevice(server, test.backgroundScope, test, id, name, platform, type, skew, latency, roundTripMs).also { devices += it }

        /** The app's engine stops and starts while the player keeps going. */
        fun restarted(d: SimDevice, store: ConnectControlStore?) = SimDevice(server, test.backgroundScope, test, d.engine.me.id, d.engine.me.name,
            d.engine.me.platform, d.engine.me.type, player = d.player, savedControls = store).also { devices += it }

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
        for (d in listOf(laptop, desktop)) {
            assertNull(d.engine.ui.value.remote)
            assertEquals(paused, d.player.positionMs, "settles at the paused position locally")
        }

        desktop.user(ConnectIntent.Seek(90_000))
        w.run(4_000)
        assertEquals(90_000, desktop.player.positionMs)
        assertFalse(phone.player.playing)

        desktop.user(ConnectIntent.Play)
        w.run(4_000)
        assertTrue(desktop.player.playing)

        laptop.user(ConnectIntent.Next)
        w.run(4_000)
        assertEquals("song2", desktop.player.track?.id)
        w.run(3_000)
        assertEquals("song2", laptop.engine.ui.value.remote!!.track.id)

        desktop.user(ConnectIntent.PlayIndex(3, "song4"))
        w.run(4_000)
        assertEquals("song4", desktop.player.track?.id)
        assertFalse(laptop.player.playing || phone.player.playing)
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
        assertEquals("laptop-1", w.server.snapshot().session?.ownerId)
        assertEquals("phone-1", phone.engine.ui.value.output?.deviceId)
        assertEquals("laptop-1", laptop.engine.ui.value.output?.deviceId)
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
        for (d in w.devices) assertEquals(if (sounding.isEmpty()) d.engine.me.id else owner, d.engine.ui.value.output?.deviceId)
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

    /** Another device played last and closed; this phone then can't write to the session. */
    private fun TestScope.phoneAfterAnotherDeviceClosed(): Pair<World, SimDevice> {
        val w = World(this)
        val phone = w.device("phone-1", "Phone", ConnectPlatform.ANDROID, ConnectDeviceType.PHONE)
        val pc = w.device("pc", "This PC", ConnectPlatform.WINDOWS, ConnectDeviceType.COMPUTER)
        phone.start()
        pc.start()
        w.run(2_000)
        pc.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        pc.user(ConnectIntent.Pause)
        w.run(2_000)
        backgroundScope.launch { pc.engine.stop(false) }
        w.run(2_000)
        return w to phone
    }

    /** Each control must have acted when [SimDevice.user] returns: no virtual time passes in here. */
    private fun assertEveryLocalControlActsAtOnce(d: SimDevice) {
        d.user(ConnectIntent.Load(songs(3, "new"), 0))
        assertTrue(d.player.playing)
        assertEquals("new1", d.player.track?.id)
        d.user(ConnectIntent.Next)
        assertEquals("new2", d.player.track?.id)
        d.user(ConnectIntent.Previous)
        assertEquals("new1", d.player.track?.id)
        d.user(ConnectIntent.Seek(42_000))
        assertEquals(42_000, d.player.positionMs)
        d.user(ConnectIntent.Pause)
        assertFalse(d.player.playing)
        d.user(ConnectIntent.Play)
        assertTrue(d.player.playing)
        d.user(ConnectIntent.PlayIndex(2, "new3"))
        assertEquals("new3", d.player.track?.id)
    }

    @Test
    fun localControlsActAtOnceWhileFirestoreIsHealthy() = runTest {
        val (_, phone) = phoneAfterAnotherDeviceClosed()
        assertEveryLocalControlActsAtOnce(phone)
    }

    @Test
    fun localControlsActAtOnceWithConnectDisabled() = runTest {
        val w = World(this)
        val neverStarted = w.device("phone", "Phone", ConnectPlatform.ANDROID, ConnectDeviceType.PHONE)
        assertEveryLocalControlActsAtOnce(neverStarted)
        val signedOut = w.device("pc", "PC", ConnectPlatform.WINDOWS, ConnectDeviceType.COMPUTER)
        signedOut.start()
        w.run(2_000)
        signedOut.engine.stop(signOut = true)
        assertEveryLocalControlActsAtOnce(signedOut)
    }

    @Test
    fun everyLocalControlWorksWithAllFirestoreCallsFailingFromLaunch() = runTest {
        val w = World(this)
        val phone = w.device("phone", "Phone", ConnectPlatform.ANDROID, ConnectDeviceType.PHONE)
        phone.offline = true
        phone.start()
        w.run(2_000)
        assertEveryLocalControlActsAtOnce(phone)
        w.run(120_000)
        assertTrue(phone.player.playing, "Failed calls must not disturb the music")
        assertNull(w.server.snapshot().session)
    }

    @Test
    fun everyLocalControlWorksWithFirestoreNeverAnsweringFromLaunch() = runTest {
        val w = World(this)
        val phone = w.device("phone", "Phone", ConnectPlatform.ANDROID, ConnectDeviceType.PHONE)
        phone.firestoreHangs = true
        phone.start()
        w.run(2_000)
        assertEveryLocalControlActsAtOnce(phone)
        w.run(120_000)
        assertTrue(phone.player.playing)
    }

    @Test
    fun everyLocalControlWorksOnAKnownSessionWhenFirestoreStopsAnswering() = runTest {
        val (w, phone) = phoneAfterAnotherDeviceClosed()
        phone.firestoreHangs = true
        assertEveryLocalControlActsAtOnce(phone)
        w.run(60_000)
        assertTrue(phone.player.playing)
        assertEquals("new3", phone.player.track?.id)
    }

    @Test
    fun aTappedSongPlaysWhenTheQuotaIsUsedUpAndTheSessionCatchesUpLater() = runTest {
        val (w, phone) = phoneAfterAnotherDeviceClosed()
        phone.quotaUsedUp = true
        phone.user(ConnectIntent.Load(songs(2, "new"), 0))
        assertTrue(phone.player.playing)
        assertEquals("new1", phone.player.track?.id)
        assertNull(phone.engine.ui.value.remote)
        assertEquals("phone-1", phone.engine.ui.value.output?.deviceId)
        phone.user(ConnectIntent.Next)
        assertEquals("new2", phone.player.track?.id)
        w.run(1_000)
        phone.quotaUsedUp = false
        w.run(35_000)
        assertTrue(phone.engine.isOutput)
        assertEquals("phone-1", w.server.snapshot().session?.ownerId)
        assertEquals("new2", w.server.snapshot().session?.track?.id)
        assertEquals("new2", phone.player.track?.id, "Reconciling never replaces what plays here")
        assertTrue(w.server.violations.isEmpty(), w.server.violations.toString())
    }

    @Test
    fun playResumesTheBookmarkWhenTheQuotaIsUsedUp() = runTest {
        val (_, phone) = phoneAfterAnotherDeviceClosed()
        phone.quotaUsedUp = true
        phone.user(ConnectIntent.Play)
        assertTrue(phone.player.playing)
        assertEquals("song1", phone.player.track?.id)
    }

    @Test
    fun aControlBeforeTheFirstReadActsHereAndIsNotSilencedByTheSessionThatArrives() = runTest {
        val (w, phone, _) = threeDevices()
        phone.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        val tablet = w.device("tablet-1", "Tablet", ConnectPlatform.ANDROID, ConnectDeviceType.TABLET)
        tablet.user(ConnectIntent.Load(songs(2, "new"), 0)) // before it has read anything
        tablet.start()
        w.run(6_000)
        assertTrue(tablet.player.playing, "The newest tap wins")
        assertEquals("new1", tablet.player.track?.id)
        assertFalse(phone.player.playing)
        assertEquals("tablet-1", w.server.snapshot().session?.ownerId)
        assertTrue(w.server.violations.isEmpty(), w.server.violations.toString())
    }

    @Test
    fun aDeviceOwnSongSurvivesThePausedOutputsBookmarkArriving() = runTest {
        val (w, phone) = phoneAfterAnotherDeviceClosed()
        phone.quotaUsedUp = true
        phone.user(ConnectIntent.Load(songs(2, "new"), 0))
        phone.user(ConnectIntent.Pause)
        w.run(20_000)
        assertEquals("new1", phone.player.track?.id, "A silent bookmark must not replace a song chosen here")
    }

    @Test
    fun aTappedSongPlaysImmediatelyWhenTheOutputIsKnownToBeUnreachable() = runTest {
        val (w, phone, others) = threeDevices()
        val laptop = others.first
        laptop.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        assertNotNull(phone.engine.ui.value.remote)
        phone.engine.onConnectionLost("Offline")
        w.run(100)
        phone.offline = true
        val commands = w.server.snapshot().session?.commands
        phone.user(ConnectIntent.Load(songs(2, "new"), 0))
        assertTrue(phone.player.playing, "Must not wait on a device lease or a write")
        assertEquals("new1", phone.player.track?.id)
        assertNull(phone.engine.ui.value.remote)
        assertEquals(commands, w.server.snapshot().session?.commands)
    }

    @Test
    fun onReconnectTheNewestLocalControlTakesTheOutputThroughAHandoffOnce() = runTest {
        val (w, phone, others) = threeDevices()
        val laptop = others.first
        laptop.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        phone.offline = true
        phone.engine.onConnectionLost("Offline")
        w.run(100)
        phone.user(ConnectIntent.Load(songs(2, "new"), 0))
        w.run(20_000)
        assertTrue(phone.player.playing)
        val beforeSong = phone.player.track?.id
        val position = phone.player.positionMs
        phone.reconnect()
        w.run(35_000)
        assertEquals("phone-1", w.server.snapshot().session?.ownerId)
        assertEquals(beforeSong, phone.player.track?.id)
        assertTrue(phone.player.positionMs > position, "Reconciling must not restart the song")
        assertTrue(phone.player.playing)
        assertFalse(laptop.player.playing, "Never two outputs once both are online")
        assertTrue(w.server.violations.isEmpty(), w.server.violations.toString())
    }

    @Test
    fun onReconnectADeviceThatTookTheOutputMeanwhileKeepsIt() = runTest {
        val (w, phone, others) = threeDevices()
        val (laptop, desktop) = others
        laptop.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        phone.offline = true
        phone.engine.onConnectionLost("Offline")
        w.run(100)
        phone.user(ConnectIntent.Load(songs(2, "new"), 0))
        desktop.engine.transferTo("desktop-1")
        w.run(12_000)
        assertTrue(desktop.player.playing)
        assertTrue(phone.player.playing, "Offline, nothing can interrupt it")
        phone.reconnect()
        w.run(35_000)
        assertFalse(phone.player.playing)
        assertTrue(desktop.player.playing)
        assertEquals("desktop-1", w.server.snapshot().session?.ownerId)
        assertNotNull(phone.engine.ui.value.remote)
    }

    @Test
    fun onReconnectASilentDeviceLeavesThePlayingOutputAlone() = runTest {
        val (w, phone, others) = threeDevices()
        val laptop = others.first
        laptop.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        phone.offline = true
        phone.engine.onConnectionLost("Offline")
        w.run(100)
        phone.user(ConnectIntent.Load(songs(2, "new"), 0))
        phone.user(ConnectIntent.Pause)
        w.run(5_000)
        phone.reconnect()
        w.run(35_000)
        assertTrue(laptop.player.playing, "A paused phone must not stop the laptop")
        assertEquals("laptop-1", w.server.snapshot().session?.ownerId)
        assertNotNull(phone.engine.ui.value.remote)
    }

    @Test
    fun pausingAMirroredOutputThatCantBeReachedNeverStartsAudioHere() = runTest {
        val (w, phone, others) = threeDevices()
        others.first.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        phone.quotaUsedUp = true
        phone.engine.onConnectionLost("RESOURCE_EXHAUSTED")
        w.run(100)
        assertTrue(phone.engine.intercept(ConnectIntent.Pause), "Pause is for the device shown on screen")
        assertTrue(phone.engine.intercept(ConnectIntent.Seek(10_000)))
        w.run(5_000)
        assertFalse(phone.player.playing)
        assertNotNull(phone.engine.ui.value.remote)
    }

    @Test
    fun aSongForAnOutputThatFailsPlaysHereAndTheTapIsNotLost() = runTest {
        val (w, phone, others) = threeDevices()
        others.first.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        phone.quotaUsedUp = true
        phone.user(ConnectIntent.Load(songs(2, "new"), 0))
        w.run(1_000)
        assertTrue(phone.player.playing)
        assertEquals("new1", phone.player.track?.id)
        phone.user(ConnectIntent.Pause)
        assertFalse(phone.player.playing, "Controls then belong to this device")
    }

    @Test
    fun aSongForAnOutputThatNeverAnswersPlaysHereWithinThePatience() = runTest {
        val (w, phone, others) = threeDevices()
        others.first.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        phone.firestoreHangs = true
        phone.user(ConnectIntent.Load(songs(2, "new"), 0))
        w.run(3_000)
        assertFalse(phone.player.playing, "A slow write to the other device gets its chance")
        w.run(2_000)
        assertTrue(phone.player.playing)
    }

    @Test
    fun remoteControlsStillReachALiveOutputWithoutAudioHere() = runTest {
        val (w, phone, others) = threeDevices()
        val laptop = others.first
        laptop.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        phone.user(ConnectIntent.Load(songs(2, "picked"), 1))
        w.run(3_000)
        assertEquals("picked2", laptop.player.track?.id)
        assertFalse(phone.player.playing)
        assertTrue(w.server.snapshot().session?.commands.orEmpty().size <= 1, "One tap is one command")
    }

    @Test
    fun aHandoffThatCantBeRequestedSaysSoAndChangesNothing() = runTest {
        val (w, phone, others) = threeDevices()
        val (laptop, desktop) = others
        phone.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        desktop.quotaUsedUp = true
        desktop.engine.transferTo("laptop-1")
        w.run(1_000)
        assertTrue(desktop.engine.ui.value.transfer?.failed == true)
        assertTrue(phone.player.playing)
        assertFalse(laptop.player.playing)
    }

    @Test
    fun askingForTheAudioHerePlaysHereWhenTheHandoffCantBeWritten() = runTest {
        val (w, phone, others) = threeDevices()
        others.first.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        phone.quotaUsedUp = true
        phone.engine.transferTo("phone-1")
        w.run(1_000)
        assertTrue(phone.player.playing)
        assertEquals("song1", phone.player.track?.id)
    }

    @Test
    fun failedWritesBackOffInsteadOfRetryingEverySecond() = runTest {
        val (w, phone, _) = threeDevices()
        phone.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        phone.quotaUsedUp = true
        phone.player.seekTo(30_000)
        w.run(1_000)
        val before = phone.writeAttempts
        w.run(120_000)
        assertTrue(phone.writeAttempts - before <= 8, "${phone.writeAttempts - before} attempts in two minutes")
        assertTrue(phone.player.playing)
    }

    @Test
    fun anOutputThatLosesFirestoreKeepsPlayingAndRenewsWhenItReturns() = runTest {
        val (w, phone, _) = threeDevices()
        phone.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        val epoch = w.server.snapshot().session?.epoch
        phone.quotaUsedUp = true
        w.run(100_000)
        assertTrue(phone.player.playing)
        phone.quotaUsedUp = false
        w.run(35_000)
        assertTrue(phone.player.playing)
        assertTrue(phone.engine.isOutput)
        assertEquals(epoch, w.server.snapshot().session?.epoch)
    }

    /** A goes offline; the platform notices, as a listener does. */
    private fun World.dropOff(d: SimDevice) {
        d.offline = true
        d.engine.onConnectionLost("Offline")
        run(100)
    }

    @Test
    fun anOfflineSongTappedAfterAnotherDeviceTookTheOutputWins() = runTest {
        val (w, phone, others) = threeDevices()
        val (laptop, desktop) = others
        laptop.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        w.dropOff(phone)
        desktop.engine.transferTo("desktop-1")
        w.run(20_000)
        assertTrue(desktop.player.playing)
        phone.user(ConnectIntent.Load(songs(2, "new"), 0))
        w.run(20_000)
        phone.reconnect()
        w.run(35_000)
        assertEquals("phone-1", w.server.snapshot().session?.ownerId)
        assertTrue(phone.player.playing, "The newer action must survive reconnecting")
        assertEquals("new1", phone.player.track?.id)
        assertFalse(desktop.player.playing)
        assertTrue(w.server.violations.isEmpty(), w.server.violations.toString())
    }

    @Test
    fun anOfflineSongTappedBeforeAnotherDeviceTookTheOutputLoses() = runTest {
        val (w, phone, others) = threeDevices()
        val (laptop, desktop) = others
        laptop.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        w.dropOff(phone)
        phone.user(ConnectIntent.Load(songs(2, "new"), 0))
        w.run(20_000)
        desktop.engine.transferTo("desktop-1")
        w.run(20_000)
        phone.reconnect()
        w.run(35_000)
        assertEquals("desktop-1", w.server.snapshot().session?.ownerId)
        assertFalse(phone.player.playing)
        assertTrue(desktop.player.playing)
        assertTrue(phone.engine.ui.value.canPlayHere, "The way back is one action")
    }

    @Test
    fun wallClockChangesWhileOfflineCantReverseTheOrder() = runTest {
        for (jump in listOf(3_600_000L, -3_600_000L)) for (tapAfterTakeover in listOf(true, false)) {
            val (w, phone, others) = threeDevices()
            val (laptop, desktop) = others
            laptop.user(ConnectIntent.Load(songs(3), 0))
            w.run(5_000)
            w.dropOff(phone)
            phone.skewMs += jump
            if (!tapAfterTakeover) phone.user(ConnectIntent.Load(songs(2, "new"), 0))
            w.run(60_000)
            desktop.engine.transferTo("desktop-1")
            w.run(60_000)
            if (tapAfterTakeover) phone.user(ConnectIntent.Load(songs(2, "new"), 0))
            w.run(5_000)
            phone.reconnect()
            w.run(70_000)
            val winner = if (tapAfterTakeover) "phone-1" else "desktop-1"
            assertEquals(winner, w.server.snapshot().session?.ownerId, "jump $jump, tap after takeover $tapAfterTakeover")
            assertEquals(tapAfterTakeover, phone.player.playing)
            assertEquals(!tapAfterTakeover, desktop.player.playing)
            w.devices.forEach { it.engine.stop(false) }
        }
    }

    @Test
    fun aDeviceWithAWrongClockStillOrdersActionsCorrectly() = runTest {
        val w = World(this)
        val phone = w.device("phone-1", "Pixel 8", ConnectPlatform.ANDROID, ConnectDeviceType.PHONE, skew = -7_200_000L)
        val pc = w.device("pc", "PC", ConnectPlatform.WINDOWS, ConnectDeviceType.COMPUTER, skew = 3_600_000L)
        phone.start(); pc.start()
        w.run(2_000)
        pc.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        w.dropOff(phone)
        pc.user(ConnectIntent.Next) // a real action, but older than the phone's
        w.run(20_000)
        phone.user(ConnectIntent.Load(songs(2, "new"), 0))
        w.run(10_000)
        phone.reconnect()
        w.run(35_000)
        assertEquals("phone-1", w.server.snapshot().session?.ownerId)
        assertTrue(phone.player.playing)
        assertFalse(pc.player.playing)
    }

    @Test
    fun aNewerActionOnTheOutputItselfBeatsAnOlderOfflineTap() = runTest {
        val (w, phone, others) = threeDevices()
        val laptop = others.first
        laptop.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        w.dropOff(phone)
        phone.user(ConnectIntent.Load(songs(2, "new"), 0))
        w.run(20_000)
        laptop.user(ConnectIntent.Next) // the owner, not an ownership change
        w.run(20_000)
        phone.reconnect()
        w.run(35_000)
        assertEquals("laptop-1", w.server.snapshot().session?.ownerId)
        assertFalse(phone.player.playing)
        assertEquals("song2", laptop.player.track?.id)
    }

    @Test
    fun aRemoteControlSentAfterAnOfflineTapCountsAsTheNewerAction() = runTest {
        val (w, phone, others) = threeDevices()
        val (laptop, desktop) = others
        laptop.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        w.dropOff(phone)
        phone.user(ConnectIntent.Load(songs(2, "new"), 0))
        w.run(20_000)
        desktop.user(ConnectIntent.Next)
        w.run(20_000)
        phone.reconnect()
        w.run(35_000)
        assertFalse(phone.player.playing)
        assertEquals("laptop-1", w.server.snapshot().session?.ownerId)
    }

    @Test
    fun anOutputsOwnOfflineControlIsReconciledInsteadOfBeingSilenced() = runTest {
        for (tapAfterTakeover in listOf(true, false)) {
            val (w, phone, others) = threeDevices()
            val laptop = others.first
            phone.user(ConnectIntent.Load(songs(3), 0))
            w.run(5_000)
            w.dropOff(phone)
            if (!tapAfterTakeover) phone.user(ConnectIntent.Next)
            w.run(100_000) // the phone's lease runs out
            laptop.user(ConnectIntent.Play)
            w.run(20_000)
            if (tapAfterTakeover) phone.user(ConnectIntent.Next)
            w.run(5_000)
            phone.reconnect()
            w.run(35_000)
            assertEquals(if (tapAfterTakeover) "phone-1" else "laptop-1", w.server.snapshot().session?.ownerId)
            assertEquals(tapAfterTakeover, phone.player.playing)
            assertEquals(!tapAfterTakeover, laptop.player.playing)
            assertTrue(w.server.violations.isEmpty(), w.server.violations.toString())
            w.devices.forEach { it.engine.stop(false) }
        }
    }

    @Test
    fun anOutputWithNoOfflineControlStillStopsWhenItFindsTheOutputMoved() = runTest {
        val (w, phone, others) = threeDevices()
        phone.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        w.dropOff(phone)
        w.run(100_000)
        others.first.user(ConnectIntent.Play)
        w.run(20_000)
        phone.reconnect()
        w.run(5_000)
        assertFalse(phone.player.playing)
    }

    @Test
    fun twoDevicesComingBackInEitherOrderSettleOnTheNewerTap() = runTest {
        for (phoneFirst in listOf(true, false)) {
            val (w, phone, others) = threeDevices()
            val (laptop, desktop) = others
            desktop.user(ConnectIntent.Load(songs(3), 0))
            w.run(5_000)
            w.dropOff(phone)
            w.dropOff(laptop)
            phone.user(ConnectIntent.Load(songs(2, "old"), 0))
            w.run(20_000)
            laptop.user(ConnectIntent.Load(songs(2, "new"), 0))
            w.run(20_000)
            (if (phoneFirst) phone else laptop).reconnect()
            w.run(35_000)
            (if (phoneFirst) laptop else phone).reconnect()
            w.run(35_000)
            assertEquals("laptop-1", w.server.snapshot().session?.ownerId, "phone first: $phoneFirst")
            assertTrue(laptop.player.playing)
            assertFalse(phone.player.playing)
            assertFalse(desktop.player.playing)
            assertTrue(w.server.violations.isEmpty(), w.server.violations.toString())
            w.devices.forEach { it.engine.stop(false) }
        }
    }

    @Test
    fun aStaleCommandCantUndoAnOutputsNewerOfflineControl() = runTest {
        val (w, phone, others) = threeDevices()
        val laptop = others.first
        phone.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        w.dropOff(phone)
        laptop.user(ConnectIntent.Next) // reaches the session, not the offline phone
        w.run(5_000)
        phone.user(ConnectIntent.Pause)
        w.run(5_000)
        phone.reconnect()
        w.run(4_000)
        assertEquals("song1", phone.player.track?.id, "The older Next must not run after the newer Pause")
        assertFalse(phone.player.playing)
        assertEquals(phone.player.track?.id, w.server.snapshot().session?.track?.id)
    }

    @Test
    fun aNewerOfflineControlIsNotLostToTheFirstReportAfterReconnecting() = runTest {
        val (w, phone, others) = threeDevices()
        val laptop = others.first
        laptop.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        w.dropOff(phone)
        val seen = w.server.version
        w.run(40_000) // routine reports advance the session meanwhile
        val reports = w.server.version - seen
        phone.user(ConnectIntent.Load(songs(2, "new"), 0))
        phone.user(ConnectIntent.Next)
        w.run(5_000)
        assertTrue(reports > 0, "Nothing moved the session on")
        phone.reconnect()
        w.run(35_000)
        assertEquals("phone-1", w.server.snapshot().session?.ownerId)
        assertEquals("new2", phone.player.track?.id)
    }

    @Test
    fun reconcilingCostsAHandfulOfWritesAndThenStops() = runTest {
        val (w, phone, others) = threeDevices()
        others.first.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        w.dropOff(phone)
        phone.user(ConnectIntent.Load(songs(2, "new"), 0))
        w.run(5_000)
        val before = w.server.writes
        phone.reconnect()
        w.run(10_000)
        val settling = w.server.writes - before
        assertTrue(settling <= 5, "$settling writes")
        val recorded = w.server.intentWrites
        val after = w.server.writes
        w.run(30_000)
        assertTrue(w.server.writes - after <= 4, "${w.server.writes - after} writes: routine reports only")
        assertEquals(recorded, w.server.intentWrites, "Routine reports are not actions")
    }

    @Test
    fun anIntentIsPublishedAtOnceEvenWithNobodyElseOnline() = runTest {
        val w = World(this)
        val pc = w.device("pc", "PC", ConnectPlatform.WINDOWS, ConnectDeviceType.COMPUTER)
        pc.start()
        pc.user(ConnectIntent.Load(songs(3), 0))
        w.run(20_000)
        val before = w.server.intentWrites
        pc.user(ConnectIntent.Pause)
        w.run(7_000)
        assertEquals(before + 1, w.server.intentWrites, "A returning device must see it")
        assertEquals(w.server.snapshot().session?.version, w.server.intent?.version)
    }

    @Test
    fun skippingThroughSongsAloneCostsOneActionOnceItSettles() = runTest {
        val w = World(this)
        val pc = w.device("pc", "PC", ConnectPlatform.WINDOWS, ConnectDeviceType.COMPUTER)
        pc.start()
        pc.user(ConnectIntent.Load(songs(30), 0))
        w.run(20_000)
        val writes = w.server.writes
        val intents = w.server.intentWrites
        repeat(10) { pc.user(ConnectIntent.Next); w.run(400) }
        w.run(8_000)
        assertEquals("song11", pc.player.track?.id)
        assertEquals("song11", w.server.snapshot().session?.track?.id)
        assertTrue(w.server.writes - writes <= 2, "${w.server.writes - writes} session writes")
        assertEquals(intents + 1, w.server.intentWrites)
    }

    @Test
    fun pressingThisDeviceBringsTheAudioBackFromAnotherDevice() = runTest {
        val (w, phone, others) = threeDevices()
        val laptop = others.first
        laptop.user(ConnectIntent.Load(songs(3), 0))
        w.run(8_000)
        assertTrue(phone.engine.ui.value.canPlayHere)
        assertTrue(phone.engine.ui.value.output?.isThis == false)
        phone.engine.transferTo("phone-1")
        w.run(6_000)
        assertTrue(phone.player.playing)
        assertFalse(laptop.player.playing)
        assertTrue(phone.engine.ui.value.output?.isThis == true)
        assertFalse(phone.engine.ui.value.canPlayHere)
        assertEquals("phone-1", laptop.engine.ui.value.output?.deviceId)
        assertTrue(laptop.engine.ui.value.canPlayHere)
    }

    @Test
    fun sessionsWrittenByReleasedClientsCantEraseTheIntent() = runTest {
        val (w, phone, others) = threeDevices()
        val (laptop, desktop) = others
        phone.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        val recorded = assertNotNull(w.server.intent)
        laptop.released = true
        desktop.released = true
        laptop.user(ConnectIntent.Pause)
        desktop.user(ConnectIntent.Next)
        w.run(40_000)
        assertTrue(w.server.version > recorded.version)
        assertEquals("phone-1", w.server.intent?.by, "A released client's writes leave it alone")
        assertTrue(w.server.violations.isEmpty(), w.server.violations.toString())
    }

    @Test
    fun aReleasedClientsTakeoverKeepsTheOutputSinceItRecordsNoIntent() = runTest {
        val (w, phone, others) = threeDevices()
        val (laptop, desktop) = others
        desktop.released = true
        laptop.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        w.dropOff(phone)
        desktop.engine.transferTo("desktop-1")
        w.run(20_000)
        phone.user(ConnectIntent.Load(songs(2, "new"), 0))
        w.run(5_000)
        phone.reconnect()
        w.run(35_000)
        assertEquals("desktop-1", w.server.snapshot().session?.ownerId, "With nothing recorded, ownership is all there is")
        assertFalse(phone.player.playing)
        assertTrue(desktop.player.playing)
        assertTrue(phone.engine.ui.value.canPlayHere)
    }

    @Test
    fun aReleasedOutputStillStopsWhenAnUpdatedDeviceTakesOver() = runTest {
        val (w, phone, others) = threeDevices()
        val laptop = others.first
        laptop.released = true
        laptop.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        w.dropOff(phone)
        phone.user(ConnectIntent.Load(songs(2, "new"), 0))
        w.run(5_000)
        phone.reconnect()
        w.run(35_000)
        assertEquals("phone-1", w.server.snapshot().session?.ownerId)
        assertFalse(laptop.player.playing)
        assertTrue(w.server.violations.isEmpty(), w.server.violations.toString())
    }

    @Test
    fun everythingStillWorksWhenTheRulesRefuseTheIntentDocument() = runTest {
        val (w, phone, others) = threeDevices()
        val (laptop, desktop) = others
        w.devices.forEach { it.intentRefused = true }
        phone.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        assertEquals("song1", laptop.engine.ui.value.remote?.track?.id)
        laptop.user(ConnectIntent.Pause)
        w.run(4_000)
        assertFalse(phone.player.playing)
        desktop.user(ConnectIntent.Play)
        w.run(4_000)
        assertTrue(desktop.player.playing)
        laptop.engine.transferTo("laptop-1")
        w.run(12_000)
        assertTrue(laptop.player.playing)
        assertFalse(desktop.player.playing)
        assertNull(w.server.intent)
        assertTrue(w.server.violations.isEmpty(), w.server.violations.toString())
        val before = phone.writeAttempts
        w.run(60_000)
        assertTrue(phone.writeAttempts - before <= 6, "Refused intents must not be retried for ever")
    }

    @Test
    fun aRefusedIntentFallsBackToOwnershipOnReconnect() = runTest {
        val (w, phone, others) = threeDevices()
        val (laptop, desktop) = others
        w.devices.forEach { it.intentRefused = true }
        laptop.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        w.dropOff(phone)
        desktop.engine.transferTo("desktop-1")
        w.run(20_000)
        phone.user(ConnectIntent.Load(songs(2, "new"), 0))
        w.run(5_000)
        phone.reconnect()
        w.run(35_000)
        assertEquals("desktop-1", w.server.snapshot().session?.ownerId)
        assertFalse(phone.player.playing)
    }

    @Test
    fun theIntentCostsNothingUntilSomeoneActsAndOneWritePerAction() = runTest {
        val (w, phone, others) = threeDevices()
        phone.user(ConnectIntent.Load(songs(40), 0))
        w.run(5_000)
        val writes = w.server.intentWrites
        val reads = w.server.intentReads
        w.run(3_000_000)
        assertEquals(writes, w.server.intentWrites, "Playing costs no intent writes")
        assertEquals(reads, w.server.intentReads, "Nothing polls the intent")
        phone.user(ConnectIntent.Next)
        w.run(2_000)
        assertEquals(writes + 1, w.server.intentWrites)
        others.first.user(ConnectIntent.Next)
        w.run(2_000)
        assertEquals(writes + 2, w.server.intentWrites)
        assertEquals(reads, w.server.intentReads, "Only a real conflict reads it")
    }

    @Test
    fun aConflictReadsTheIntentOnce() = runTest {
        val (w, phone, others) = threeDevices()
        others.first.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        w.dropOff(phone)
        phone.user(ConnectIntent.Load(songs(2, "new"), 0))
        w.run(5_000)
        val reads = w.server.intentReads
        phone.reconnect()
        w.run(35_000)
        assertEquals(reads + 1, w.server.intentReads)
    }

    @Test
    fun aPendingOfflineTapSurvivesAnEngineRestartWhileTheMusicPlays() = runTest {
        val (w, phone, others) = threeDevices()
        val (laptop, desktop) = others
        laptop.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        w.dropOff(phone)
        desktop.engine.transferTo("desktop-1")
        w.run(60_000)
        phone.engine.stop(signOut = false)
        val store = MemoryControlStore()
        val first = w.restarted(phone, store)
        first.offline = true
        first.start()
        w.run(1_000)
        first.user(ConnectIntent.Load(songs(2, "new"), 0))
        w.run(5_000)
        assertNotNull(store.saved)
        first.engine.stop(signOut = false)
        val second = w.restarted(first, store)
        second.start()
        w.run(40_000)
        assertEquals("phone-1", w.server.snapshot().session?.ownerId, "The newer tap must not be forgotten")
        assertTrue(second.player.playing)
        assertEquals("new1", second.player.track?.id)
        assertFalse(desktop.player.playing)
        assertNull(store.saved, "Settled controls aren't kept")
    }

    @Test
    fun withoutASavedControlARestartedDeviceStopsForThePlayingOutput() = runTest {
        val (w, phone, others) = threeDevices()
        others.first.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        w.dropOff(phone)
        phone.user(ConnectIntent.Load(songs(2, "new"), 0))
        w.run(5_000)
        phone.engine.stop(signOut = false)
        val restarted = w.restarted(phone, null)
        restarted.start()
        w.run(10_000)
        assertFalse(restarted.player.playing, "This is what saving the control prevents")
    }

    @Test
    fun aSavedControlNeverStartsOrMovesAudioOnASilentDevice() = runTest {
        val (w, phone, others) = threeDevices()
        val laptop = others.first
        laptop.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        val store = MemoryControlStore()
        store.saved = ConnectSavedControl(1L, 1L, w.test.testScheduler.currentTime, w.test.testScheduler.currentTime)
        phone.player.pause()
        phone.engine.stop(signOut = false)
        val restarted = w.restarted(phone, store)
        restarted.start()
        w.run(10_000)
        assertFalse(restarted.player.playing)
        assertTrue(laptop.player.playing)
        assertEquals("laptop-1", w.server.snapshot().session?.ownerId)
        assertNull(store.saved, "An action that can't apply is forgotten")
    }

    @Test
    fun anExpiredSavedControlIsForgotten() = runTest {
        val (w, phone, others) = threeDevices()
        val laptop = others.first
        laptop.user(ConnectIntent.Load(songs(3), 0))
        w.run(5_000)
        phone.player.load(songs(1, "here"), 0, 0L, true, false, RepeatMode.OFF, null)
        val store = MemoryControlStore()
        store.saved = ConnectSavedControl(1L, 1L, 0L, phone.localNow() - ConnectEngine.SAVED_CONTROL_KEEP_MS - 1)
        phone.engine.stop(signOut = false)
        val restarted = w.restarted(phone, store)
        restarted.start()
        w.run(5_000)
        assertFalse(restarted.player.playing)
        assertEquals("laptop-1", w.server.snapshot().session?.ownerId)
    }

    @Test
    fun outputThatLostItsConnectionDoesNotReclaimPlayback() = runTest {
        val (w, phone, others) = threeDevices()
        val (laptop, desktop) = others
        phone.user(ConnectIntent.Load(songs(3), 0))
        w.run(10_000)
        phone.offline = true // keeps playing offline (downloads), but can't renew its lease
        w.run(100_000)
        assertNull(laptop.engine.ui.value.remote, "An expired output defaults to this device")
        assertTrue(laptop.engine.ui.value.output?.isThis == true)
        assertEquals("song1", laptop.player.track?.id)
        assertFalse(laptop.player.playing)
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
        assertEquals("desktop-1", others.first.engine.ui.value.output?.deviceId)
    }
}
