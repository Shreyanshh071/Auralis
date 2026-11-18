package com.auralis.music.data.together

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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

/**
 * One Listen Together room on the Cloudflare room server (connect-server/src/together.js), over one
 * WebSocket. It holds the same room, members and song requests Firestore held, as plain maps, and the
 * server pushes every change, so nothing here polls. The connection retries with backoff until
 * [stop], or until the room is closed for good.
 */
class TogetherRoomSocket(
    /** wss://…/v1/together */
    private val url: String,
    val roomCode: String,
    private val scope: CoroutineScope,
    client: OkHttpClient,
    private val localNow: () -> Long = System::currentTimeMillis,
    private val ackTimeoutMs: Long = 5_000L,
    private val maxBackoffMs: Long = 30_000L,
    private val log: (String) -> Unit = {},
    /** A Firebase ID token (anonymous is fine), refreshed when asked to; null while unavailable. */
    private val token: suspend (refresh: Boolean) -> String?
) {
    /** What the server last said. [Snapshot.loaded] stays false until the first state arrives. */
    data class Snapshot(
        val loaded: Boolean = false,
        val room: Map<String, Any?>? = null,
        val members: List<Map<String, Any?>>? = null,
        val recs: List<Map<String, Any?>>? = null,
        /** The room is gone: closed by its host, or never existed when we arrived. */
        val ended: Boolean = false
    )

    /** A reply's server time and when it was sent and answered here, for clock offsets. */
    data class Ack(val serverNow: Long, val sentAt: Long, val ackAt: Long, val reply: JSONObject)

    class Refused(val code: String) : IOException("Refused: $code")

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val client = client.newBuilder().pingInterval(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val nextId = AtomicLong(1)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false
    private var job: Job? = null

    fun start() {
        if (job == null) job = scope.launch { connectLoop() }
    }

    /** The network changed: try now instead of finishing a backoff wait. */
    fun nudge() { wake.trySend(Unit) }

    fun stop() {
        job?.cancel()
        job = null
        socket?.close(1000, null)
    }

    /** Waits until the first state arrives, at most [timeoutMs]. False when it didn't. */
    suspend fun awaitLoaded(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) { state.first { it.loaded } } != null

    private suspend fun connectLoop() {
        var failures = 0
        var refresh = false
        while (scope.isActive) {
            val close = runOnce(refresh)
            refresh = close.code == CLOSE_EXPIRED || close.code == UNAUTHORIZED
            if (close.opened) failures = 0
            when (close.code) {
                CLOSE_ROOM_CLOSED -> { _state.value = _state.value.copy(loaded = true, room = null, ended = true); return }
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
            withTimeoutOrNull(ackTimeoutMs * 2) { token(refresh) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return Closed(UNAUTHORIZED, opened = false)
        val closed = CompletableDeferred<Closed>()
        val request = Request.Builder().url("$url?room=$roomCode").header("Authorization", "Bearer $credential").build()
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
            log("Room $roomCode socket closed (${result.code}, opened=${result.opened})")
            return result
        } finally {
            ws.cancel()
            ready = false
            socket = null
            _connected.value = false
            pending.values.toList().forEach { it.completeExceptionally(IOException("Connection closed")) }
            pending.clear()
        }
    }

    /** True for the first state of a connection. */
    private fun receive(text: String): Boolean {
        val frame = try { JSONObject(text) } catch (e: Exception) { return false }
        val waiter = if (frame.has("r") && !frame.isNull("r")) pending.remove(frame.getLong("r")) else null
        var first = false
        val now = _state.value
        when (frame.optString("t")) {
            "state" -> {
                first = !ready
                ready = true
                _connected.value = true
                // A room that vanished while we were away ended; one that never existed did not.
                _state.value = now.copy(loaded = true, room = roomOf(frame), members = listOf(frame, "members"),
                    recs = listOf(frame, "recs"), ended = frame.isNull("room") && (now.room != null || now.ended))
            }
            "room" -> {
                val room = roomOf(frame)
                _state.value = now.copy(loaded = true, room = room, ended = room == null)
            }
            "members" -> _state.value = now.copy(members = listOf(frame, "members"))
            "recs" -> _state.value = now.copy(recs = listOf(frame, "recs"))
            "ok" -> frame.optJSONObject("state")?.let { s ->
                _state.value = now.copy(loaded = true, room = roomOf(s), members = listOf(s, "members"), recs = listOf(s, "recs"), ended = s.isNull("room"))
            }
        }
        waiter?.complete(frame)
        return first
    }

    private fun roomOf(frame: JSONObject): Map<String, Any?>? = frame.optJSONObject("room")?.let(::mapOf)

    private fun listOf(frame: JSONObject, key: String): List<Map<String, Any?>>? =
        frame.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::mapOf) } }

    private suspend fun call(frame: JSONObject): Ack {
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
            // A socket that stops answering is dead, whatever TCP thinks: reconnect rather than wait.
            ws.cancel()
            throw IOException("No answer")
        }
        if (answer.optString("t") == "err") throw Refused(answer.optString("code"))
        return Ack(answer.optLong("now", 0L), sent, localNow(), answer)
    }

    suspend fun create(room: Map<String, Any?>, member: Map<String, Any?>): Ack =
        call(JSONObject().put("t", "create").put("room", jsonOf(room)).put("member", jsonOf(member)))

    suspend fun join(member: Map<String, Any?>): Ack = call(JSONObject().put("t", "join").put("member", jsonOf(member)))

    /** Host only: merges [set] into the room. */
    suspend fun updateRoom(set: Map<String, Any?>): Ack = call(JSONObject().put("t", "room").put("set", jsonOf(set)))

    /** Merges [set] into this member's record; `network` merges one level deeper. */
    suspend fun updateMember(set: Map<String, Any?>): Ack = call(JSONObject().put("t", "member").put("set", jsonOf(set)))

    /** Leaves the room; the host leaving closes it. The connection stops either way. */
    suspend fun leave() {
        try { call(JSONObject().put("t", "leave")) } finally { stop() }
    }

    suspend fun recommend(rec: Map<String, Any?>): String = call(JSONObject().put("t", "rec").put("rec", jsonOf(rec))).reply.getString("id")
    suspend fun upvote(id: String) { call(JSONObject().put("t", "upvote").put("id", id)) }
    suspend fun setRecStatus(id: String, status: String) { call(JSONObject().put("t", "rec-status").put("id", id).put("status", status)) }
    suspend fun deleteRec(id: String) { call(JSONObject().put("t", "rec-delete").put("id", id)) }

    companion object {
        const val CLOSE_ROOM_CLOSED = 4000
        const val CLOSE_EXPIRED = 4401
        const val CLOSE_REPLACED = 4409
        const val CLOSE_TOO_LARGE = 4413
        const val CLOSE_TOO_FAST = 4429
        const val UNAUTHORIZED = -401
        private const val BASE_BACKOFF_MS = 500L
        private const val LONG_PAUSE_MS = 15_000L

        fun backoffCeiling(failures: Int, maxMs: Long): Long =
            (BASE_BACKOFF_MS shl failures.coerceIn(0, 10)).coerceAtMost(maxMs).coerceAtLeast(BASE_BACKOFF_MS)

        fun jsonOf(map: Map<String, Any?>): JSONObject {
            val json = JSONObject()
            for ((k, v) in map) json.put(k, jsonValue(v))
            return json
        }

        private fun jsonValue(v: Any?): Any = when (v) {
            null -> JSONObject.NULL
            is Map<*, *> -> jsonOf(v.entries.associate { (k, value) -> k.toString() to value })
            is Iterable<*> -> JSONArray().also { a -> v.forEach { a.put(jsonValue(it)) } }
            is Array<*> -> JSONArray().also { a -> v.forEach { a.put(jsonValue(it)) } }
            is Float -> v.toDouble()
            else -> v
        }

        fun mapOf(json: JSONObject): Map<String, Any?> = json.keys().asSequence().associateWith { valueOf(json.get(it)) }

        private fun valueOf(v: Any?): Any? = when (v) {
            JSONObject.NULL -> null
            is JSONObject -> mapOf(v)
            is JSONArray -> (0 until v.length()).map { valueOf(v.get(it)) }
            is Int -> v.toLong()
            else -> v
        }
    }
}
