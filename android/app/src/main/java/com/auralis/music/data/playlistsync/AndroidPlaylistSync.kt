package com.auralis.music.data.playlistsync

import android.content.Context
import com.auralis.music.data.network.*
import com.auralis.music.domain.repository.LibraryRepository
import com.auralis.music.util.KeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object AndroidPlaylistSync {
    fun protectedPlaylistIds(context: Context): Set<String> = runCatching {
        val data = context.applicationContext.getSharedPreferences("auralis_playlist_sync", Context.MODE_PRIVATE)
            .getString("playlist_sync_v1", "[]") ?: "[]"
        val links = org.json.JSONArray(data)
        (0 until links.length()).map { links.getJSONObject(it) }.filter { it.optBoolean("enabled", true) }
            .map { it.getString("local") }.toSet()
    }.getOrDefault(emptySet())
    fun create(context: Context, library: LibraryRepository): PlaylistSyncEngine {
        val prefs = context.applicationContext.getSharedPreferences("auralis_playlist_sync", Context.MODE_PRIVATE)
        // A write-ahead journal must be durable before any service mutation starts.
        val store = object : KeyValueStore {
            override fun getString(key: String, default: String) = prefs.getString(key, default)
            override fun putStrings(values: Map<String, String>) {
                check(prefs.edit().also { edit -> values.forEach { (key, value) -> edit.putString(key, value) } }.commit()) {
                    "Could not save the playlist sync journal. Nothing was uploaded."
                }
            }
            override fun clear() { check(prefs.edit().clear().commit()) }
        }
        val youtube = YouTubePlaylistSync(identity = { YouTubeSession.playlistSyncIdentity() }, post = { endpoint, extras ->
            withContext(Dispatchers.IO) {
                val account = YouTubeSession.playlistSyncIdentity()
                val version = YouTubeMusicLibrary.CLIENT_VERSION
                val client = JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", version)
                    .put("hl", ContentLocale.hl()).put("gl", ContentLocale.gl())
                if (YouTubeSession.visitorData.isNotBlank()) client.put("visitorData", YouTubeSession.visitorData)
                extras.put("context", JSONObject().put("client", client).put("user", JSONObject()
                    .put("onBehalfOfUser", YouTubeSession.playlistSyncUser()).put("lockedSafetyMode", false)))
                val request = Request.Builder().url("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
                    .header("Origin", "https://music.youtube.com").header("Referer", "https://music.youtube.com/")
                    .header("X-YouTube-Client-Name", "67").header("X-YouTube-Client-Version", version)
                    .header("User-Agent", "Mozilla/5.0")
                    .apply { YouTubeSession.authHeaders("https://music.youtube.com").forEach { (key, value) -> header(key, value) } }
                    .post(extras.toString().toRequestBody("application/json".toMediaType())).build()
                check(YouTubeSession.playlistSyncIdentity() == account) { "YouTube Music account changed. Sync paused." }
                val body = suspendCancellableCoroutine<String> { continuation ->
                    val call = NetworkClientProvider.okHttpClient.newCall(request)
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
                        override fun onResponse(call: Call, response: Response) {
                            val result = runCatching {
                                response.use {
                                    check(it.isSuccessful) { "YouTube Music sync failed (HTTP ${it.code}). Reconnect or retry later." }
                                    it.body?.string() ?: error("YouTube returned an empty response.")
                                }
                            }
                            result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                        }
                    })
                }
                val result = JSONObject(body)
                check(!result.has("error")) { "YouTube Music did not authorize this playlist request." }
                check(YouTubeSession.playlistSyncIdentity() == account) { "YouTube Music account changed. Review sync again." }
                result
            }
        })
        return PlaylistSyncEngine(library, PlaylistSyncStore(store), mapOf(SyncService.YOUTUBE to youtube, SyncService.SPOTIFY to SpotifyPlaylistSync()))
    }
}
