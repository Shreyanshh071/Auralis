package com.auralis.music.data.network

import com.auralis.music.domain.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import org.json.JSONObject

class YouTubeShortsFilterTest {
    @Test fun cachedPreviewsHideUncheckedVideosAndKeepKnownShortsBlockedOffline() {
        val previous = YouTubeShortsFilter.classificationStore
        val now = System.currentTimeMillis()
        val values = mutableMapOf("shortcache1" to "${now - 1000}|true", "audio-safe1" to "${now + 60000}|false")
        YouTubeShortsFilter.classificationStore = object : com.auralis.music.util.KeyValueStore {
            override fun getString(key: String, default: String) = values[key] ?: default
            override fun putStrings(values: Map<String, String>) {}
            override fun clear() { values.clear() }
        }
        try {
            val shown = YouTubeShortsFilter.filterCached(listOf(
                Track("shortcache1", duration = 49), Track("newshort001", duration = 49),
                Track("audio-safe1", duration = 49), Track("local:audio", duration = 30)))
            kotlin.test.assertEquals(listOf("audio-safe1", "local:audio"), shown.map { it.id })
            assertTrue(YouTubeShortsFilter.filterCached(listOf(Track("shortcache1", duration = 600))).isEmpty())
        } finally { YouTubeShortsFilter.classificationStore = previous }
    }

    @Test fun shortSongsAndInterludesAreNotRejectedByDurationAlone() {
        val track = Track("short-song", "Interlude", duration = 49)
        val landscape = JSONObject("""{"microformat":{"playerMicroformatRenderer":{"category":"Music","uploadDate":"2026-01-01"}},
            "videoDetails":{"lengthSeconds":"49"},"streamingData":{"adaptiveFormats":[{"width":1920,"height":1080}]}}""")
        val portrait = JSONObject(landscape.toString()).apply {
            getJSONObject("streamingData").getJSONArray("adaptiveFormats").getJSONObject(0).put("width", 1080).put("height", 1920)
        }
        assertFalse(YouTubeShortsFilter.isShortOrNonMusic(track, landscape))
        assertFalse(YouTubeShortsFilter.isShortOrNonMusic(track, null))
        assertTrue(YouTubeShortsFilter.isShortOrNonMusic(track, portrait))
        assertTrue(YouTubeShortsFilter.isShortOrNonMusic(track,
            JSONObject("""{"microformat":{"playerMicroformatRenderer":{"category":"Gaming"}}}""")))
    }

    @Test fun liveReportedGamingShortIsExcluded() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("AURALIS_LIVE_SHORTS") == "1")
        val tracks = YouTubeShortsFilter.filter(listOf(Track("H467W9K843E", "Trigger Discipline", "Horcus", duration = 49)))
        assertTrue(tracks.isEmpty(), "The reported Short must not be offered for playback")
    }
}
