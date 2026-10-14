package com.auralis.music.windows.profile

import com.auralis.music.data.network.YouTubeSession
import com.auralis.music.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Sign in with Google for YouTube Music": a browser window with Auralis's own profile (separate
 * from the listener's browsers) opens on Google's account chooser for YouTube; once YouTube Music
 * loads signed in, its session is taken from that window and the window closes.
 *
 * Chromium browsers (Chrome, Edge, Brave, Helium...) hand the cookies over through their DevTools
 * port, with no decryption; Firefox/Zen keep them readable in the profile's cookies.sqlite. Because
 * the profile is Auralis's, a stale session is later renewed from it in the background, unseen.
 */
internal object YouTubeBrowserSignIn {
    private const val TAG = "YouTubeBrowserSignIn"
    private const val CHOOSER = "https://accounts.google.com/AccountChooser?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F"
    private val root get() = File(com.auralis.music.util.desktopAppDataDir(), "youtube-signin-browser")

    internal data class Browser(val exe: File, val chromium: Boolean, val name: String)

    /** The browser to sign in with: any Chromium browser installed, else Firefox or Zen. */
    fun browser(): Browser? {
        fun appPath(exe: String) = listOf("HKCU", "HKLM").firstNotNullOfOrNull { hive ->
            runCatching {
                val out = ProcessBuilder("reg", "query", "$hive\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\$exe", "/ve")
                    .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
                Regex("""REG_SZ\s+(.+)""").find(out)?.groupValues?.get(1)?.trim()?.trim('"')?.let(::File)?.takeIf { it.isFile }
            }.getOrNull()
        }
        val local = System.getenv("LOCALAPPDATA").orEmpty()
        val chromium = listOfNotNull(appPath("chrome.exe"), appPath("msedge.exe"), appPath("brave.exe"),
            File(System.getenv("ProgramFiles").orEmpty(), "Google/Chrome/Application/chrome.exe"),
            File(local, "Google/Chrome/Application/chrome.exe"),
            File(System.getenv("ProgramFiles(x86)").orEmpty(), "Microsoft/Edge/Application/msedge.exe"),
            File(local, "imput/Helium/Application/chrome.exe")).firstOrNull { it.isFile }
        if (chromium != null) return Browser(chromium, true, chromium.parentFile?.parentFile?.name ?: "browser")
        val firefox = listOfNotNull(appPath("firefox.exe"), appPath("zen.exe"),
            File(System.getenv("ProgramFiles").orEmpty(), "Mozilla Firefox/firefox.exe")).firstOrNull { it.isFile }
        return firefox?.let { Browser(it, false, it.nameWithoutExtension) }
    }

    /** Opens the sign-in window and waits (up to 10 minutes) until YouTube Music is signed in there. */
    suspend fun signIn(onStatus: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val browser = browser() ?: error("No browser found for signing in. Install Chrome, Edge or Firefox.")
        onStatus("Sign in in the ${browser.name.replaceFirstChar { it.uppercase() }} window that opened…")
        val cookie = session(browser, headless = false, timeoutMs = 10 * 60_000L) { it != null }
        cookie ?: error("Sign-in didn't finish. The window was closed or took too long.")
    }

    /** A current session from Auralis's sign-in profile, loaded unseen; null when it isn't signed in. */
    suspend fun renew(): String? = withContext(Dispatchers.IO) {
        val browser = browser() ?: return@withContext null
        if (!profileDir(browser).isDirectory) return@withContext null
        // Give YouTube Music a few seconds to refresh its cookies before reading them.
        runCatching { session(browser, headless = true, timeoutMs = 25_000L, settleMs = 6_000L) { it != null } }
            .onFailure { Log.w(TAG, "Couldn't renew from the sign-in profile: ${it.message}") }.getOrNull()
    }

    /** Development check: loads YouTube Music unseen and returns the youtube.com cookie names it got. */
    internal suspend fun devtoolsCheck(): List<String> = withContext(Dispatchers.IO) {
        val browser = browser() ?: return@withContext listOf("no browser")
        val cookie = session(browser, headless = true, timeoutMs = 20_000L, settleMs = 0L, requireAuth = false) { true }
        listOf("browser=${browser.exe}") + cookie.orEmpty().split("; ").map { it.substringBefore('=') }
    }

    private fun profileDir(browser: Browser) = File(root, if (browser.chromium) "chromium" else "firefox")

    /** Runs the browser on the profile until [done] accepts the session (or [timeoutMs]), then closes it. */
    private suspend fun session(browser: Browser, headless: Boolean, timeoutMs: Long, settleMs: Long = 2_000L,
        requireAuth: Boolean = true, done: (String?) -> Boolean): String? {
        val dir = profileDir(browser).apply { mkdirs() }
        val url = if (headless) "https://music.youtube.com/" else CHOOSER
        return if (browser.chromium) {
            val port = ServerSocket(0).use { it.localPort }
            val process = ProcessBuilder(listOfNotNull(browser.exe.absolutePath, "--user-data-dir=${dir.absolutePath}",
                "--remote-debugging-port=$port", "--remote-allow-origins=*", "--no-first-run", "--no-default-browser-check",
                if (headless) "--headless=new" else "--new-window", url)).start()
            try {
                val devtools = withTimeoutOrNull(20_000L) {
                    while (true) {
                        runCatching { JSONObject(get("http://127.0.0.1:$port/json/version")).optString("webSocketDebuggerUrl") }
                            .getOrNull()?.takeIf { it.isNotBlank() }?.let { return@withTimeoutOrNull it }
                        if (!process.isAlive) return@withTimeoutOrNull null
                        delay(300)
                    }
                    @Suppress("UNREACHABLE_CODE") null
                } ?: error("The browser didn't open its sign-in window.")
                Cdp(devtools).use { cdp ->
                    var found: String? = null
                    withTimeoutOrNull(timeoutMs) {
                        while (process.isAlive) {
                            val cookie = youtubeCookie(cdp.call("Storage.getCookies").optJSONArray("cookies"))
                            if (YouTubeSession.hasAuthCookies(cookie) || (!requireAuth && cookie.isNotBlank())) {
                                delay(settleMs)
                                found = youtubeCookie(cdp.call("Storage.getCookies").optJSONArray("cookies"))
                                    .takeIf { !requireAuth || YouTubeSession.hasAuthCookies(it) } ?: cookie
                                break
                            }
                            delay(1_500)
                        }
                    }
                    runCatching { cdp.call("Browser.close") }
                    found?.takeIf { done(it) }
                }
            } finally {
                if (process.isAlive) { delay(800); process.destroy() }
            }
        } else {
            val process = ProcessBuilder(listOfNotNull(browser.exe.absolutePath, "-no-remote", "-profile", dir.absolutePath,
                if (headless) "-headless" else null, url)).start()
            try {
                val profile = BrowserYouTubeLogin.Profile(browser.name, "Auralis sign-in", File(dir, "cookies.sqlite"), true)
                var found: String? = null
                withTimeoutOrNull(timeoutMs) {
                    while (process.isAlive || headless) {
                        val cookie = runCatching { BrowserYouTubeLogin.read(profile) }.getOrNull()
                        if (cookie != null) {
                            delay(settleMs)
                            found = runCatching { BrowserYouTubeLogin.read(profile) }.getOrNull() ?: cookie
                            break
                        }
                        delay(1_500)
                    }
                }
                found?.takeIf { done(it) }
            } finally {
                process.destroy()
            }
        }
    }

    /** The youtube.com cookies as a Cookie header. */
    private fun youtubeCookie(cookies: JSONArray?): String = (0 until (cookies?.length() ?: 0)).mapNotNull { i ->
        cookies!!.optJSONObject(i)?.takeIf { it.optString("domain").trimStart('.').let { d -> d == "youtube.com" || d.endsWith(".youtube.com") } }
            ?.let { "${it.optString("name")}=${it.optString("value")}" }
    }.distinct().joinToString("; ")

    private val http by lazy { OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build() }
    private fun get(url: String) = http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }

    /** A minimal Chrome DevTools Protocol connection (browser target). */
    private class Cdp(url: String) : AutoCloseable {
        private val ids = AtomicInteger(0)
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
        private val socket: WebSocket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                pending.remove(message.optInt("id", -1))?.complete(message.optJSONObject("result") ?: JSONObject())
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                pending.values.forEach { it.completeExceptionally(t) }; pending.clear()
            }
        })

        suspend fun call(method: String, params: JSONObject = JSONObject()): JSONObject {
            val id = ids.incrementAndGet()
            val answer = CompletableDeferred<JSONObject>().also { pending[id] = it }
            socket.send(JSONObject().put("id", id).put("method", method).put("params", params).toString())
            return withTimeoutOrNull(10_000L) { answer.await() } ?: JSONObject()
        }

        override fun close() { socket.close(1000, null) }
    }
}
