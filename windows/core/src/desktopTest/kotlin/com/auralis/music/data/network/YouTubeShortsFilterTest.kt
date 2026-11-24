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
        val values = mutableMapOf("shortcache1" to "${now - 1000}|true|2", "audio-safe1" to "${now + 60000}|false|2")
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

    @Test fun verdictsFromTheSquareCoverRuleAreCheckedAgain() {
        val previous = YouTubeShortsFilter.classificationStore
        val now = System.currentTimeMillis()
        // Written by the old rule, which took a song's square album cover for a Short.
        val values = mutableMapOf("oldverdict1" to "${now + 60000}|true")
        YouTubeShortsFilter.classificationStore = object : com.auralis.music.util.KeyValueStore {
            override fun getString(key: String, default: String) = values[key] ?: default
            override fun putStrings(values: Map<String, String>) {}
            override fun clear() { values.clear() }
        }
        try {
            // Unknown again: kept off quick previews until re-checked, but no longer hidden for good.
            kotlin.test.assertEquals(listOf("oldverdict1"), YouTubeShortsFilter.filterCached(listOf(Track("oldverdict1", duration = 600))).map { it.id })
        } finally { YouTubeShortsFilter.classificationStore = previous }
    }

    @Test fun songUploadsWithSquareCoversAreNotShorts() {
        // EsDeeKid "Century" (vVllJT_n380): 110 s, uploaded 2025, square cover art, a Topic channel.
        val track = Track("vVllJT_n380", "Century", "EsDeeKid", duration = 110)
        fun player(author: String, eligible: Boolean?) = JSONObject("""{"microformat":{"playerMicroformatRenderer":{"category":"Music","uploadDate":"2025-10-30"}},
            "videoDetails":{"lengthSeconds":"110","author":"$author"},"streamingData":{"adaptiveFormats":[{"width":1080,"height":1080}]}}""").apply {
            if (eligible != null) getJSONObject("microformat").getJSONObject("playerMicroformatRenderer").put("isShortsEligible", eligible)
        }
        assertFalse(YouTubeShortsFilter.isShortOrNonMusic(track, player("EsDeeKid - Topic", null)))
        assertFalse(YouTubeShortsFilter.isShortOrNonMusic(track, player("EsDeeKid", false)))
        assertTrue(YouTubeShortsFilter.isShortOrNonMusic(track, player("EsDeeKid", true)))
        assertTrue(YouTubeShortsFilter.isShortOrNonMusic(track, player("EsDeeKid", null)))
    }

    @Test fun liveReportedGamingShortIsExcluded() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("AURALIS_LIVE_SHORTS") == "1")
        val tracks = YouTubeShortsFilter.filter(listOf(Track("H467W9K843E", "Trigger Discipline", "Horcus", duration = 49)))
        assertTrue(tracks.isEmpty(), "The reported Short must not be offered for playback")
    }
}
