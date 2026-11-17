package com.auralis.music.windows.profile

import com.auralis.music.desktop.library.DesktopLibraryRepository
import com.auralis.music.desktop.library.DesktopListeningStats
import com.auralis.music.domain.model.Track
import com.auralis.music.windows.together.FirestoreRest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.test.*

class AccountCloudRetryTest {
    private class Fixture {
        val dir = java.nio.file.Files.createTempDirectory("account-cloud-retry").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val library = DesktopLibraryRepository(File(dir, "library.json"))
        val stats = DesktopListeningStats(File(dir, "listens.json"))
        var rejectReads = false
        var rejectWrites = false
        var writes = 0
        var cloud = JSONObject().put("favorites", JSONArray())
        val api = FirestoreRest("test", "key") { url, _, body, _ -> when {
            url.contains("signInWithPassword") -> JSONObject().put("idToken", "id").put("refreshToken", "refresh")
                .put("localId", "uid").put("expiresIn", "3600")
            url.contains("accounts:lookup") -> JSONObject("""{"users":[{"email":"test@example.com","displayName":"Listener"}]}""")
            url.contains("/listening?") -> JSONObject().put("documents", JSONArray())
            url.endsWith("/users/uid") -> {
                if (rejectReads) throw FirestoreRest.ServiceException(429, "Quota exceeded.")
                JSONObject().put("fields", FirestoreRest.encodeFields(cloud))
            }
            url.endsWith(":commit") -> {
                if (rejectWrites) throw FirestoreRest.ServiceException(429, "Quota exceeded.")
                writes++
                cloud = FirestoreRest.decodeFields(body!!.getJSONArray("writes").getJSONObject(0).getJSONObject("update").getJSONObject("fields"))
                JSONObject()
            }
            else -> error("Unexpected test request")
        } }
        val account = DesktopAccount(File(dir, "session.bin"), scope, api)
        suspend fun signIn() {
            account.signIn("test@example.com", "password", false)
            withTimeout(5000) { account.state.first { it.profile != null } }
        }
        fun remoteLike(id: String) {
            cloud.put("favorites", JSONArray().put(JSONObject().put("id", id).put("title", id).put("artist", "Artist")))
        }
        fun close() { scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun failedFreshRestoreRetriesAndIdleDeviceReceivesRemoteLikesAndRemovals() = runBlocking {
        val f = Fixture()
        try {
            f.signIn()
            f.rejectReads = true
            assertFalse(f.account.syncCloudLibrary(f.library, f.stats))
            assertTrue(f.account.state.value.syncMessage!!.contains("quota exceeded"))
            assertEquals(0, f.writes, "Never upload before a successful restore")
            f.rejectReads = false
            f.remoteLike("phone-like")
            f.cloud.put("playlists", JSONArray().put(JSONObject()
                .put("id", "imported:youtube_music:phone-playlist").put("title", "Phone playlist")
                .put("createdAt", 1_700_000_000_000L).put("isCustom", true)
                .put("tracks", JSONArray().put(JSONObject().put("id", "song").put("title", "Song").put("artist", "Artist")))))
            assertTrue(f.account.syncCloudLibrary(f.library, f.stats))
            assertNull(f.account.state.value.syncMessage)
            assertEquals(listOf("song"), f.library.getPlaylist("imported:youtube_music:phone-playlist").first()!!.tracks.map { it.id })
            assertEquals(listOf("phone-like"), f.library.getFavoriteTracks().first().map { it.id })
            // No local edit: the next background check still needs to fetch remote edits.
            f.remoteLike("new-phone-like")
            assertTrue(f.account.syncCloudLibrary(f.library, f.stats))
            assertEquals(listOf("new-phone-like"), f.library.getFavoriteTracks().first().map { it.id })
            assertEquals(0, f.writes, "Unchanged cloud libraries must not consume write quota")
        } finally { f.close() }
    }

    @Test fun writeQuotaStillAppliesRemoteChangesAndRetainsPendingLocalLikeForRetry() = runBlocking {
        val f = Fixture()
        try {
            f.signIn()
            assertTrue(f.account.syncCloudLibrary(f.library, f.stats))
            f.library.setFavorite(Track("local-like", "Local", "Artist"), true)
            f.remoteLike("phone-like")
            f.rejectWrites = true
            assertFalse(f.account.syncCloudLibrary(f.library, f.stats))
            assertEquals(setOf("local-like", "phone-like"), f.library.getFavoriteTracks().first().map { it.id }.toSet())
            f.rejectWrites = false
            assertTrue(f.account.syncCloudLibrary(f.library, f.stats))
            val favorites = f.cloud.getJSONArray("favorites")
            assertEquals(setOf("local-like", "phone-like"), (0 until favorites.length()).map { favorites.getJSONObject(it).getString("id") }.toSet())
            assertNull(f.account.state.value.syncMessage)
            assertEquals(1, f.writes)
        } finally { f.close() }
    }
}
