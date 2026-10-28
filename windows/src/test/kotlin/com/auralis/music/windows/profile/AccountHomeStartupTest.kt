package com.auralis.music.windows.profile

import com.auralis.music.windows.together.FirestoreRest
import com.auralis.music.desktop.library.DesktopLibraryRepository
import com.auralis.music.desktop.library.DesktopListeningStats
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AccountHomeStartupTest {
    @Test fun recentListeningPopulatesHomeBeforeLibraryAndFullHistoryFinish() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("account-home-startup").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val release = CountDownLatch(1)
        val events = JSONObject()
        (1..5).forEach { i -> events.put("$i", JSONObject().put("t", "song$i").put("ti", "Song $i")
            .put("ar", "Artist $i").put("ms", 300_000L).put("ts", 1_700_000_000_000L + i)) }
        val day = JSONObject().put("name", "projects/test/databases/(default)/documents/users/uid/listening/2026-10-26")
            .put("fields", FirestoreRest.encodeFields(JSONObject().put("events", events)))
        val docs = JSONObject().put("documents", JSONArray().put(day))
        var boundedRequests = 0
        val api = FirestoreRest("test", "key") { url, _, _, _ ->
            when {
                url.contains("signInWithPassword") -> JSONObject().put("idToken", "id").put("refreshToken", "refresh")
                    .put("localId", "uid").put("expiresIn", "3600")
                url.contains("accounts:lookup") -> JSONObject("""{"users":[{"email":"test@example.com","displayName":"Listener"}]}""")
                url.contains("/listening?") && url.contains("orderBy=") -> {
                    assertTrue(url.contains("pageSize=7")); boundedRequests++; docs
                }
                url.contains("/listening?") -> {
                    check(release.await(10, TimeUnit.SECONDS)); docs
                }
                url.endsWith("/users/uid") -> {
                    check(release.await(10, TimeUnit.SECONDS)); JSONObject().put("fields", JSONObject())
                }
                else -> error("Unexpected test request")
            }
        }
        try {
            val account = DesktopAccount(File(dir, "session.bin"), scope, api)
            account.signIn("test@example.com", "test-password", false)
            withTimeout(3000) { account.state.first { it.profile != null } }
            val stats = DesktopListeningStats(File(dir, "listens.json"))
            val library = DesktopLibraryRepository(File(dir, "library.json"))
            val history = com.auralis.music.desktop.library.DesktopHistoryRepository(File(dir, "history.json"), library)
            val sync = async(Dispatchers.IO) { account.pullCloudBackup(library, stats, history) }
            withTimeout(2000) { stats.listens.first { it.size == 5 } }
            assertFalse(sync.isCompleted, "Home's songs must arrive while the library and full history are still blocked")
            assertEquals(1, boundedRequests)
            assertEquals(5, stats.listens.value.map { it.track.artist }.distinct().size)
            release.countDown()
            withTimeout(3000) { sync.await() }
            assertEquals(5, stats.listens.value.size, "The full sync must not duplicate the preview's listening sessions")
            val imported = history.getHistory().first()
            assertEquals(5, imported.size)
            assertEquals("song5", imported.first().track.id)
            assertEquals(1_700_000_000_005L, imported.first().playedAt)
            assertTrue(history.getPlayCounts().isEmpty(), "Sync must not count imported history as new playback")
        } finally { release.countDown(); scope.cancel(); dir.deleteRecursively() }
    }
}
