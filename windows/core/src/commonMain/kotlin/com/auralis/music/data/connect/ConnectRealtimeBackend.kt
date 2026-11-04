package com.auralis.music.data.connect

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Where a realtime connection delivers what the server pushes; the same calls Firestore's listeners make. */
interface ConnectRealtimeSink {
    fun onSnapshot(snapshot: ConnectSnapshot)
    fun onDevices(devices: List<ConnectDevice>)
    fun onConnectionLost(message: String?)
}

/**
 * [ConnectBackend] over one WebSocket, so the engine and every rule in it stay as they are. A write
 * is the same compare-and-swap Firestore's transaction ran; the server refuses a stale base and
 * hands back the newer state. Being connected is the heartbeat, so nothing is sent to stay present.
 * Nothing here can hold up playback: with no connection every call fails at once, and a call never
 * waits longer than [ackTimeoutMs]. Connecting happens on [scope], away from any player thread.
 */
class ConnectRealtimeBackend(
    private val url: String,
    private val deviceId: String,
    private val clock: ServerClock,
    private val scope: CoroutineScope,
    private val sink: ConnectRealtimeSink,
    client: OkHttpClient,
    private val localNow: () -> Long = System::currentTimeMillis,
    private val pingMs: Long = 45_000L,
    private val ackTimeoutMs: Long = 3_000L,
    private val maxBackoffMs: Long = 60_000L,
    /** Connection changes and device lists, for the test builds' logs. */
    private val log: (String) -> Unit = {},
    /** A Firebase ID token, refreshed when asked to; null while signed out or unreachable. */
    private val token: suspend (refresh: Boolean) -> String?
) : ConnectBackend {
    override val controlPatienceMs: Long get() = CONTROL_PATIENCE_MS

    private val client = client.newBuilder().pingInterval(pingMs, TimeUnit.MILLISECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val nextId = AtomicLong(1)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var latest = ConnectSnapshot()
    private val snapshotLock = Any()

    private fun remember(snapshot: ConnectSnapshot): ConnectSnapshot = synchronized(snapshotLock) {
        val known = latest.session
        val incoming = snapshot.session
        if (known == null || (incoming != null && incoming.version >= known.version && incoming.epoch >= known.epoch)) {
            latest = snapshot
        }
        latest
    }
    @Volatile private var wantedDevice: String? = null
    @Volatile private var saidDevice: String? = null
    /** The last device list, and which of those devices had a connection when it was sent. */
    @Volatile private var devices: List<ConnectDevice> = emptyList()
    @Volatile private var liveIds: Set<String> = emptySet()
    private var job: Job? = null

    private class Refused(val code: String) : IOException("Refused: $code")

    fun start() {
        job = scope.launch { connectLoop() }
    }

    /** The network changed: try now instead of finishing a backoff wait. */
    fun nudge() { wake.trySend(Unit) }

    fun stop() {
        job?.cancel()
        socket?.close(1000, null)
    }

    private suspend fun connectLoop() {
        var failures = 0
        var refresh = false
        while (scope.isActive) {
            val close = runOnce(refresh)
            refresh = close.code == CLOSE_EXPIRED
            if (close.opened) failures = 0
            when (close.code) {
                CLOSE_WIPED -> return
                CLOSE_EXPIRED -> Unit
                CLOSE_REPLACED, CLOSE_TOO_FAST, CLOSE_TOO_LARGE, UNAUTHORIZED -> pause(LONG_PAUSE_MS)
                else -> {
                    failures++
                    pause(Random.nextLong(BASE_BACKOFF_MS, backoffCeiling(failures, maxBackoffMs) + 1))
                }
            }
        }
    }

    private suspend fun pause(ms: Long) {
        withTimeoutOrNull(ms) { wake.receive() }
    }

    private class Closed(val code: Int, val opened: Boolean)

    private suspend fun runOnce(refresh: Boolean): Closed {
        val credential = try {
            withTimeoutOrNull(ackTimeoutMs * 3) { token(refresh) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return Closed(UNAUTHORIZED, opened = false)
        val closed = CompletableDeferred<Closed>()
        val request = Request.Builder().url("$url?device=$deviceId").header("Authorization", "Bearer $credential").build()
        var opened = false
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (receive(text)) opened = true
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code.coerceIn(1000, 4999).takeUnless { it in 1004..1006 } ?: 1000, null)
                closed.complete(Closed(code, opened))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Closed(code, opened)) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.complete(Closed(if (response?.code == 401) UNAUTHORIZED else 0, opened))
            }
        })
        socket = ws
        try {
            val result = closed.await()
            log("Socket closed (${result.code}, opened=${result.opened})")
            return result
        } finally {
            lost(ws, "Connection closed")
        }
    }

    private fun lost(ws: WebSocket, message: String) {
        ws.cancel()
        ready = false
        socket = null
        saidDevice = null
        // Everything in flight fails now rather than waiting out its timeout.
        pending.values.toList().forEach { it.completeExceptionally(IOException(message)) }
        pending.clear()
        sink.onConnectionLost(message)
    }

    /** True for the first state of a connection. */
    private fun receive(text: String): Boolean {
        val frame = try { JSONObject(text) } catch (e: Exception) { return false }
        val waiter = if (frame.has("r") && !frame.isNull("r")) pending.remove(frame.getLong("r")) else null
        var first = false
        when (frame.optString("t")) {
            "state" -> {
                val snapshot = remember(snapshotOf(frame, latest))
                first = !ready
                ready = true
                if (first) log("Socket ready")
                sink.onDevices(devicesOf(frame))
                sink.onSnapshot(snapshot)
                // The engine's first heartbeat comes before the socket: being here is the announcement.
                if (first) scope.launch { runCatching { sayDevice() } }
            }
            "session" -> {
                sink.onSnapshot(remember(snapshotOf(frame, latest)))
            }
            "devices" -> sink.onDevices(devicesOf(frame))
        }
        waiter?.complete(frame)
        return first
    }

    private suspend fun call(frame: JSONObject): JSONObject {
        val ws = socket?.takeIf { ready } ?: throw IOException("Not connected")
        val id = nextId.getAndIncrement()
        val reply = CompletableDeferred<JSONObject>()
        pending[id] = reply
        frame.put("r", id)
        val sent = localNow()
        if (!ws.send(frame.toString())) {
            pending.remove(id)
            throw IOException("Not connected")
        }
        val answer = withTimeoutOrNull(ackTimeoutMs) { reply.await() }
        if (answer == null) {
            pending.remove(id)
            // A socket that stops answering is dead, whatever TCP thinks: reconnect rather than wait for pings.
            ws.cancel()
            throw IOException("No answer")
        }
        if (answer.has("now")) clock.sample(answer.getLong("now"), sent, localNow())
        if (answer.optString("t") == "err") throw Refused(answer.optString("code"))
        return answer
    }

    override suspend fun fetch(): ConnectSnapshot {
        if (!ready) throw IOException("Not connected")
        return latest
    }

    override suspend fun update(block: (ConnectSnapshot) -> ConnectWrite?): ConnectSnapshot? {
        repeat(MAX_CONFLICTS) {
            val cur = latest
            val w = block(cur) ?: return null
            val frame = JSONObject().put("t", "update").put("base", cur.session?.version ?: 0L)
                .put("s", jsonOf(ConnectCodec.encodeSession(w.session)))
            w.queue?.let { frame.put("q", jsonOf(ConnectCodec.encodeQueue(it))) }
            val answer = call(frame)
            if (answer.optString("t") == "conflict") {
                remember(snapshotOf(answer, cur))
            } else {
                val written = ConnectSnapshot(w.session.copy(updatedAtMs = answer.optLong("now")), w.queue ?: cur.queue)
                remember(written)
                return written
            }
        }
        throw IOException("The session kept changing")
    }

    override suspend fun heartbeat(device: ConnectDevice) {
        wantedDevice = jsonOf(ConnectCodec.encodeDevice(device)).toString()
        // Offline this isn't a failure: the device is announced as soon as there is a connection.
        if (!ready) return
        sayDevice()
        // The server lists devices only when one joins or leaves, so a connected device's "seen" time
        // would age past the engine's online window (the phone lost the playing PC after 5 quiet
        // minutes). Every list arrives while this socket is open, so its live devices are still live.
        val live = liveIds
        if (live.isEmpty()) return
        log("Refreshed ${live.size} live device(s)")
        sink.onDevices(devices.map { if (it.id in live) it.copy(lastSeenMs = clock.now()) else it })
    }

    private suspend fun sayDevice() {
        val text = wantedDevice ?: return
        // Presence is the connection; only a real change, or a stale clock, is worth a message.
        if (text == saidDevice && clock.fresh()) return
        call(JSONObject().put("t", "hello").put("d", JSONObject(text)))
        saidDevice = text
    }

    override suspend fun removeDevice(id: String) {
        call(JSONObject().put("t", "remove").put("id", id))
    }

    override suspend fun readIntent(): ConnectPlayIntent? {
        val i = call(JSONObject().put("t", "get-intent")).optJSONObject("i") ?: return null
        return ConnectCodec.decodeIntent(mapOf(i))
    }

    override suspend fun writeIntent(intent: ConnectPlayIntent) {
        call(JSONObject().put("t", "intent").put("i", jsonOf(ConnectCodec.encodeIntent(intent))))
    }

    private fun snapshotOf(frame: JSONObject, previous: ConnectSnapshot): ConnectSnapshot {
        val session = frame.optJSONObject("s")?.let { ConnectCodec.decodeSession(mapOf(it)) }
            ?: if (frame.has("s")) null else previous.session
        val queue = frame.optJSONObject("q")?.let { ConnectCodec.decodeQueue(mapOf(it)) } ?: previous.queue
        return ConnectSnapshot(session, queue?.takeIf { it.rev == session?.queueRev })
    }

    private fun devicesOf(frame: JSONObject): List<ConnectDevice> {
        val list = frame.optJSONArray("dv") ?: return emptyList()
        val decoded = (0 until list.length()).map { ConnectCodec.decodeDevice(mapOf(list.getJSONObject(it))) }
        // The server stamps every connected device with the moment it sent the list: the newest time.
        val sentAt = decoded.maxOfOrNull { it.lastSeenMs } ?: 0L
        devices = decoded
        liveIds = decoded.filter { it.lastSeenMs == sentAt && sentAt > 0L }.mapTo(HashSet()) { it.id }
        log("Devices: " + decoded.joinToString { "${it.name}${if (it.id in liveIds) " (live)" else ""}${if (it.online) "" else " offline"}" })
        return decoded
    }

    companion object {
        /** Long enough for a healthy round trip; the server answers in milliseconds, not Firestore's seconds. */
        const val CONTROL_PATIENCE_MS = 1_500L
        private const val BASE_BACKOFF_MS = 1_000L
        private const val LONG_PAUSE_MS = 60_000L
        private const val MAX_CONFLICTS = 6
        private const val UNAUTHORIZED = 401
        private const val CLOSE_EXPIRED = 4401
        private const val CLOSE_REPLACED = 4409
        private const val CLOSE_WIPED = 4410
        private const val CLOSE_TOO_LARGE = 4413
        private const val CLOSE_TOO_FAST = 4429

        /** Full jitter draws under this, so a server coming back isn't hit by everyone at once. */
        fun backoffCeiling(failures: Int, maxMs: Long): Long =
            (BASE_BACKOFF_MS shl (failures - 1).coerceIn(0, 16)).coerceAtMost(maxMs)

        private fun jsonOf(map: Map<String, Any?>): JSONObject {
            val json = JSONObject()
            for ((k, v) in map) if (v !== ConnectServerTime) json.put(k, jsonValue(v))
            return json
        }

        private fun jsonValue(v: Any?): Any = when (v) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> if (x !== ConnectServerTime) o.put(k.toString(), jsonValue(x)) } }
            is List<*> -> JSONArray().also { a -> v.forEach { a.put(jsonValue(it)) } }
            else -> v
        }

        private fun mapOf(json: JSONObject): Map<String, Any?> = json.keys().asSequence().associateWith { valueOf(json.get(it)) }

        private fun valueOf(v: Any?): Any? = when (v) {
            null, JSONObject.NULL -> null
            is JSONObject -> mapOf(v)
            is JSONArray -> (0 until v.length()).map { valueOf(v.get(it)) }
            else -> v
        }
    }
}
