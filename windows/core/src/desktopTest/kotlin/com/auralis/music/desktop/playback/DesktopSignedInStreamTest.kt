package com.auralis.music.desktop.playback

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSignedInStreamTest {
    private val remix = DesktopSignedInStream.CLIENTS.first()

    @Test
    fun `player request speaks for the account and carries the session token`() {
        val body = DesktopSignedInStream.playerPayload("vid", remix, 20500, "session-pot", "visitor", onBehalfOfUser = "account-id")
        val client = body.getJSONObject("context").getJSONObject("client")
        assertEquals("WEB_REMIX", client.getString("clientName"))
        assertEquals("visitor", client.getString("visitorData"))
        assertEquals("account-id", body.getJSONObject("context").getJSONObject("user").getString("onBehalfOfUser"))
        assertEquals("session-pot", body.getJSONObject("serviceIntegrityDimensions").getString("poToken"))
        assertEquals(20500, body.getJSONObject("playbackContext").getJSONObject("contentPlaybackContext").getInt("signatureTimestamp"))
        assertTrue(body.getBoolean("contentCheckOk") && body.getBoolean("racyCheckOk"))
    }

    @Test
    fun `personal account sends no onBehalfOfUser`() {
        val body = DesktopSignedInStream.playerPayload("vid", remix, 1, "p", "v")
        assertFalse(body.getJSONObject("context").getJSONObject("user").has("onBehalfOfUser"))
    }

    @Test
    fun `headers scope the cookie to the account and channel page`() {
        val account = DesktopSignedInStream.Account("LOGIN_INFO=x; SAPISID=abc; __Secure-3PAPISID=abc", "2", "page-1", "id", "v")
        val headers = DesktopSignedInStream.authHeaders(account, "https://music.youtube.com", "v")
        assertEquals("2", headers["X-Goog-AuthUser"])
        assertEquals("page-1", headers["X-Goog-PageId"])
        assertEquals("v", headers["X-Goog-Visitor-Id"])
        assertTrue(headers.getValue("Authorization").startsWith("SAPISIDHASH "))
        assertTrue("SAPISID3PHASH " in headers.getValue("Authorization"))
    }

    @Test
    fun `picks the highest bitrate audio and ignores video`() {
        val json = JSONObject().put("streamingData", JSONObject().put("adaptiveFormats", JSONArray()
            .put(JSONObject().put("itag", 137).put("mimeType", "video/mp4").put("bitrate", 9_000_000))
            .put(JSONObject().put("itag", 140).put("mimeType", "audio/mp4; codecs=\"mp4a\"").put("bitrate", 130_000))
            .put(JSONObject().put("itag", 251).put("mimeType", "audio/webm; codecs=\"opus\"").put("bitrate", 150_000))))
        assertEquals(251, DesktopSignedInStream.pickAudio(json, remix)?.getInt("itag"))
    }

    @Test
    fun `refusal yields nothing and reads YouTube's signed-in verdict`() {
        val json = JSONObject()
            .put("playabilityStatus", JSONObject().put("status", "LOGIN_REQUIRED").put("reason", "Sign in to confirm your age"))
            .put("responseContext", JSONObject().put("serviceTrackingParams", JSONArray().put(JSONObject()
                .put("params", JSONArray().put(JSONObject().put("key", "logged_in").put("value", "0"))))))
        assertNull(DesktopSignedInStream.pickAudio(json, remix))
        assertEquals("0", DesktopSignedInStream.loggedIn(json))
        assertEquals("?", DesktopSignedInStream.loggedIn(JSONObject()))
    }
}
