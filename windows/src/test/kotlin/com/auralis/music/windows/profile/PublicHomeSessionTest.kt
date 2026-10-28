package com.auralis.music.windows.profile

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PublicHomeSessionTest {
    @Test fun publicHomeUsesVisitorAndLocaleWithoutAuthentication() = runBlocking {
        var calls = 0
        val api = YouTubeMusicClient(cookie = "", transport = { _, body, headers ->
            assertFalse(headers.containsKey("Authorization"))
            assertFalse(headers.containsKey("X-Goog-AuthUser"))
            assertEquals("false", headers["X-Youtube-Bootstrap-Logged-In"])
            calls++
            if (body == null) """{"INNERTUBE_CLIENT_VERSION":"1.test","VISITOR_DATA":"visitor","LOGGED_IN":false}"""
            else {
                assertEquals("FEmusic_home", body.getString("browseId"))
                assertEquals("visitor", body.getJSONObject("context").getJSONObject("client").getString("visitorData"))
                "{}"
            }
        })
        api.resolvePublicScope()
        api.browseHome()
        assertEquals(2, calls)
    }
}
