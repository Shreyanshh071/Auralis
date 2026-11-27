package com.zemer.cipher

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The desktop stand-in for Android's WebView in zemer-cipher: one unseen Chromium (the browser the
 * user signs in to YouTube with, on a throwaway profile of its own) driven over the DevTools
 * protocol. Each [Page] is a tab whose own requests are answered from memory ([Page.serve]) and
 * whose other requests are refused, like the WebViews' `blockNetworkLoads`: YouTube's player script
 * and BotGuard run in a real browser engine without the page reaching the network itself.
 *
 * Never shown and never focused (`--headless=new`), muted, and closed after [IDLE_MS] unused.
 */
internal object HeadlessChromium {
    private const val TAG = "Zemer_Chromium"
    private const val IDLE_MS = 10 * 60_000L

    /** The Chromium executable to run, or null when there's none (set by the app at startup). */
    @Volatile var browserExe: () -> File? = { null }
    /** Where the throwaway profile lives (set by the app at startup). */
    @Volatile var workDir: File = File(System.getProperty("java.io.tmpdir"), "auralis-zemer")

    private val http by lazy { OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build() }
    private val startLock = Mutex()
    @Volatile private var process: Process? = null
    @Volatile private var connection: Connection? = null
    @Volatile private var lastUse = 0L
    private val pages = ConcurrentHashMap.newKeySet<Page>()

    class ChromiumUnavailable(message: String) : Exception(message)

    val available: Boolean get() = browserExe()?.isFile == true

    /** Opens an unseen tab (starting the browser when needed). */
    suspend fun openPage(): Page = withContext(Dispatchers.IO) {
        val conn = ensureStarted()
        val target = conn.call("Target.createTarget", JSONObject().put("url", "about:blank")).optString("targetId")
        if (target.isBlank()) throw ChromiumUnavailable("Chromium didn't open a tab")
        val session = conn.call("Target.attachToTarget", JSONObject().put("targetId", target).put("flatten", true)).optString("sessionId")
        if (session.isBlank()) throw ChromiumUnavailable("Chromium didn't attach to its tab")
        Page(conn, target, session).also { pages += it; it.enable() }
    }

    private suspend fun ensureStarted(): Connection = startLock.withLock {
        lastUse = System.currentTimeMillis()
        connection?.takeIf { it.open && process?.isAlive != false }?.let { return it }
        shutdownLocked()
        val exe = browserExe()?.takeIf { it.isFile } ?: throw ChromiumUnavailable("No Chromium browser to run YouTube's player script")
        // A browser left running from an earlier session (the app was killed) holds its profile's
        // lock: take it over when it still answers (it's closed like ours when idle), and clear
        // the profiles of browsers that are gone. Each launch gets a profile of its own.
        val profiles = File(workDir, "browser")
        for (old in profiles.listFiles().orEmpty().filter { it.isDirectory }) {
            adopt(File(old, "DevToolsActivePort"))?.let { conn ->
                connection = conn
                Timber.tag(TAG).i("Took over an unseen browser left running for YouTube's player script")
                scheduleIdleClose()
                return conn
            }
            runCatching { old.deleteRecursively() }
        }
        val profile = File(profiles, "p${ProcessHandle.current().pid()}-${System.currentTimeMillis()}").apply { mkdirs() }
        val portFile = File(profile, "DevToolsActivePort")
        val started = ProcessBuilder(exe.absolutePath, "--user-data-dir=${profile.absolutePath}", "--headless=new",
            "--remote-debugging-port=0", "--remote-allow-origins=*", "--no-first-run", "--no-default-browser-check",
            "--mute-audio", "--disable-extensions", "--disable-background-networking", "--disable-sync",
            "--disable-component-update", "--disable-default-apps", "about:blank")
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        process = started
        val endpoint = withTimeoutOrNull(20_000L) {
            while (true) {
                if (!started.isAlive) return@withTimeoutOrNull null
                val lines = runCatching { portFile.readLines() }.getOrNull()
                if (lines != null && lines.size >= 2 && lines[0].toIntOrNull() != null)
                    return@withTimeoutOrNull "ws://127.0.0.1:${lines[0].trim()}${lines[1].trim()}"
                delay(150)
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: run { started.destroy(); process = null; throw ChromiumUnavailable("Chromium didn't start (${exe.name})") }
        val conn = Connection(endpoint)
        connection = conn
        Timber.tag(TAG).i("Started unseen ${exe.parentFile?.parentFile?.name ?: exe.name} for YouTube's player script")
        scheduleIdleClose()
        conn
    }

    /** A DevTools connection to a browser already running on our profile, or null when there's none. */
    private suspend fun adopt(portFile: File): Connection? {
        val lines = runCatching { portFile.readLines() }.getOrNull() ?: return null
        val port = lines.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val path = lines.getOrNull(1)?.trim()?.takeIf { it.startsWith("/devtools/browser/") } ?: return null
        val conn = runCatching { Connection("ws://127.0.0.1:$port$path") }.getOrNull() ?: return null
        val version = conn.call("Browser.getVersion", timeoutMs = 3_000L)
        if (version.has("__cdpError") || version.optString("product").isBlank()) { conn.close(); return null }
        process = null
        return conn
    }

    private fun scheduleIdleClose() {
        val watched = connection
        Thread({
            while (true) {
                Thread.sleep(60_000L)
                if (connection !== watched) return@Thread
                if (System.currentTimeMillis() - lastUse > IDLE_MS) {
                    kotlinx.coroutines.runBlocking { startLock.withLock { if (connection === watched) shutdownLocked() } }
                    return@Thread
                }
            }
        }, "zemer-chromium-idle").apply { isDaemon = true }.start()
    }

    /** Closes the browser (pages become dead; the next [openPage] starts it again). */
    suspend fun shutdown() = startLock.withLock { shutdownLocked() }

    private fun shutdownLocked() {
        pages.forEach { it.dead = true }
        pages.clear()
        connection?.let { conn -> runCatching { conn.sendOnly("Browser.close") }; conn.close() }
        connection = null
        process?.let { p -> if (p.isAlive) { runCatching { p.waitFor(1, TimeUnit.SECONDS) }; if (p.isAlive) p.destroy() } }
        process = null
    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { connection?.sendOnly("Browser.close") }
            runCatching { process?.let { p -> p.descendants().forEach { it.destroy() }; p.destroy() } }
        })
    }

    class JsException(message: String) : Exception(message)

    /** One unseen tab. Requests it makes are answered by [serve]; any other request fails. */
    class Page internal constructor(private val conn: Connection, private val targetId: String, private val sessionId: String) {
        @Volatile var dead = false
            internal set
        private val routes = ConcurrentHashMap<String, Pair<ByteArray, String>>()

        internal suspend fun enable() {
            conn.onEvent(sessionId) { method, params ->
                when (method) {
                    "Fetch.requestPaused" -> answer(params)
                    "Inspector.targetCrashed", "Inspector.detached" -> dead = true
                }
            }
            call("Fetch.enable", JSONObject().put("patterns", JSONArray().put(JSONObject().put("urlPattern", "*"))))
            call("Runtime.enable")
            call("Inspector.enable")
        }

        fun serve(url: String, body: String, contentType: String) { routes[url] = body.toByteArray(Charsets.UTF_8) to contentType }

        private fun answer(params: JSONObject) {
            val id = params.optString("requestId")
            val url = params.optJSONObject("request")?.optString("url").orEmpty()
            val route = routes[url]
            if (route == null) {
                conn.sendOnly("Fetch.failRequest", JSONObject().put("requestId", id).put("errorReason", "BlockedByClient"), sessionId)
                return
            }
            conn.sendOnly("Fetch.fulfillRequest", JSONObject().put("requestId", id).put("responseCode", 200)
                .put("responseHeaders", JSONArray().put(JSONObject().put("name", "Content-Type").put("value", route.second)))
                .put("body", Base64.getEncoder().encodeToString(route.first)), sessionId)
        }

        /** Loads [url] (which must be [serve]d) and waits for its load event. */
        suspend fun navigate(url: String, timeoutMs: Long) {
            val loaded = CompletableDeferred<Unit>()
            conn.onEvent(sessionId, "Page.loadEventFired") { _, _ -> loaded.complete(Unit) }
            call("Page.enable")
            val result = call("Page.navigate", JSONObject().put("url", url))
            if (result.has("errorText")) throw JsException("Navigation failed: ${result.optString("errorText")}")
            withTimeoutOrNull(timeoutMs) { loaded.await() } ?: throw JsException("Page didn't load in ${timeoutMs}ms")
        }

        /** Runs [expression] (awaiting a returned promise) and returns its value as JSON text. */
        suspend fun evaluate(expression: String, timeoutMs: Long = 15_000L): Any? {
            if (dead) throw JsException("Page is gone")
            lastUse = System.currentTimeMillis()
            val result = withTimeoutOrNull(timeoutMs) {
                call("Runtime.evaluate", JSONObject().put("expression", expression).put("awaitPromise", true)
                    .put("returnByValue", true), timeoutMs)
            } ?: run { dead = true; throw JsException("Script didn't answer in ${timeoutMs}ms") }
            result.optJSONObject("exceptionDetails")?.let { details ->
                val text = details.optJSONObject("exception")?.optString("description")?.takeIf { it.isNotBlank() }
                    ?: details.optString("text")
                throw JsException(text)
            }
            if (result.has("__cdpError")) throw JsException(result.optString("__cdpError"))
            val value = result.optJSONObject("result") ?: return null
            return if (value.has("value")) value.get("value").takeIf { it != JSONObject.NULL } else null
        }

        private suspend fun call(method: String, params: JSONObject = JSONObject(), timeoutMs: Long = 15_000L) =
            conn.call(method, params, sessionId, timeoutMs)

        fun close() {
            dead = true
            pages.remove(this)
            conn.removeListeners(sessionId)
            conn.sendOnly("Target.closeTarget", JSONObject().put("targetId", targetId))
        }
    }

    /** A DevTools connection to the browser; page sessions share it (flattened sessions). */
    internal class Connection(url: String) : AutoCloseable {
        private val ids = AtomicInteger(0)
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
        private val listeners = ConcurrentHashMap<String, MutableList<Pair<String?, (String, JSONObject) -> Unit>>>()
        @Volatile var open = true
            private set
        private val socket: WebSocket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (message.has("id")) {
                    val answer = message.optJSONObject("result") ?: JSONObject().apply {
                        message.optJSONObject("error")?.let { put("__cdpError", it.optString("message")) }
                    }
                    pending.remove(message.optInt("id", -1))?.complete(answer)
                    return
                }
                val method = message.optString("method")
                val session = message.optString("sessionId")
                val params = message.optJSONObject("params") ?: JSONObject()
                listeners[session]?.toList()?.forEach { (only, listener) ->
                    if (only == null || only == method) runCatching { listener(method, params) }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = fail(t)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail(IllegalStateException("closed"))
        })

        private fun fail(t: Throwable) {
            open = false
            pending.values.forEach { it.complete(JSONObject().put("__cdpError", t.message ?: "connection lost")) }
            pending.clear()
        }

        fun onEvent(session: String, method: String? = null, listener: (String, JSONObject) -> Unit) {
            listeners.computeIfAbsent(session) { java.util.Collections.synchronizedList(mutableListOf()) } += method to listener
        }

        fun removeListeners(session: String) { listeners.remove(session) }

        private fun message(id: Int, method: String, params: JSONObject, session: String?) =
            JSONObject().put("id", id).put("method", method).put("params", params).apply { session?.let { put("sessionId", it) } }.toString()

        suspend fun call(method: String, params: JSONObject = JSONObject(), session: String? = null, timeoutMs: Long = 15_000L): JSONObject {
            if (!open) return JSONObject().put("__cdpError", "connection lost")
            val id = ids.incrementAndGet()
            val answer = CompletableDeferred<JSONObject>().also { pending[id] = it }
            socket.send(message(id, method, params, session))
            return withTimeoutOrNull(timeoutMs) { answer.await() } ?: JSONObject().put("__cdpError", "$method timed out").also { pending.remove(id) }
        }

        fun sendOnly(method: String, params: JSONObject = JSONObject(), session: String? = null) {
            if (open) socket.send(message(ids.incrementAndGet(), method, params, session))
        }

        override fun close() { open = false; socket.close(1000, null) }
    }
}
