package com.auralis.music.windows.search

import com.auralis.music.data.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class GuestSearchTest {
    @Test fun publicSearchCanRecoverFromRejectedMusicRouteDuringBackgroundCooldown() = runBlocking {
        val pause = YouTubeBackoff::class.java.getDeclaredField("pausedUntil").apply { isAccessible = true }
        val original = pause.getLong(YouTubeBackoff)
        val hosts = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertNull(request.header("Authorization"))
            assertNull(request.header("Cookie"))
            hosts += request.url.host
            val code = if (request.url.host == "music.youtube.com") 403 else 200
            val json = if (code == 403) "rejected" else """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"abcdefghijk"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Guest song"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Guest Performer"}]}}}]}}]}}]}}}}]}}}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test")
                .body(json.toResponseBody()).build()
        }.build()
        try {
            YouTubeBackoff.trip()
            val result = UserSearchRequest.run { InnerTubeClient(client).search("Guest song") }
            assertFalse(result.requestFailed)
            assertEquals("Guest song", result.songs.single().title)
            assertEquals(listOf("music.youtube.com", "www.youtube.com"), hosts)
            assertTrue(YouTubeBackoff.active)
            assertFailsWith<java.io.IOException> {
                client.searchBody(Request.Builder().url("https://music.youtube.com/").build(), 1000)
            }
            assertEquals(2, hosts.size)
        } finally { pause.setLong(YouTubeBackoff, original) }
    }
}
