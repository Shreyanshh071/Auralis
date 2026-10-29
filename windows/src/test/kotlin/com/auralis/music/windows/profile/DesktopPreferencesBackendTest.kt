package com.auralis.music.windows.profile

import com.auralis.music.data.sync.*
import com.auralis.music.windows.together.FirestoreRest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*

class DesktopPreferencesBackendTest {
    @Test fun pcProfileNeverReplacesPhoneSettingsOrLibraryAndRetriesConcurrentWrites() = runBlocking {
        val phone = JSONObject().put("a.mini_player_bg_style", JSONObject().put("value", "Live Mesh").put("revision", 3).put("device", "phone"))
        val unknown = JSONObject().put("future-setting", JSONObject().put("value", "future"))
        val document = JSONObject().put("favorites", JSONArray().put("keep-library"))
            .put("preferences", JSONObject().put("android", phone).put("windows", unknown))
        var revision = 1
        var commits = 0
        val api = FirestoreRest("project", "key") { url, _, body, _ -> when {
            url.contains("signInWithPassword") -> JSONObject().put("idToken", "token").put("refreshToken", "refresh")
                .put("localId", "user").put("expiresIn", "3600")
            url.endsWith("/users/user") -> JSONObject().put("fields", FirestoreRest.encodeFields(document))
                .put("updateTime", "2026-10-29T00:00:0${revision}Z")
            url.endsWith(":commit") -> {
                commits++
                if (commits == 1) {
                    document.getJSONArray("favorites").put("concurrent-library-change")
                    revision++
                    throw FirestoreRest.ServiceException(412, "Conflict")
                }
                val write = body!!.getJSONArray("writes").getJSONObject(0)
                assertEquals("preferences.windows", write.getJSONObject("updateMask").getJSONArray("fieldPaths").getString(0))
                assertEquals("2026-10-29T00:00:02Z", write.getJSONObject("currentDocument").getString("updateTime"))
                val fields = FirestoreRest.decodeFields(write.getJSONObject("update").getJSONObject("fields"))
                assertFalse(fields.has("favorites"))
                assertFalse(fields.getJSONObject("preferences").has("android"))
                document.getJSONObject("preferences").put("windows", fields.getJSONObject("preferences").getJSONObject("windows"))
                JSONObject().put("commitTime", "2026-10-29T00:00:03Z")
            }
            else -> error("Unexpected endpoint")
        } }.apply { signInEmail("user@example.com", "fixture-password") }
        val result = DesktopPreferencesBackend(api).exchange("user", emptyMap(), mapOf("a.mini_player_bg_style" to "Blur"))
        assertEquals("Blur", result["a.mini_player_bg_style"]?.value)
        assertEquals(2, commits)
        assertEquals("Live Mesh", document.getJSONObject("preferences").getJSONObject("android")
            .getJSONObject("a.mini_player_bg_style").getString("value"))
        assertTrue(document.getJSONObject("preferences").getJSONObject("windows").has("future-setting"))
        assertEquals(2, document.getJSONArray("favorites").length())
    }
}
