package com.auralis.music.windows.profile

import com.auralis.music.windows.together.FirestoreRest
import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class SessionPersistenceTest {
    @Test fun refreshedCookiesRetainOnlyTheSameAccountAndSelectedChannel() {
        val dir = Files.createTempDirectory("cookie-rotation").toFile()
        try {
            val accounts = YouTubeAccountsStore(ProtectedYouTubeStore(File(dir, "accounts.properties")))
            val cookie = "LOGIN_INFO=old; SAPISID=identity; VISITOR_INFO1_LIVE=old-visitor"
            val first = accounts.save(YouTubeIdentity("First", "@first", null), cookie, YouTubeScope(authUser = "0"), emptyList())
            val second = accounts.save(YouTubeIdentity("Second", "@second", null), cookie, YouTubeScope(authUser = "1"), emptyList())
            val refreshed = "VISITOR_INFO1_LIVE=new-visitor; SAPISID=identity; LOGIN_INFO=new"
            assertEquals(first, accounts.findCookie(refreshed, "0"))
            assertEquals(second, accounts.findCookie(refreshed, "1"))
            assertNull(accounts.findCookie("LOGIN_INFO=other; SAPISID=other", "0"))
            assertNull(accounts.findCookie("", "0"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun protectedCookiesAreReadableImmediatelyAfterSaveAndReplacement() {
        val dir = Files.createTempDirectory("session-persistence").toFile()
        try {
            val file = File(dir, "session.properties")
            val store = ProtectedYouTubeStore(file)
            for (cookie in listOf("LOGIN_INFO=first; SAPISID=secret", "LOGIN_INFO=second; SAPISID=updated")) {
                store.putStrings(mapOf("cookie" to cookie, "account_label" to "Listener"))
                assertEquals(cookie, ProtectedYouTubeStore(file).getString("cookie", ""))
                assertFalse(file.readText().contains("SAPISID"))
                assertEquals("Listener", ProtectedYouTubeStore(file).getString("account_label", ""))
            }
            store.clear()
            assertEquals("", ProtectedYouTubeStore(file).getString("cookie", ""))
        } finally { dir.deleteRecursively() }
    }

    @Test fun temporaryNetworkFailureKeepsSavedProfileAndRetriesWithoutLogin() = runBlocking {
        val dir = Files.createTempDirectory("account-restore").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val file = File(dir, "account.bin")
            val saved = JSONObject().put("refreshToken", "saved-token").put("profile", JSONObject()
                .put("uid", "uid").put("displayName", "Listener").put("email", "test@example.com"))
            file.writeBytes(Crypt32Util.cryptProtectData(saved.toString().toByteArray()))
            val recovered = CompletableDeferred<Unit>()
            var attempts = 0
            var profileAttempts = 0
            val api = FirestoreRest("project", "key") { url, _, body, _ ->
                if (url.contains("securetoken")) {
                    attempts++
                    if (attempts == 1) throw java.io.IOException("Offline")
                    if (attempts == 3) assertEquals("new-token", body!!.getString("refresh_token"))
                    JSONObject().put("id_token", "id").put("refresh_token", "new-token").put("user_id", "uid").put("expires_in", "3600")
                } else {
                    profileAttempts++
                    if (profileAttempts == 1) throw java.io.IOException("Profile temporarily unavailable")
                    recovered.complete(Unit)
                    JSONObject("""{"users":[{"email":"test@example.com","displayName":"Listener"}]}""")
                }
            }
            val account = DesktopAccount(file, scope, api, restoreRetryMs = 500)
            withTimeout(5000) {
                while (attempts == 0 || account.state.value.message == null) delay(10)
            }
            assertEquals("Listener", account.state.value.profile?.displayName)
            assertTrue(file.isFile)
            withTimeout(5000) { recovered.await() }
            withTimeout(5000) {
                while (!String(Crypt32Util.cryptUnprotectData(file.readBytes())).contains("new-token")) delay(10)
            }
            assertEquals(3, attempts)
            assertEquals("Listener", account.state.value.profile?.displayName)
        } finally { scope.coroutineContext[Job]?.cancelAndJoin(); dir.deleteRecursively() }
    }
}
