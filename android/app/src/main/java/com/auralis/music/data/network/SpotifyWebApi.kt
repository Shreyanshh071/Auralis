package com.auralis.music.data.network

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The web player's persisted queries work with its session token, unlike scoped OAuth REST calls. */
internal object SpotifyWebApi {
    private const val TAG = "SpotifyWebApi"
    private const val QUERY_URL = "https://api-partner.spotify.com/pathfinder/v2/query"
    private const val HASH_REGISTRY = "https://francescograzioso.github.io/Meld/spotify-gql-hashes.json"

    // Verified hashes from Meld's registry on 2026-10-04. A rejected hash triggers a registry refresh.
    private val bundledHashes = mapOf(
        "libraryV3" to "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455",
        "fetchPlaylist" to "8964e8eafb21aa992a7d951d256d83285c04be2105d209262901de70cb97584a",
        "fetchLibraryTracks" to "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"
    )
    private val previousHashes = mapOf(
        "libraryV3" to "973e511ca44261fda7eebac8b653155e7caee3675abb4fb110cc1b8c78b091c3",
        "fetchPlaylist" to "243c0ba2736f16da721e3a227004bbcdb8df6c846f198bd478172e00aa1faf42"
    )
    private var remoteHashes: Map<String, Pair<String, String?>> = emptyMap()

    private val client by lazy {
        OkHttpClient.Builder()
            .proxyAuthenticator(ContentProxy.authenticator)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun query(token: String, operation: String, variables: JSONObject): JSONObject {
        require(operation in bundledHashes) { "Unknown Spotify operation: $operation" }
        val candidates = linkedSetOf<String>()
        remoteHashes[operation]?.let { (current, previous) ->
            candidates.add(current)
            previous?.let(candidates::add)
        }
        candidates.add(bundledHashes.getValue(operation))
        previousHashes[operation]?.let(candidates::add)

        var refreshed = false
        while (true) {
            for (hash in candidates.toList()) {
                val result = execute(token, operation, variables, hash)
                if (result != null) return result
            }
            if (refreshed) throw IOException("Spotify rejected all $operation query hashes")
            refreshed = true
            refreshHashes()
            remoteHashes[operation]?.let { (current, previous) ->
                candidates.add(current)
                previous?.let(candidates::add)
            }
            if (candidates.isEmpty()) throw IOException("No Spotify query hash for $operation")
        }
    }

    /** Returns null only when Spotify rejected this persisted-query hash. */
    private fun execute(token: String, operation: String, variables: JSONObject, hash: String): JSONObject? {
        val body = JSONObject()
            .put("operationName", operation)
            .put("variables", variables)
            .put("extensions", JSONObject().put("persistedQuery", JSONObject()
                .put("version", 1).put("sha256Hash", hash)))
        val request = Request.Builder().url(QUERY_URL)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", SpotifySession.USER_AGENT)
            .header("app-platform", "WebPlayer")
            .header("Origin", "https://open.spotify.com")
            .header("Referer", "https://open.spotify.com/")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 412) return null
            if (!response.isSuccessful) {
                Log.w(TAG, "$operation returned HTTP ${response.code}")
                throw IOException("Spotify $operation returned HTTP ${response.code}")
            }
            val json = JSONObject(response.body?.string().orEmpty())
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                val message = errors.optJSONObject(0)?.optString("message").orEmpty()
                if (message.contains("PersistedQueryNotFound", ignoreCase = true)) return null
                throw IOException("Spotify $operation: $message")
            }
            return json
        }
    }

    private fun refreshHashes() {
        try {
            val request = Request.Builder().url(HASH_REGISTRY).header("Accept", "application/json").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val operations = JSONObject(response.body?.string().orEmpty()).optJSONObject("operations") ?: return
                val updated = mutableMapOf<String, Pair<String, String?>>()
                for (name in bundledHashes.keys) {
                    val entry = operations.optJSONObject(name) ?: continue
                    val hash = entry.optString("hash")
                    if (!hash.matches(Regex("[0-9a-f]{64}"))) continue
                    val previous = entry.optString("previous_hash").takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                    updated[name] = hash to previous
                }
                remoteHashes = updated
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not refresh Spotify query hashes: ${e.message}")
        }
    }

    fun libraryVariables(offset: Int, limit: Int): JSONObject = JSONObject()
        .put("filters", org.json.JSONArray().put("Playlists"))
        .put("order", JSONObject.NULL)
        .put("textFilter", "")
        .put("features", org.json.JSONArray().put("LIKED_SONGS").put("YOUR_EPISODES_V2")
            .put("PRERELEASES").put("EVENTS"))
        .put("limit", limit)
        .put("offset", offset)
        .put("flatten", true)
        .put("expandedFolders", org.json.JSONArray())
        .put("folderUri", JSONObject.NULL)
        .put("includeFoldersWhenFlattening", false)

    fun playlistVariables(id: String, offset: Int, limit: Int): JSONObject = JSONObject()
        .put("uri", "spotify:playlist:$id")
        .put("offset", offset)
        .put("limit", limit)
        .put("enableWatchFeedEntrypoint", false)

    fun likedSongsVariables(offset: Int, limit: Int): JSONObject = JSONObject()
        .put("offset", offset)
        .put("limit", limit)
}
