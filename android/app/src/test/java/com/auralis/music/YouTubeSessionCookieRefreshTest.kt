package com.auralis.music

import com.auralis.music.data.network.YouTubeSession
import com.auralis.music.util.KeyValueStore
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

class YouTubeSessionCookieRefreshTest {
    private val url = "https://music.youtube.com/youtubei/v1/browse".toHttpUrl()
    private val now = System.currentTimeMillis()
    @After
    fun tearDown() = YouTubeSession.signOut()

    private fun cookie(header: String) = Cookie.parse(url, header)!!

    private val signedIn = "LOGIN_INFO=abc; SAPISID=sap; __Secure-3PAPISID=sap3; SIDCC=old; __Secure-3PSIDTS=ts-old"

    @Test
    fun rotatedValuesReplaceStaleOnesAndKeepTheRest() {
        val merged = YouTubeSession.mergeCookies(signedIn, listOf(
            cookie("SIDCC=new; Domain=.youtube.com; Path=/; Max-Age=31536000"),
            cookie("__Secure-3PSIDTS=ts-new; Domain=.youtube.com; Path=/; Secure; Max-Age=31536000"),
        ), now)
        val jar = YouTubeSession.parseCookies(merged)
        assertEquals("new", jar["SIDCC"])
        assertEquals("ts-new", jar["__Secure-3PSIDTS"])
        assertEquals("abc", jar["LOGIN_INFO"])
        assertEquals("sap", jar["SAPISID"])
    }

    @Test
    fun deletionsAndBlankValuesNeverSignOut() {
        val merged = YouTubeSession.mergeCookies(signedIn, listOf(
            cookie("SAPISID=gone; Domain=.youtube.com; Path=/; Max-Age=0"),
            cookie("LOGIN_INFO=; Domain=.youtube.com; Path=/"),
        ), now)
        assertEquals(signedIn, merged)
        assertTrue(YouTubeSession.hasAuthCookies(merged))
    }

    @Test
    fun newCookiesAreAdded() {
        val merged = YouTubeSession.mergeCookies(signedIn, listOf(cookie("YSC=fresh; Domain=.youtube.com; Path=/")), now)
        assertEquals("fresh", YouTubeSession.parseCookies(merged)["YSC"])
    }

    @Test
    fun savedSessionIsUpdatedOnlyWhenSomethingChanged() {
        val store = MemoryStore()
        YouTubeSession.init(store)
        YouTubeSession.save(signedIn, "visitor", "0", "Test", "1||")
        store.writes.clear()

        assertTrue(YouTubeSession.absorbSetCookies(listOf(cookie("SIDCC=new; Domain=.youtube.com; Path=/; Max-Age=600")), now))
        assertEquals("new", YouTubeSession.parseCookies(store.values["cookie"].orEmpty())["SIDCC"])
        assertEquals(1, store.writes.size)

        assertFalse(YouTubeSession.absorbSetCookies(listOf(cookie("SIDCC=new; Domain=.youtube.com; Path=/; Max-Age=600")), now))
        assertEquals(1, store.writes.size)
        YouTubeSession.signOut()
        assertFalse(YouTubeSession.absorbSetCookies(listOf(cookie("SIDCC=x; Domain=.youtube.com; Path=/")), now))
    }

    private class MemoryStore : KeyValueStore {
        val values = mutableMapOf<String, String>()
        val writes = mutableListOf<Map<String, String>>()
        override fun getString(key: String, default: String): String? = values[key] ?: default
        override fun putStrings(values: Map<String, String>) { writes += values; this.values += values }
        override fun clear() = values.clear()
    }
}
