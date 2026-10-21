package com.auralis.music.windows.settings

import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.Track
import com.auralis.music.windows.profile.ProtectedYouTubeStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal object ScrobblePreferences {
    private val disk by lazy { ProtectedYouTubeStore(File(com.auralis.music.util.desktopAppDataDir(), "scrobbling.properties")) }
    private val store by lazy { DesktopPreferencesStore(disk, storageKey = "cookie") }
    val changes get() = store.changes
    fun text(key: String) = store.text(key)
    fun bool(key: String, default: Boolean = false) = store.bool(key, default)
    fun number(key: String, default: Double) = store.number(key, default)
    fun set(key: String, value: Any) = store.set(key, value)
    fun metadata(track: Track) = scrobbleMetadata(track, store.configuration())
}

internal fun scrobbleMetadata(track: Track, config: JSONObject): Triple<String, String, String> {
    fun bool(key: String, default: Boolean = false) = config.optBoolean(key,default)
    fun text(key: String) = config.optString(key)
        var title = track.title; var artist = track.artist; var album = track.album.orEmpty()
        if (bool("cleanTitle", true)) title = title.replace(Regex("(?i)\\s*[\\[(](official (music )?video|official audio|lyrics?|visuali[sz]er|hd|hq)[\\])]"), "").trim()
        if (bool("stripTopic", true)) artist = artist.removeSuffix(" - Topic")
        if (bool("splitVideoTitles") && track.isMusicVideo && title.contains(" - ")) {
            artist = title.substringBefore(" - ").trim(); title = title.substringAfter(" - ").trim()
        }
        if (bool("primaryArtist")) artist = artist.split(Regex(if(bool("primaryStrict")) "(?i)\\s*(?:,|&|/| feat\\.? | ft\\.? | featuring | x )\\s*" else "(?i)\\s+(?:feat\\.?|ft\\.?|featuring)\\s+", RegexOption.IGNORE_CASE)).first().trim()
        val rules = runCatching { org.json.JSONArray(text("rules").ifBlank { "[]" }) }.getOrElse { org.json.JSONArray() }
        for (i in 0 until rules.length()) rules.optJSONObject(i)?.takeIf { it.optBoolean("enabled", true) }?.let { rule ->
            val find = rule.optString("find").takeIf(String::isNotEmpty) ?: return@let
            val replace = rule.optString("replace")
            fun apply(s: String) = if (rule.optBoolean("regex")) runCatching { Regex(find).replace(s, replace) }.getOrDefault(s) else s.replace(find, replace)
            when (rule.optString("field")) { "title" -> title = apply(title); "artist" -> artist = apply(artist); "album" -> album = apply(album) }
        }
        runCatching { JSONObject(text("edits").ifBlank { "{}" }).optJSONObject(track.id) }.getOrNull()?.let { edit ->
            title = edit.optString("title",title); artist = edit.optString("artist",artist); album = edit.optString("album",album)
        }
        return Triple(title, artist, album)
    }

internal object ScrobbleApi {
    private val client by lazy { com.auralis.music.data.network.NetworkClientProvider.okHttpClient.newBuilder().callTimeout(12, java.util.concurrent.TimeUnit.SECONDS).build() }
    fun signature(params: Map<String, String>, secret: String) = MessageDigest.getInstance("MD5")
        .digest((params.toSortedMap().entries.joinToString("") { it.key + it.value } + secret).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun lastfm(method: String, fields: Map<String, String> = emptyMap()): JSONObject {
        val params = fields + mapOf("method" to method, "api_key" to ScrobblePreferences.text("apiKey"))
        require(params.getValue("api_key").isNotBlank() && ScrobblePreferences.text("apiSecret").isNotBlank()) { "Enter your Last.fm API key and shared secret." }
        val signed = params + ("api_sig" to signature(params, ScrobblePreferences.text("apiSecret"))) + ("format" to "json")
        val request = Request.Builder().url("https://ws.audioscrobbler.com/2.0/").post(FormBody.Builder().apply { signed.forEach { (k,v) -> add(k,v) } }.build()).build()
        return client.newCall(request).execute().use { r ->
            val json = JSONObject(r.body?.string().orEmpty())
            check(r.isSuccessful && !json.has("error")) { json.optString("message", "Last.fm HTTP ${r.code}") }; json
        }
    }
    fun beginLastfm(): String {
        val token = lastfm("auth.getToken").getString("token"); ScrobblePreferences.set("requestToken", token)
        return "https://www.last.fm/api/auth/?api_key=${ScrobblePreferences.text("apiKey")}&token=$token"
    }
    fun finishLastfm() {
        val session = lastfm("auth.getSession", mapOf("token" to ScrobblePreferences.text("requestToken"))).getJSONObject("session")
        ScrobblePreferences.set("session", session.getString("key")); ScrobblePreferences.set("lastfmUser", session.getString("name"))
        ScrobblePreferences.set("lastfmEnabled", true); ScrobblePreferences.set("requestToken", "")
    }
    fun validateListenBrainz(token: String): String {
        val request = Request.Builder().url("https://api.listenbrainz.org/1/validate-token").header("Authorization", "Token $token").build()
        return client.newCall(request).execute().use { r ->
            val json = JSONObject(r.body?.string().orEmpty())
            check(r.isSuccessful && json.optBoolean("valid")) { "ListenBrainz did not accept this token." }; json.getString("user_name")
        }
    }
    fun submit(track: Track, started: Long, now: Boolean, provider: String) {
        val (title, artist, album) = ScrobblePreferences.metadata(track)
        if (provider == "lastfm") {
            val fields = mutableMapOf("track" to title, "artist" to artist, "album" to album, "sk" to ScrobblePreferences.text("session"), "duration" to track.duration.toString())
            if (!now) fields["timestamp"] = started.toString()
            lastfm(if (now) "track.updateNowPlaying" else "track.scrobble", fields)
        } else {
            val listen = JSONObject().put("track_metadata", JSONObject().put("track_name", title).put("artist_name", artist)
                .put("release_name", album).put("additional_info", JSONObject().put("submission_client", "Auralis").put("duration", track.duration)))
            if (!now) listen.put("listened_at", started)
            val body = JSONObject().put("listen_type", if (now) "playing_now" else "single").put("payload", org.json.JSONArray().put(listen))
            val request = Request.Builder().url("https://api.listenbrainz.org/1/submit-listens")
                .header("Authorization", "Token ${ScrobblePreferences.text("lbToken")}").post(body.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { check(it.isSuccessful) { "ListenBrainz HTTP ${it.code}" } }
        }
    }
}

internal fun scrobbleThreshold(durationMs: Long, percent: Double, minutes: Double): Long =
    minOf((durationMs * percent.coerceIn(50.0, 100.0) / 100).toLong(), if(minutes<=0) Long.MAX_VALUE else (minutes.coerceIn(4.0, 30.0) * 60_000).toLong())

internal class DesktopScrobbler(playback: DesktopPlaybackController?, scope: CoroutineScope) : AutoCloseable {
    companion object { val status = MutableStateFlow("Connect a service to scrobble your plays.") }
    private val job = if (playback == null) null else scope.launch(Dispatchers.IO) {
        var session = -1L; var accumulated = 0L; var previous = System.nanoTime(); var started = 0L
        val sent = mutableSetOf<String>(); val announced = mutableSetOf<String>()
        val retryAfter = mutableMapOf<String, Long>()
        while (isActive) {
            delay(1000)
            val tick = System.nanoTime(); val delta = ((tick - previous) / 1_000_000).coerceIn(0L, 2000L); previous = tick
            if (playback.session.value != session) { session = playback.session.value; accumulated = 0; started = System.currentTimeMillis()/1000; sent.clear(); announced.clear(); retryAfter.clear() }
            if (playback.state.value != VlcAudioPlayer.State.PLAYING) continue
            val track = playback.queue.value.current ?: continue
            if (runCatching { JSONObject(ScrobblePreferences.text("edits").ifBlank {"{}"}).optJSONObject(track.id)?.optBoolean("skip") == true }.getOrDefault(false)) continue
            val duration = playback.lengthMs.value.takeIf { it > 0 } ?: track.duration * 1000
            if (duration < 30_000 || (ScrobblePreferences.bool("skipLocal") && (track.id.startsWith("file:") || track.id.startsWith("local:")))) continue
            accumulated += delta
            for (provider in listOf("lastfm", "lb")) {
                if (!ScrobblePreferences.bool("${provider}Enabled")) continue
                if (ScrobblePreferences.text(if (provider == "lastfm") "session" else "lbToken").isBlank()) continue
                if ((retryAfter[provider] ?: 0) > System.currentTimeMillis()) continue
                val now = provider !in announced
                if (!now && (provider in sent || accumulated < scrobbleThreshold(duration, ScrobblePreferences.number("percent",50.0), ScrobblePreferences.number("minutes",4.0)))) continue
                try {
                    if (!now || ScrobblePreferences.bool("nowPlaying", true)) ScrobbleApi.submit(track, started, now, provider)
                    if (now) announced += provider else sent += provider
                    status.value = if (now) "Now playing sent to $provider." else "Scrobbled to $provider."
                } catch (_: Exception) { status.value = "Couldn't reach $provider. Retrying in a minute."; retryAfter[provider] = System.currentTimeMillis()+60_000 }
            }
        }
    }
    override fun close() { job?.cancel() }
}
