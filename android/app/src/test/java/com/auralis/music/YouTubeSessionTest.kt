package com.auralis.music

import com.auralis.music.data.network.YouTubeSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** The YouTube sign-in that unlocks age-restricted songs. */
class YouTubeSessionTest {

    private val signedInCookie = "VISITOR_INFO1_LIVE=abc; LOGIN_INFO=xyz; SAPISID=sapi123/ABC; __Secure-3PAPISID=three456; __Secure-1PAPISID=one789"

    @After
    fun tearDown() = YouTubeSession.signOut()

    private fun sha1(text: String) =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `a cookie counts as signed in only with LOGIN_INFO and a SAPISID`() {
        assertTrue(YouTubeSession.hasAuthCookies(signedInCookie))
        // The Google login page sets cookies before the user finishes signing in.
        assertFalse(YouTubeSession.hasAuthCookies("VISITOR_INFO1_LIVE=abc; SAPISID=sapi123"))
        assertFalse(YouTubeSession.hasAuthCookies("LOGIN_INFO=xyz; YSC=1"))
        assertFalse(YouTubeSession.hasAuthCookies(""))
    }

    @Test
    fun `auth headers use YouTube's SAPISIDHASH scheme for the request origin`() {
        YouTubeSession.save(signedInCookie, visitorData = "Cgt2aXNpdG9y", authUser = "1", accountLabel = "Test")
        val origin = "https://www.youtube.com"
        val headers = YouTubeSession.authHeaders(origin)

        assertEquals(signedInCookie, headers["Cookie"])
        assertEquals(origin, headers["X-Origin"])
        assertEquals("1", headers["X-Goog-AuthUser"])
        assertEquals("Cgt2aXNpdG9y", headers["X-Goog-Visitor-Id"])

        val parts = headers.getValue("Authorization").split(" ")
        assertEquals(listOf("SAPISIDHASH", "SAPISID1PHASH", "SAPISID3PHASH"), parts.filterIndexed { i, _ -> i % 2 == 0 })
        val (timestamp, hash) = parts[1].split("_")
        assertEquals(sha1("$timestamp sapi123/ABC $origin"), hash)
        assertEquals(sha1("$timestamp one789 $origin"), parts[3].split("_")[1])
        assertEquals(sha1("$timestamp three456 $origin"), parts[5].split("_")[1])
    }

    @Test
    fun `signed out sends nothing`() {
        YouTubeSession.signOut()
        assertFalse(YouTubeSession.isSignedIn)
        assertTrue(YouTubeSession.authHeaders("https://www.youtube.com").isEmpty())
    }

    @Test
    fun `data sync id keeps only the account part YouTube binds tokens to`() {
        assertEquals("113244556677", YouTubeSession.normalizeDataSyncId("113244556677||"))
        assertEquals("113244556677", YouTubeSession.normalizeDataSyncId("113244556677||998877"))
        assertEquals("", YouTubeSession.normalizeDataSyncId(""))
    }
}
