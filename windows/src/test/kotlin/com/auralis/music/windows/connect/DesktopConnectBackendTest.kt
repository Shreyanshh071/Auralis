package com.auralis.music.windows.connect

import com.auralis.music.data.connect.ConnectCodec
import com.auralis.music.data.connect.ConnectSession
import com.auralis.music.data.connect.ConnectWrite
import com.auralis.music.data.connect.ServerClock
import com.auralis.music.windows.together.FirestoreRest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopConnectBackendTest {
    private class Fixture(private val contention: Boolean) {
        var revision = 1L
        var commits = 0
        val api = FirestoreRest("test-project", "test-key") { url, _, body, _ ->
            when {
                url.contains("signInWithPassword") -> JSONObject()
                    .put("idToken", "test-token").put("refreshToken", "test-refresh")
                    .put("localId", "account").put("expiresIn", "3600")
                url.endsWith("/connect/session") -> {
                    val fields = DesktopConnectBackend.toJson(ConnectCodec.encodeSession(
                        ConnectSession(version = revision, queueRev = 0)
                    )).first
                    JSONObject().put("fields", FirestoreRest.encodeFields(fields))
                        .put("updateTime", "2026-10-23T00:00:0${revision}Z")
                }
                url.endsWith(":commit") -> {
                    commits++
                    if (commits == 1) {
                        if (contention) revision++
                        throw FirestoreRest.ServiceException(403, "Missing or insufficient permissions.")
                    }
                    val write = body!!.getJSONArray("writes").getJSONObject(0)
                    val fields = FirestoreRest.decodeFields(write.getJSONObject("update").getJSONObject("fields"))
                    assertEquals(revision + 1, fields.getLong("version"))
                    assertEquals("2026-10-23T00:00:0${revision}Z", write.getJSONObject("currentDocument").getString("updateTime"))
                    JSONObject().put("commitTime", "2026-10-23T00:00:05Z")
                }
                else -> error("Unexpected request: $url")
            }
        }.apply { signInEmail("test@example.com", "test-password") }
        val backend = DesktopConnectBackend(api, "account", ServerClock { 0L })
    }

    @Test fun retriesRuleDenialOnlyWhenServerRevisionChanged() = runBlocking {
        val f = Fixture(contention = true)
        val result = f.backend.update { cur ->
            ConnectWrite(cur.session!!.copy(version = cur.session!!.version + 1))
        }
        assertEquals(3L, result!!.session!!.version)
        assertEquals(2, f.commits)
    }

    @Test fun genuinePermissionDenialIsNotRetried() = runBlocking {
        val f = Fixture(contention = false)
        assertFailsWith<FirestoreRest.ServiceException> {
            f.backend.update { cur -> ConnectWrite(cur.session!!.copy(version = 2)) }
        }
        assertEquals(1, f.commits)
    }
}
