package com.auralis.music.windows.profile

import com.auralis.music.data.network.YouTubeSession
import okhttp3.Cookie
import kotlin.test.*

class YouTubeSessionRefreshTest {
    private val first = "LOGIN_INFO=login; SAPISID=first; __Secure-1PSIDTS=old"
    @Test fun savesServerRotationWithoutRestartingHomeAndRejectsOldAccountResponses() {
        try {
            YouTubeSession.save(first, "", "0", "Same account")
            val revision = YouTubeSession.revision.value
            val rotated = mergeYouTubeCookies(first, listOf(Cookie.Builder().name("__Secure-1PSIDTS").value("new").domain("youtube.com").build()))
            assertTrue(YouTubeSession.refreshCookies(first, rotated))
            assertEquals("new", YouTubeSession.parseCookies(YouTubeSession.authHeaders(YouTubeMusicClient.ORIGIN).getValue("Cookie"))["__Secure-1PSIDTS"])
            assertEquals(revision, YouTubeSession.revision.value)
            YouTubeSession.save("LOGIN_INFO=other; SAPISID=second", "", "0", "Same account")
            assertTrue(YouTubeSession.revision.value > revision, "Reconnecting the same name reloads Home")
            assertFalse(YouTubeSession.refreshCookies(rotated, first), "An older request cannot restore the previous session")
            assertEquals("second", YouTubeSession.parseCookies(YouTubeSession.authHeaders(YouTubeMusicClient.ORIGIN).getValue("Cookie"))["SAPISID"])
        } finally { YouTubeSession.signOut() }
    }
    @Test fun ignoresOtherDomainsAndRemovesExpiredCookies() {
        val updates = listOf(
            Cookie.Builder().name("SAPISID").value("foreign").domain("example.com").build(),
            Cookie.Builder().name("__Secure-1PSIDTS").value("deleted").domain("youtube.com").expiresAt(1).build())
        val result = YouTubeSession.parseCookies(mergeYouTubeCookies(first, updates))
        assertEquals("first", result["SAPISID"])
        assertFalse("__Secure-1PSIDTS" in result)
    }
}
