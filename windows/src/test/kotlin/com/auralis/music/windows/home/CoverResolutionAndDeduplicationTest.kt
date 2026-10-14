package com.auralis.music.windows.home

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.PlaylistThumbnails
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recommendations.TrackDeduplicator
import com.auralis.music.ui.components.getOptimizedThumbnailUrl
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverResolutionAndDeduplicationTest {

    @Test
    fun thumbnailUpgradesNeverProduceDoubleHqSuffix() {
        val urls = listOf(
            "https://i.ytimg.com/vi/wc8h0MfIX6w/hqdefault.jpg?sqp=123",
            "https://i.ytimg.com/vi/wc8h0MfIX6w/mqdefault.jpg",
            "https://i.ytimg.com/vi/wc8h0MfIX6w/default.jpg",
            "https://i.ytimg.com/vi/wc8h0MfIX6w/hqhqdefault.jpg"
        )
        for (input in urls) {
            val upgraded = PlaylistThumbnails.upgradeCoverUrl(input)
            assertEquals("https://i.ytimg.com/vi/wc8h0MfIX6w/hqdefault.jpg", upgraded, "Failed for $input")
        }
    }

    @Test
    fun innerTubeClientThumbnailParsingPreservesHqdefault() {
        val client = InnerTubeClient()
        val jsonItem = JSONObject().apply {
            put("flexColumns", JSONArray().apply {
                put(JSONObject().put("musicResponsiveListItemFlexColumnRenderer", JSONObject().put("text", JSONObject().put("runs", JSONArray().apply {
                    put(JSONObject().put("text", "Oh Girl You're Mine...").put("navigationEndpoint", JSONObject().put("watchEndpoint", JSONObject().put("videoId", "wc8h0MfIX6w"))))
                }))))
                put(JSONObject().put("musicResponsiveListItemFlexColumnRenderer", JSONObject().put("text", JSONObject().put("runs", JSONArray().apply {
                    put(JSONObject().put("text", "Song • Loy Mendonsa & Alyssa Mendonsa"))
                }))))
            })
            put("thumbnail", JSONObject().put("musicThumbnailRenderer", JSONObject().put("thumbnail", JSONObject().put("thumbnails", JSONArray().apply {
                put(JSONObject().put("url", "https://i.ytimg.com/vi/wc8h0MfIX6w/hqdefault.jpg?sqp=abc"))
            }))))
        }
        val songs = mutableListOf<Track>()
        client.parseMusicListItem(jsonItem, songs, mutableListOf())
        assertEquals(1, songs.size)
        assertEquals("https://i.ytimg.com/vi/wc8h0MfIX6w/hqdefault.jpg", songs[0].thumbnail)
    }

    @Test
    fun deduplicateTracksRemovesDuplicateMusicVideosAndPrioritizesStudioRelease() {
        val studioTrack = Track(
            id = "PvM79DJ2PmM",
            title = "The Less I Know The Better",
            artist = "Tame Impala",
            album = "Currents",
            thumbnail = "https://yt3.googleusercontent.com/album.jpg"
        )
        val musicVideo = Track(
            id = "sBzrzS1Ag_g",
            title = "The Less I Know The Better (Official Video)",
            artist = "Tame Impala",
            thumbnail = "https://i.ytimg.com/vi/sBzrzS1Ag_g/hqdefault.jpg"
        )

        val deduplicated = TrackDeduplicator.deduplicateTracks(listOf(musicVideo, studioTrack))
        assertEquals(1, deduplicated.size, "Must keep only one version of the song")
        assertEquals("PvM79DJ2PmM", deduplicated[0].id, "Must prioritize the studio track over the music video")
    }

    @Test
    fun nonMusicTutorialVideosAreRecognizedAsJunk() {
        val tutorial = Track(
            id = "PYgPivGppLA",
            title = "RealMe 7 Pro Tips & Tricks",
            artist = "TechRJ"
        )
        assertTrue(TrackDeduplicator.isJunkOrNoiseTrack(tutorial), "Tips & Tricks tech video must be filtered as non-music junk")
    }

    @Test
    fun getOptimizedThumbnailUrlRepairsDoubleHqUrl() {
        val corrupted = "https://i.ytimg.com/vi/sBzrzS1Ag_g/hqhqdefault.jpg"
        val optimized = getOptimizedThumbnailUrl(corrupted)
        assertEquals("https://i.ytimg.com/vi/sBzrzS1Ag_g/hq720.jpg", optimized)
    }
}
