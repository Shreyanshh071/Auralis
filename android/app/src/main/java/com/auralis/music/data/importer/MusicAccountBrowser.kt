package com.auralis.music.data.importer

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume

/** WebView operations run on Main; service API requests run on IO in MusicAccountLibrary. */
class MusicAccountBrowser(private val context: Context, val service: MusicAccountService, private val web: WebView) {
    private var library: MusicAccountLibrary? = null
    private var disposed = false

    fun dispose() {
        disposed = true
        library = null
    }

    suspend fun playlists(progress: (Int) -> Unit): List<MusicAccountPlaylist> = withContext(Dispatchers.Main) {
        when (service) {
            MusicAccountService.DEEZER -> {
                val arl = listOf("https://www.deezer.com", "https://auth.deezer.com").firstNotNullOfOrNull { origin ->
                    CookieManager.getInstance().getCookie(origin).orEmpty().split(';')
                        .map { it.trim() }.firstOrNull { it.startsWith("arl=") }?.substringAfter('=')?.takeIf { it.isNotBlank() }
                } ?: throw MusicAccountException("Sign in to Deezer first, then load playlists.")
                MusicAccountLibrary(service, arl).also { library = it }.playlists()
            }
            MusicAccountService.TIDAL -> {
                if (Uri.parse(web.url).host != "listen.tidal.com")
                    throw MusicAccountException("Finish signing in and open Tidal's web player, then load playlists.")
                val tokens = evaluate(TIDAL_SESSION) as? JSONArray
                    ?: throw MusicAccountException("Tidal's session is not available yet. Open the web player and retry.")
                if (tokens.length() == 0) throw MusicAccountException("Sign in to Tidal and let the web player load, then retry.")
                var lastFailure: MusicAccountException? = null
                var result: List<MusicAccountPlaylist>? = null
                for (i in 0 until tokens.length()) {
                    try {
                        val candidate = MusicAccountLibrary(service, tokens.getString(i))
                        result = candidate.playlists()
                        library = candidate
                        break
                    } catch (e: MusicAccountException) { lastFailure = e }
                }
                result ?: throw (lastFailure ?: MusicAccountException("Tidal sign-in needs to be renewed."))
            }
            MusicAccountService.AMAZON -> {
                val current = Uri.parse(web.url)
                if (!isAmazonHost(current.host)) throw MusicAccountException("Finish signing in and open Amazon Music before loading playlists.")
                if (current.path?.trimEnd('/') != "/my/playlists") {
                    navigate("https://${current.host}/my/playlists")
                }
                scan("library", progress).objects().map { p ->
                    MusicAccountPlaylist(p.getString("id"), p.getString("title"),
                        p.optString("coverUrl").takeIf { it.startsWith("https://") },
                        p.optInt("trackCount", -1), url = p.getString("url"), remoteId = p.getString("id"))
                }
            }
        }
    }

    suspend fun readPlaylist(item: MusicAccountPlaylist, progress: (Int) -> Unit): ExternalPlaylist = withContext(Dispatchers.Main) {
        if (service != MusicAccountService.AMAZON) {
            return@withContext (library ?: throw MusicAccountException("Reload your playlists before importing."))
                .readPlaylist(item, progress)
        }
        val url = item.url ?: throw MusicAccountException("This playlist has no Amazon Music address.")
        val parsed = Uri.parse(url)
        if (!isAmazonHost(parsed.host) || parsed.scheme != "https" ||
            !Regex("/(?:user-playlists|playlists)/[A-Za-z0-9_-]+/?").matches(parsed.path.orEmpty()))
            throw MusicAccountException("This is not an Amazon Music playlist address.")
        navigate(url)
        val tracks = scan("tracks", progress).objects().map { row ->
            val title = row.getString("title")
            val artist = row.getString("artist")
            val id = row.optString("id").ifBlank {
                MessageDigest.getInstance("SHA-256").digest("$artist|$title".toByteArray())
                    .take(12).joinToString("") { "%02x".format(it) }
            }
            val seconds = row.optString("duration").split(':').fold(0L) { sum, part -> sum * 60 + (part.toLongOrNull() ?: 0L) }
            accountTrack(service, id, title, artist, row.optString("album").ifBlank { null }, seconds, item.coverUrl)
        }
        ExternalPlaylist(ExternalService.AMAZON_MUSIC, item.remoteId, item.title, null, item.coverUrl, tracks)
    }

    private suspend fun navigate(url: String) {
        val parsed = Uri.parse(url)
        web.loadUrl(url, mapOf("Accept-Language" to "en-US,en;q=0.9"))
        withTimeout(45_000) {
            do {
                delay(500)
                val current = Uri.parse(web.url)
                val ready = evaluate("document.readyState") == "complete"
            } while (current.host != parsed.host || current.path?.trimEnd('/') != parsed.path?.trimEnd('/') || !ready)
        }
        delay(1200) // The web player populates its custom elements after document load.
    }

    private suspend fun scan(mode: String, progress: (Int) -> Unit): JSONArray {
        val id = UUID.randomUUID().toString()
        val script = context.assets.open("music-account-browser.js").bufferedReader().use { it.readText() }
        evaluate(script)
        evaluate("window.AuralisMusicReader.scan(${JSONObject.quote(mode)}, ${JSONObject.quote(id)}); true")
        try {
            return withTimeout(120_000) {
                var result: JSONArray? = null
                while (result == null) {
                    delay(500)
                    val state = evaluate("window.__auralisMusicJob || null") as? JSONObject
                        ?: throw MusicAccountException("The page navigated during import. Open your library and retry.")
                    if (state.optString("id") != id) throw MusicAccountException("The page changed. Please retry.")
                    progress(state.optInt("count"))
                    when (state.optString("status")) {
                        "done" -> result = state.getJSONArray("result")
                        "error" -> throw MusicAccountException(state.optString("error", "The service could not read this playlist."))
                    }
                }
                requireNotNull(result)
            }
        } finally {
            if (!disposed) web.evaluateJavascript("if(window.__auralisMusicJob && window.__auralisMusicJob.id === ${JSONObject.quote(id)}) { window.__auralisMusicJob.cancelled = true; delete window.__auralisMusicJob.result; }", null)
        }
    }

    private suspend fun evaluate(script: String): Any? = suspendCancellableCoroutine { continuation ->
        if (disposed) {
            continuation.cancel(CancellationException("Account browser closed"))
            return@suspendCancellableCoroutine
        }
        web.evaluateJavascript(script) { value ->
            if (continuation.isActive) continuation.resume(runCatching { JSONTokener(value).nextValue() }.getOrNull())
        }
    }

    companion object {
        private val AMAZON_REGIONS = setOf("com", "in", "co.uk", "de", "fr", "it", "es", "co.jp", "ca", "com.au", "com.mx", "com.br")
        fun isAmazonHost(host: String?): Boolean = AMAZON_REGIONS.any { host == "music.amazon.$it" }

        /** Clear only this service; never clear the global WebView cookies used by Spotify/YT. */
        fun disconnect(service: MusicAccountService) {
            val hosts = when (service) {
                MusicAccountService.DEEZER -> listOf("deezer.com", "www.deezer.com", "auth.deezer.com")
                MusicAccountService.TIDAL -> listOf("tidal.com", "listen.tidal.com", "login.tidal.com", "auth.tidal.com")
                MusicAccountService.AMAZON -> AMAZON_REGIONS.flatMap { listOf("amazon.$it", "www.amazon.$it", "music.amazon.$it") }
            }
            val cookies = CookieManager.getInstance()
            hosts.forEach { host ->
                cookies.getCookie("https://$host").orEmpty().split(';').map { it.substringBefore('=').trim() }
                    .filter { it.isNotBlank() }.forEach { name ->
                        cookies.setCookie("https://$host", "$name=; Max-Age=0; Path=/; Secure")
                        cookies.setCookie("https://$host", "$name=; Max-Age=0; Domain=.$host; Path=/; Secure")
                    }
                WebStorage.getInstance().deleteOrigin("https://$host")
            }
            cookies.flush()
        }

        private val TIDAL_SESSION = """
            (function() {
              if (location.origin !== 'https://listen.tidal.com') return [];
              const tokens = new Set();
              function walk(value, depth) {
                if (!value || depth > 7) return;
                if (typeof value === 'string') { try { walk(JSON.parse(value), depth + 1); } catch (_) {} return; }
                if (typeof value !== 'object') return;
                for (const [key, entry] of Object.entries(value)) {
                  if (/^(access_token|accessToken|accessTokenValue)$/i.test(key) && typeof entry === 'string' && entry.length > 20) tokens.add(entry);
                  else walk(entry, depth + 1);
                }
              }
              for (const storage of [localStorage, sessionStorage]) {
                for (let i = 0; i < storage.length; i++) {
                  const key = storage.key(i), value = storage.getItem(key);
                  if (/^(access_token|accessToken)$/i.test(key) && value && value.length > 20) tokens.add(value.replace(/^"|"${'$'}/g, ''));
                  else if (/auth|token|session|tidal|credential/i.test(key)) walk(value, 0);
                }
              }
              return [...tokens].slice(0, 4);
            })()
        """.trimIndent()
    }
}
