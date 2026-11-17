package com.auralis.music.data.sync

import com.auralis.music.domain.model.Playlist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The account library endpoint for one device: discovery, the sync calls and the socket that says
 * when another device changed something. [baseUrl] is blank in builds where the rollout is off,
 * and then nothing here touches the network: every account stays on Firestore.
 */
class LibraryCloudClient(
    baseUrl: String,
    private val deviceId: String,
    private val store: LibraryCloudStore,
    client: OkHttpClient,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /**
     * A Firebase ID token for [uid], refreshed when asked. Null when that account is not the one
     * signed in: the server routes by the token, so a sync for one account must never carry
     * another account's token.
     */
    private val token: suspend (uid: String, refresh: Boolean) -> String?,
    /** False when the build enables the account sync service for other topics but not the library. */
    private val libraryEnabled: Boolean = true
) {
    private val base = baseUrl.trim().trimEnd('/')
    /** Whether the library may use the service (otherwise every account stays on Firestore). */
    val enabled: Boolean get() = base.isNotEmpty() && libraryEnabled
    /** Whether the account sync service is reachable at all (settings and later topics). */
    val serviceConfigured: Boolean get() = base.isNotEmpty()

    /** One frame to [uid]'s account sync service and its answer, for topics other than the library. */
    suspend fun call(uid: String, frame: JSONObject): JSONObject {
        check(serviceConfigured) { "The account sync service is not configured" }
        return transport(uid).call(frame)
    }

    private val http = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
    private val socketHttp = client.newBuilder().pingInterval(45, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val device = URLEncoder.encode(deviceId, "UTF-8")

    private suspend fun request(uid: String, build: (Request.Builder) -> Request.Builder): Pair<Int, String> = withContext(Dispatchers.IO) {
        var refresh = false
        while (true) {
            val bearer = token(uid, refresh) ?: throw IOException("Not signed in")
            val request = build(Request.Builder()).header("Authorization", "Bearer $bearer").build()
            val (status, body) = http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
            // One retry with a fresh token: the cached one may have just expired.
            if (status == 401 && !refresh) { refresh = true; continue }
            return@withContext status to body
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private fun transport(uid: String) = LibraryTransport { frame ->
        val (status, body) = request(uid) { it.url("$base/v1/library/rpc?device=$device")
            .post(frame.toString().toRequestBody("application/json; charset=utf-8".toMediaType())) }
        if (status != 200) throw IOException("Library server answered HTTP $status")
        runCatching { JSONObject(body) }.getOrElse { throw IOException("Library server sent an unreadable answer") }
    }

    /** One engine per account, so a mirror held in memory is never another account's. */
    private val engines = java.util.concurrent.ConcurrentHashMap<String, LibraryCloudSync>()
    private fun engine(uid: String) = engines.getOrPut(uid) { LibraryCloudSync(transport(uid), store, now) }

    /** What discovery says now, or null when it can't be reached. Never guessed. */
    private suspend fun discover(uid: String): AuthorityAnswer? = try {
        val (status, body) = request(uid) { it.url("$base/v1/library/authority").get() }
        if (status != 200) null else JSONObject(body).let { json ->
            LibraryAuthority.of(json.optString("state"))?.let { AuthorityAnswer(it, json.optInt("epoch"), now()) }
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    fun cached(uid: String): AuthorityAnswer? = AuthorityAnswer.decode(store.read(uid, AUTHORITY))

    /**
     * Where [uid]'s library syncs right now. Asks discovery when [check] or when nothing recent is
     * known; otherwise answers from the stored answer without a request.
     */
    suspend fun route(uid: String, check: Boolean = false): LibraryRoute {
        if (!enabled) return LibraryRoute.Firestore
        val known = cached(uid)
        // A move takes seconds, so "migrating" is asked about again on every attempt.
        val recent = known != null && known.state != LibraryAuthority.MIGRATING && now() - known.checkedAt in 0 until RECHECK_MS
        if (!check && recent) return LibraryAuthorityPolicy.route(true, known, reachable = false, now = now())
        val fresh = discover(uid)
        val answer = LibraryAuthorityPolicy.remember(known, fresh)
        if (fresh != null && answer === fresh) store.write(uid, AUTHORITY, fresh.encode())
        if (answer != known) log("Library authority: ${answer?.state} epoch ${answer?.epoch}")
        return LibraryAuthorityPolicy.route(true, answer, reachable = fresh != null && answer === fresh, now = now())
    }

    /** The server said the account moved: forget how fresh the answer was, keep what it said. */
    fun authorityChanged(uid: String, change: LibraryAuthorityChanged? = null) {
        val known = cached(uid)
        val state = change?.state
        val next = when {
            state != null && (known == null || change.epoch >= known.epoch) -> AuthorityAnswer(state, change.epoch, 0L)
            known != null -> known.copy(checkedAt = 0L)
            else -> return
        }
        // Once an account was seen on Cloudflare, an unconfirmed answer never moves it back.
        if (known?.state == LibraryAuthority.CLOUDFLARE && next.state == LibraryAuthority.LEGACY && next.epoch <= known.epoch) return
        store.write(uid, AUTHORITY, next.encode())
    }

    suspend fun sync(
        uid: String, epoch: Int, baseline: SyncBaseline?, protected: Set<String>,
        local: suspend () -> SyncedLibrary, duplicates: (SyncedLibrary) -> (Playlist) -> Boolean = { { false } }
    ): LibraryCloudSync.Result = try {
        engine(uid).sync(uid, epoch, baseline, protected, local, duplicates)
    } catch (e: LibraryAuthorityChanged) {
        authorityChanged(uid, e)
        throw e
    }

    /**
     * Account deletion: removes any library held for [uid] on the library service, whatever state
     * the account is in there (including an archive left by a rollback), and this device's files for
     * it. Asks the service directly rather than trusting a cached answer, and fails when it can't be
     * reached, so the caller stops before deleting the sign-in and can try again. Erasing twice is
     * fine, so a deletion that failed later can be retried.
     */
    suspend fun erase(uid: String) {
        // Settings/listening history can use this service before the library is migrated.
        // Account deletion must erase those topics even when libraryEnabled is false.
        if (!serviceConfigured) return
        val reply = transport(uid).call(JSONObject().put("t", "erase").put("v", LibraryCloudSync.PROTOCOL))
        if (reply.optString("t") != "erased") throw IOException("The library service did not delete your library. Try again.")
        stopAll()
        engines.remove(uid)
        for (name in listOf("library-mirror", "library-outbox", AUTHORITY)) store.write(uid, name, null)
    }

    // ---- change notifications ------------------------------------------------------------------

    private var watching: Job? = null
    private var watchedUid: String? = null
    @Volatile private var socket: WebSocket? = null
    /**
     * What each part of the app wants from the account's one socket, by name ("library",
     * "settings"). Each listener gets a pushed frame for its topic, or null after a reconnect,
     * when changes may have been missed.
     */
    private val listeners = java.util.concurrent.ConcurrentHashMap<String, suspend (JSONObject?) -> Unit>()

    /**
     * Keeps a socket open for [uid] and calls [onChange] when another device changed the library
     * or the account's authority moved, and each time the connection comes back (changes may have
     * been missed). Being connected is the only keepalive: nothing is polled.
     */
    fun watch(uid: String, onChange: suspend () -> Unit) {
        if (!enabled) return
        listen(uid, LIBRARY) { onChange() }
    }

    /** One socket per account, shared by every topic. Replacing [uid] drops the previous account's listeners. */
    @Synchronized fun listen(uid: String, name: String, listener: suspend (JSONObject?) -> Unit) {
        if (!serviceConfigured) return
        if (watchedUid != uid) stopAll()
        val addedTopic = listeners.put(name, listener) == null
        if (watching?.isActive == true) {
            // The shared socket may have opened for another topic before this listener existed.
            // Its earlier onOpen callback cannot catch this topic up; do it on registration.
            if (addedTopic) scope.launch { runCatching { listener(null) } }
            return
        }
        watchedUid = uid
        watching = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive) {
                // Every time the socket opens, the first time included, each listener catches up:
                // a change made between this device's last pull and the socket opening would
                // otherwise be missed until the next local edit. Catching up when nothing changed
                // reads no stored rows on the service.
                val opened = runCatching { connectOnce(uid, onOpen = {
                    listeners.values.forEach { l -> scope.launch { runCatching { l(null) } } }
                }) }.getOrElse { if (it is CancellationException) throw it; false }
                attempt = if (opened) 0 else attempt + 1
                // Backoff with jitter, so devices that lost the server together don't return together.
                val ceiling = (1_000L shl attempt.coerceAtMost(9)).coerceAtMost(MAX_BACKOFF_MS)
                delay(ceiling / 2 + Random.nextLong(ceiling / 2 + 1))
            }
        }
    }

    /** This part of the app no longer needs the socket; it closes when nothing does. */
    @Synchronized fun stopWatching(name: String = LIBRARY) {
        listeners.remove(name)
        if (listeners.isEmpty()) closeSocket()
    }

    /** Sign-out, account switch or deletion: nothing listens any more. */
    @Synchronized fun stopAll() { listeners.clear(); closeSocket() }

    private fun closeSocket() {
        watching?.cancel(); watching = null; watchedUid = null
        socket?.cancel(); socket = null
    }

    /** One connection, until it ends. Returns whether it was ever open. */
    private suspend fun connectOnce(uid: String, onOpen: () -> Unit): Boolean {
        val bearer = token(uid, true) ?: return false
        val ended = CompletableDeferred<Boolean>()
        var opened = false
        val url = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/v1/library?device=$device"
        val ws = socketHttp.newWebSocket(Request.Builder().url(url).header("Authorization", "Bearer $bearer").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened = true; log("Account sync socket open"); onOpen() }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
                val topic = frame.optString("topic").ifEmpty { LIBRARY }
                when (frame.optString("t")) {
                    "changed" -> scope.launch {
                        if (topic == LIBRARY) runCatching { engine(uid).accept(uid, frame) }
                        listeners[topic]?.let { runCatching { it(frame) } }
                    }
                    "authority" -> scope.launch {
                        authorityChanged(uid, LibraryAuthorityChanged(LibraryAuthority.of(frame.optString("state")), frame.optInt("epoch")))
                        listeners[LIBRARY]?.let { runCatching { it(frame) } }
                    }
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ended.complete(opened) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { ended.complete(opened) }
        })
        socket = ws
        return try { ended.await() } finally { ws.cancel(); log("Account sync socket closed") }
    }

    companion object {
        private const val AUTHORITY = "library-authority"
        const val LIBRARY = "library"
        /** A stored answer is used without asking again for this long (a start or a refusal always asks). */
        private const val RECHECK_MS = 15 * 60_000L
        private const val MAX_BACKOFF_MS = 5 * 60_000L
    }
}
