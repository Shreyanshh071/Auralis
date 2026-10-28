package com.auralis.music.windows.home

import com.auralis.music.windows.profile.youtubeHomeAccountKey
import org.json.JSONObject
import kotlin.test.*

class YouTubeHomeCacheTest {
    @Test fun cachedFeedIsScopedToSelectedAccountAndRetainsIndependentHistory() {
        val directory = kotlin.io.path.createTempDirectory("youtube-home-cache").toFile()
        try {
            val cache = YouTubeHomeCache(directory)
            val first = youtubeHomeAccountKey("SAPISID=one; LOGIN_INFO=login; VISITOR_INFO1_LIVE=old", "0")
            assertEquals(first, youtubeHomeAccountKey("SAPISID=one; LOGIN_INFO=login; VISITOR_INFO1_LIVE=new", "0"))
            val other = youtubeHomeAccountKey("SAPISID=two; LOGIN_INFO=other", "0")
            val switched = youtubeHomeAccountKey("SAPISID=one; LOGIN_INFO=login", "1")
            cache.write(first, "home", JSONObject().put("feed", "first"))
            cache.write(first, "history", JSONObject().put("recent", "song"))
            assertEquals("first", cache.read(first)!!.getJSONObject("home").getString("feed"))
            assertEquals("song", cache.read(first)!!.getJSONObject("history").getString("recent"))
            assertNull(cache.read(other)); assertNull(cache.read(switched))
            assertTrue(directory.listFiles()!!.none { it.name.contains("login") || it.name.contains("SAPISID") })
            assertNull(cache.read("../account-session"))
        } finally { directory.deleteRecursively() }
    }
}
