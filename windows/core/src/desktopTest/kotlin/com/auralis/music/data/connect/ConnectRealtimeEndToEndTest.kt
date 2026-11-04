package com.auralis.music.data.connect

import com.auralis.music.domain.model.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real engine and real WebSocket client against the real server, in real time. Runs when
 * AURALIS_CONNECT_PORT names a local `wrangler dev` (connect-server/test/serve.mjs starts one). A
 * proxy between them is the network: it can drop, refuse or silently stop carrying traffic.
 */
class ConnectRealtimeEndToEndTest {
    private val port = System.getenv("AURALIS_CONNECT_PORT")?.toIntOrNull()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient()
    private val uid = "e2e" + System.nanoTime()

    private class Network(val target: Int) {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val pipes = CopyOnWriteArrayList<Socket>()
        val attempts = AtomicInteger()
        @Volatile var refusing = false
        @Volatile var frozen = false
        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val inbound = try { server.accept() } catch (e: Exception) { break }
                    attempts.incrementAndGet()
                    if (refusing) { inbound.close(); continue }
                    val outbound = Socket(InetAddress.getLoopbackAddress(), target)
                    pipes += inbound; pipes += outbound
                    pump(inbound, outbound); pump(outbound, inbound)
                }
            }
        }
        private fun pump(from: Socket, to: Socket) = thread(isDaemon = true) {
            val buffer = ByteArray(16_384)
            try {
                while (true) {
                    val read = from.getInputStream().read(buffer)
                    if (read < 0) break
                    while (frozen) Thread.sleep(20)
                    to.getOutputStream().write(buffer, 0, read)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { from.close() }; runCatching { to.close() }
            }
        }
        fun drop() { pipes.forEach { runCatching { it.close() } }; pipes.clear() }
        fun outage() { refusing = true; drop() }
        fun restore() { refusing = false; frozen = false }
        fun close() { drop(); server.close() }
    }

    private inner class Device(val id: String, val network: Network, val tokens: suspend (Boolean) -> String? = { "dev:$uid" }) : ConnectRealtimeSink {
        val player = FakePlayer(System::currentTimeMillis)
        val clock = ServerClock(System::currentTimeMillis, ::elapsed)
        val snapshots = AtomicInteger()
        lateinit var engine: ConnectEngine
        val backend = ConnectRealtimeBackend("ws://127.0.0.1:${network.server.localPort}/v1/connect", id, clock, scope, this, http,
            pingMs = 500L, ackTimeoutMs = 1_000L, maxBackoffMs = 2_000L, token = tokens)

        fun elapsed() = System.nanoTime() / 1_000_000L
        override fun onSnapshot(snapshot: ConnectSnapshot) { snapshots.incrementAndGet(); engine.onSnapshot(snapshot) }
        override fun onDevices(devices: List<ConnectDevice>) = engine.onDevices(devices)
        override fun onConnectionLost(message: String?) = engine.onConnectionLost(message)

        fun start() {
            engine = ConnectEngine(scope, backend, player, ConnectDevice(id, id, platform = ConnectPlatform.ANDROID, type = ConnectDeviceType.PHONE, appVersion = "9"),
                clock, System::currentTimeMillis, ::elapsed)
            player.onChange = { engine.onLocalChanged() }
            engine.start()
            backend.start()
            scope.launch { while (isActive) { delay(250); player.advance(); engine.tick() } }
        }

        fun user(intent: ConnectIntent) {
            if (engine.intercept(intent)) return
            when (intent) {
                ConnectIntent.Play -> player.play()
                ConnectIntent.Pause -> player.pause()
                is ConnectIntent.Seek -> player.seekTo(intent.positionMs)
                ConnectIntent.Next -> player.next()
                ConnectIntent.Previous -> player.previous()
                is ConnectIntent.Load -> player.load(intent.tracks, intent.index, 0L, true, player.shuffled, player.repeatMode, intent.context)
                else -> error("unused here")
            }
        }
    }

    private val networks = mutableListOf<Network>()
    private fun network() = Network(port ?: 0).also { networks += it }
    private fun device(id: String, network: Network = network()) = Device(id, network).also { it.start() }

    @AfterTest fun cleanUp() {
        networks.forEach { it.close() }
        scope.cancel()
    }

    private fun live(block: suspend () -> Unit) {
        assumeTrue("Set AURALIS_CONNECT_PORT to run against a local server", port != null)
        runBlocking { block() }
    }

    private suspend fun until(what: String, ms: Long = 8_000L, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + ms
        while (!check()) {
            assertTrue(System.currentTimeMillis() < deadline, "Timed out waiting: $what")
            delay(20)
        }
    }

    private suspend fun twoDevices(): Pair<Device, Device> {
        val (phone, pc) = device("phone") to device("pc")
        until("both connected") { phone.engine.ui.value.devices.size == 2 && pc.engine.ui.value.devices.size == 2 }
        return phone to pc
    }

    @Test fun transportStaysIdleUntilStartedAndDoesNotReconnectAfterStop() = runBlocking {
        val network = network().also { it.refusing = true }
        val device = Device("opt-in", network)
        device.backend.nudge()
        delay(250)
        assertEquals(0, network.attempts.get())

        device.start()
        until("first connection attempt") { network.attempts.get() > 0 }
        device.backend.stop()
        val attempts = network.attempts.get()
        device.backend.nudge()
        delay(2_500)
        assertEquals(attempts, network.attempts.get())
    }

    @Test fun oneDeviceMirrorsAndControlsTheOther() = live {
        val (phone, pc) = twoDevices()
        pc.user(ConnectIntent.Load(songs(3), 0))
        assertTrue(pc.player.playing, "Local before anything else")
        until("the mirror") { phone.engine.ui.value.remote?.track?.id == "song1" }
        assertFalse(phone.player.playing)
        phone.user(ConnectIntent.Pause)
        until("the remote pause") { !pc.player.playing }
        until("the paused remote output") { phone.engine.ui.value.remote?.playing == false }
        assertEquals("pc", phone.engine.ui.value.output?.deviceId)
        phone.user(ConnectIntent.Play)
        until("the PC to resume") { pc.player.playing }
        assertFalse(phone.player.playing)
        assertEquals("pc", phone.engine.ui.value.output?.deviceId)
    }

    @Test fun remoteNextRunsExactlyOnce() = live {
        val (phone, pc) = twoDevices()
        pc.user(ConnectIntent.Load(songs(5), 0))
        until("the mirror") { phone.engine.ui.value.remote != null }
        phone.user(ConnectIntent.Next)
        until("the skip") { pc.player.track?.id == "song2" }
        delay(1_500)
        assertEquals("song2", pc.player.track?.id)
    }

    @Test fun handoffsMoveTheAudioBothWays() = live {
        val (phone, pc) = twoDevices()
        pc.user(ConnectIntent.Load(songs(3), 0))
        until("the mirror") { phone.engine.ui.value.remote != null }
        phone.engine.transferTo("phone")
        until("the handoff here") { phone.player.playing && !pc.player.playing }
        until("the mirror on the pc") { pc.engine.ui.value.remote != null }
        phone.engine.transferTo("pc")
        until("the handoff back") { pc.player.playing && !phone.player.playing }
    }

    @Test fun anOutageNeverTouchesLocalPlaybackAndEverythingRecovers() = live {
        val network = network()
        val phone = device("phone", network)
        val pc = device("pc")
        until("connected") { phone.engine.ui.value.devices.size == 2 }
        phone.user(ConnectIntent.Load(songs(3), 0))
        until("the mirror") { pc.engine.ui.value.remote != null }
        network.outage()
        until("the outage to be noticed") { !phone.engine.ui.value.connected }
        // Every control acts before user() returns: there is no suspension point on this path.
        phone.user(ConnectIntent.Next)
        assertEquals("song2", phone.player.track?.id)
        phone.user(ConnectIntent.Pause)
        assertFalse(phone.player.playing)
        phone.user(ConnectIntent.Play)
        assertTrue(phone.player.playing)
        delay(1_500)
        assertTrue(phone.player.playing, "Nothing stale may stop it")
        network.restore()
        until("reconnected") { phone.engine.ui.value.connected }
        until("the state to recover") { pc.engine.ui.value.remote?.track?.id == "song2" }
        assertTrue(phone.player.playing)
    }

    @Test fun anOfflineTapMadeAfterATakeoverWinsOnReconnect() = live {
        val network = network()
        val phone = device("phone", network)
        val pc = device("pc")
        until("connected") { phone.engine.ui.value.devices.size == 2 }
        pc.user(ConnectIntent.Load(songs(3), 0))
        until("the mirror") { phone.engine.ui.value.remote != null }
        network.outage()
        until("the outage to be noticed") { !phone.engine.ui.value.connected }
        pc.user(ConnectIntent.Next)
        delay(4_000) // beyond the tie margin
        phone.user(ConnectIntent.Load(songs(2, "new"), 0))
        assertTrue(phone.player.playing)
        network.restore()
        until("the newer tap to win", 12_000) { pc.engine.ui.value.output?.deviceId == "phone" }
        assertFalse(pc.player.playing)
        assertEquals("new1", phone.player.track?.id)
    }

    @Test fun anOfflineTapOlderThanAnotherDevicesActionYields() = live {
        val network = network()
        val phone = device("phone", network)
        val pc = device("pc")
        until("connected") { phone.engine.ui.value.devices.size == 2 }
        pc.user(ConnectIntent.Load(songs(3), 0))
        until("the mirror") { phone.engine.ui.value.remote != null }
        network.outage()
        until("the outage to be noticed") { !phone.engine.ui.value.connected }
        phone.user(ConnectIntent.Load(songs(2, "new"), 0))
        delay(4_000)
        pc.user(ConnectIntent.Next)
        delay(300)
        assertTrue(phone.player.playing, "Offline, nothing can stop it")
        network.restore()
        until("the older tap to yield", 12_000) { !phone.player.playing }
        assertTrue(pc.player.playing)
        until("the way back to appear") { phone.engine.ui.value.canPlayHere }
    }

    @Test fun aServerThatStopsAnsweringIsDetectedWithoutBlockingLocalControls() = live {
        val network = network()
        val phone = device("phone", network)
        until("connected") { phone.engine.ui.value.connected }
        phone.user(ConnectIntent.Load(songs(3), 0))
        delay(300)
        network.frozen = true
        val started = System.nanoTime()
        phone.user(ConnectIntent.Next)
        phone.user(ConnectIntent.Pause)
        phone.user(ConnectIntent.Play)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 50, "Local controls can't wait on a frozen socket")
        assertEquals("song2", phone.player.track?.id)
        assertTrue(phone.player.playing)
        until("the frozen connection to be noticed") { !phone.engine.ui.value.connected }
        network.frozen = false
        network.drop()
        until("recovery") { phone.engine.ui.value.connected }
    }

    @Test fun aSongSentToAFrozenOutputPlaysHereWithinThePatience() = live {
        val network = network()
        val phone = device("phone", network)
        val pc = device("pc")
        until("connected") { phone.engine.ui.value.devices.size == 2 }
        pc.user(ConnectIntent.Load(songs(3), 0))
        until("the mirror") { phone.engine.ui.value.remote != null }
        network.frozen = true
        val started = System.currentTimeMillis()
        phone.user(ConnectIntent.Load(songs(2, "new"), 0))
        until("the song to play here", 3_000) { phone.player.playing }
        val waited = System.currentTimeMillis() - started
        assertTrue(waited < 2_000, "Waited ${waited}ms, against Firestore's 4 s")
    }

    @Test fun reconnectingBacksOffInsteadOfHammeringTheServer() = live {
        val network = network()
        val phone = device("phone", network)
        until("connected") { phone.engine.ui.value.connected }
        network.outage()
        val before = network.attempts.get()
        delay(6_000)
        val tries = network.attempts.get() - before
        assertTrue(tries in 2..8, "$tries tries in six seconds")
        network.restore()
        phone.backend.nudge()
        until("a network change to reconnect at once", 2_000) { phone.engine.ui.value.connected }
    }

    @Test fun switchingNetworksRecoversByItself() = live {
        val network = network()
        val phone = device("phone", network)
        val pc = device("pc")
        until("connected") { phone.engine.ui.value.devices.size == 2 }
        phone.user(ConnectIntent.Load(songs(3), 0))
        until("the mirror") { pc.engine.ui.value.remote != null }
        repeat(3) {
            network.drop()
            delay(50)
            phone.backend.nudge()
            until("reconnect ${it + 1}") { phone.engine.ui.value.connected }
        }
        assertTrue(phone.player.playing)
        phone.user(ConnectIntent.Pause)
        until("control after the changes") { pc.engine.ui.value.output?.playing != true }
    }

    @Test fun aRefusedTokenLeavesLocalPlaybackAloneAndKeepsTrying() = live {
        val phone = Device("phone", network(), tokens = { "forged" }).also { it.start() }
        phone.user(ConnectIntent.Load(songs(2), 0))
        assertTrue(phone.player.playing)
        delay(1_500)
        assertFalse(phone.engine.ui.value.connected)
        assertTrue(phone.player.playing)
        phone.user(ConnectIntent.Next)
        assertEquals("song2", phone.player.track?.id)
    }

    @Test fun anExpiredTokenIsRefreshedOnReconnect() = live {
        val refreshed = AtomicInteger()
        val phone = Device("phone", network(), tokens = { refresh ->
            if (refresh) { refreshed.incrementAndGet(); "dev:$uid" } else "dev:$uid:200"
        }).also { it.start() }
        until("connected") { phone.engine.ui.value.connected }
        delay(400)
        phone.user(ConnectIntent.Load(songs(2), 0))
        until("a fresh token") { refreshed.get() > 0 }
        until("reconnected") { phone.engine.ui.value.connected }
    }

    @Test fun routineTrafficIsQuietAndPresenceCostsNoMessages() = live {
        val phone = device("phone")
        until("connected") { phone.engine.ui.value.connected }
        phone.user(ConnectIntent.Load(songs(3), 0))
        delay(1_000)
        val before = phone.snapshots.get()
        delay(3_000)
        assertEquals(before, phone.snapshots.get(), "Nobody else is here to be told anything")
    }

    @Test fun signedOutDevicesLeaveNothingBehind() = live {
        val (phone, pc) = twoDevices()
        phone.engine.stop(signOut = true)
        phone.backend.stop()
        until("the device to go") { pc.engine.ui.value.devices.none { it.device.id == "phone" } }
        assertNull(pc.engine.ui.value.remote)
        assertNotNull(pc.engine.ui.value.me)
    }
}

class ConnectRealtimeBackoffTest {
    @Test fun backoffGrowsToItsCeiling() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 60_000L, 60_000L),
            listOf(1, 2, 3, 4, 7, 40).map { ConnectRealtimeBackend.backoffCeiling(it, 60_000L) })
    }
}
