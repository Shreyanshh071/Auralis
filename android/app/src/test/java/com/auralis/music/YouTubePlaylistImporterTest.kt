package com.auralis.music

import com.auralis.music.data.network.YouTubePlaylistImporter
import com.auralis.music.data.network.YouTubeShortsFilter
import com.auralis.music.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlaylistImporterTest {

    @Test
    fun shortVideoClassificationRejectsShortsAndGamingButKeepsShortMusic() {
        val gaming = Track(id = "gaming", title = "Trigger Discipline", duration = 49)
        val music = Track(id = "song", title = "Short song", duration = 49)
        val longSong = music.copy(duration = 210)
        val gamingPlayer = org.json.JSONObject("""{
            "microformat":{"playerMicroformatRenderer":{"category":"Gaming","uploadDate":"2026-01-01"}},
            "videoDetails":{"lengthSeconds":"49"}
        }""")
        val portraitPlayer = org.json.JSONObject("""{
            "microformat":{"playerMicroformatRenderer":{"category":"Music","uploadDate":"2026-01-01"}},
            "videoDetails":{"lengthSeconds":"49"},
            "streamingData":{"adaptiveFormats":[{"width":1080,"height":1920}]}
        }""")
        val landscapePlayer = org.json.JSONObject("""{
            "microformat":{"playerMicroformatRenderer":{"category":"Music","uploadDate":"2026-01-01"}},
            "videoDetails":{"lengthSeconds":"49"},
            "streamingData":{"adaptiveFormats":[{"width":1920,"height":1080}]}
        }""")

        assertTrue(YouTubeShortsFilter.isShortOrNonMusic(gaming, gamingPlayer))
        assertTrue(YouTubeShortsFilter.isShortOrNonMusic(music, portraitPlayer))
        assertFalse(YouTubeShortsFilter.isShortOrNonMusic(music, landscapePlayer))
        assertFalse(YouTubeShortsFilter.isShortOrNonMusic(longSong, gamingPlayer))
    }

    @Test
    fun shortsNavigationMarkerIsRejectedWithoutPlayerLookup() {
        val item = org.json.JSONObject("""{"navigationEndpoint":{"reelWatchEndpoint":{"videoId":"short"}}}""")
        val normal = org.json.JSONObject("""{"navigationEndpoint":{"watchEndpoint":{"videoId":"song"}}}""")
        assertTrue(YouTubeShortsFilter.hasShortsMarker(item))
        assertFalse(YouTubeShortsFilter.hasShortsMarker(normal))
    }

    @Test
    fun unlinkedSubMinuteUserUploadIsNotImportedAsMusic() {
        val userClip = org.json.JSONObject("""{
            "playlistItemData":{"videoId":"clip"},
            "navigationEndpoint":{"watchEndpoint":{"watchEndpointMusicSupportedConfigs":{
                "watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_UGC"}}}},
            "flexColumns":[{}, {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Creator"}]}}}]
        }""")
        val releasedSong = org.json.JSONObject("""{
            "navigationEndpoint":{"watchEndpoint":{"watchEndpointMusicSupportedConfigs":{
                "watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_UGC"}}}},
            "flexColumns":[{}, {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                {"text":"Artist"},{"text":"Album","navigationEndpoint":{"browseEndpoint":{"browseId":"MPRE123"}}}
            ]}}}]
        }""")
        assertTrue(YouTubeShortsFilter.isUnlinkedShortUgc(userClip, 49))
        assertFalse(YouTubeShortsFilter.isUnlinkedShortUgc(userClip, 180))
        assertFalse(YouTubeShortsFilter.isUnlinkedShortUgc(releasedSong, 49))
    }

    @Test
    fun extractPlaylistId_fromYouTubeMusicUrl_extractsListParam() {
        val url = "https://music.youtube.com/playlist?list=PLrAlXl54T_V9j87H7A4mZf2q3w5e6r7t"
        assertEquals("PLrAlXl54T_V9j87H7A4mZf2q3w5e6r7t", YouTubePlaylistImporter.extractPlaylistId(url))
    }

    @Test
    fun extractPlaylistId_fromYouTubeMusicWatchUrl_extractsListParam() {
        val url = "https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234567890abcdef"
        assertEquals("PL1234567890abcdef", YouTubePlaylistImporter.extractPlaylistId(url))
    }

    @Test
    fun extractPlaylistId_fromYouTubeMusicBrowseUrl_extractsBrowseParam() {
        val url = "https://music.youtube.com/browse/VLOLAK5uy_k1234567890"
        assertEquals("OLAK5uy_k1234567890", YouTubePlaylistImporter.extractPlaylistId(url))
    }

    @Test
    fun extractPlaylistId_fromStandardYouTubeUrl_extractsListParam() {
        val url = "https://www.youtube.com/playlist?list=OLAK5uy_k1234567890"
        assertEquals("OLAK5uy_k1234567890", YouTubePlaylistImporter.extractPlaylistId(url))
    }

    @Test
    fun extractPlaylistId_fromStandardYouTubeWatchUrl_extractsListParam() {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234567890abcdef"
        assertEquals("PL1234567890abcdef", YouTubePlaylistImporter.extractPlaylistId(url))
    }

    @Test
    fun extractPlaylistId_fromShortYouTubeUrl_extractsListParam() {
        val url = "https://youtu.be/dQw4w9WgXcQ?list=PL1234567890abcdef"
        assertEquals("PL1234567890abcdef", YouTubePlaylistImporter.extractPlaylistId(url))
    }

    @Test
    fun extractPlaylistId_fromDirectIdWithoutDomain_extractsCleanId() {
        val id = "PL1234567890abcdef"
        assertEquals("PL1234567890abcdef", YouTubePlaylistImporter.extractPlaylistId(id))
    }

    @Test
    fun extractPlaylistId_fromInvalidUrl_returnsNull() {
        val url = "https://example.com/not-a-playlist"
        assertNull(YouTubePlaylistImporter.extractPlaylistId(url))
    }

    @Test
    fun isYouTubeMusicUrl_identifiesCorrectHosts() {
        assertTrue(YouTubePlaylistImporter.isYouTubeMusicUrl("https://music.youtube.com/playlist?list=PL123"))
        assertTrue(YouTubePlaylistImporter.isYouTubeMusicUrl("music.youtube.com/playlist?list=PL123"))
        assertFalse(YouTubePlaylistImporter.isYouTubeMusicUrl("https://www.youtube.com/playlist?list=PL123"))
        assertFalse(YouTubePlaylistImporter.isYouTubeMusicUrl("https://youtube.com/playlist?list=PL123"))
        assertFalse(YouTubePlaylistImporter.isYouTubeMusicUrl("https://youtu.be/abc"))
    }

    @Test
    fun isStandardYouTubeUrl_identifiesStandardHosts() {
        assertTrue(YouTubePlaylistImporter.isStandardYouTubeUrl("https://www.youtube.com/playlist?list=PL123"))
        assertTrue(YouTubePlaylistImporter.isStandardYouTubeUrl("https://youtube.com/playlist?list=PL123"))
        assertTrue(YouTubePlaylistImporter.isStandardYouTubeUrl("https://m.youtube.com/playlist?list=PL123"))
        assertTrue(YouTubePlaylistImporter.isStandardYouTubeUrl("https://youtu.be/abc"))
        assertFalse(YouTubePlaylistImporter.isStandardYouTubeUrl("https://music.youtube.com/playlist?list=PL123"))
    }

    @Test
    fun upgradeCoverUrl_upgradesGoogleUserContentToStudioMaster1200() {
        val rawW = "https://yt3.googleusercontent.com/abc=w544-h544-l90-rj"
        assertEquals("https://yt3.googleusercontent.com/abc=w1200-h1200-l90-rj", YouTubePlaylistImporter.upgradeCoverUrl(rawW))

        val rawS = "https://yt3.googleusercontent.com/def=s192"
        assertEquals("https://yt3.googleusercontent.com/def=s1200-c", YouTubePlaylistImporter.upgradeCoverUrl(rawS))
    }

    @Test
    fun extractBestThumbnailUrl_picksHighestResolution() {
        val array = org.json.JSONArray(
            """[
                {"url":"https://yt3.googleusercontent.com/small=s60","width":60,"height":60},
                {"url":"https://yt3.googleusercontent.com/large=s576","width":576,"height":576},
                {"url":"https://yt3.googleusercontent.com/medium=s120","width":120,"height":120}
            ]"""
        )
        val best = YouTubePlaylistImporter.extractBestThumbnailUrl(array)
        assertEquals("https://yt3.googleusercontent.com/large=s1200-c", best)
    }

    @Test
    fun extractPlaylistThumbnail_fromTwoColumnResponsiveHeader_findsAndUpgradesCover() {
        val json = org.json.JSONObject(
            """{
                "contents": {
                    "twoColumnBrowseResultsRenderer": {
                        "tabs": [{
                            "tabRenderer": {
                                "content": {
                                    "sectionListRenderer": {
                                        "contents": [{
                                            "musicResponsiveHeaderRenderer": {
                                                "thumbnail": {
                                                    "musicThumbnailRenderer": {
                                                        "thumbnail": {
                                                            "thumbnails": [
                                                                {"url": "https://yt3.googleusercontent.com/art=w540-h540", "width": 540, "height": 540}
                                                            ]
                                                        }
                                                    }
                                                }
                                            }
                                        }]
                                    }
                                }
                            }
                        }]
                    }
                }
            }"""
        )
        val cover = YouTubePlaylistImporter.extractPlaylistThumbnail(json)
        assertEquals("https://yt3.googleusercontent.com/art=w1200-h1200-l90-rj", cover)
    }

    @Test
    fun extractPlaylistThumbnail_fromEditablePlaylistDetailHeader_findsNestedCover() {
        val json = org.json.JSONObject(
            """{
                "contents": {
                    "twoColumnBrowseResultsRenderer": {
                        "tabs": [{
                            "tabRenderer": {
                                "content": {
                                    "sectionListRenderer": {
                                        "contents": [{
                                            "musicEditablePlaylistDetailHeaderRenderer": {
                                                "header": {
                                                    "musicResponsiveHeaderRenderer": {
                                                        "thumbnail": {
                                                            "croppedSquareThumbnailRenderer": {
                                                                "thumbnail": {
                                                                    "thumbnails": [
                                                                        {"url": "https://yt3.googleusercontent.com/user_art=s192", "width": 192, "height": 192}
                                                                    ]
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }]
                                    }
                                }
                            }
                        }]
                    }
                }
            }"""
        )
        val cover = YouTubePlaylistImporter.extractPlaylistThumbnail(json)
        assertEquals("https://yt3.googleusercontent.com/user_art=s1200-c", cover)
    }

    @Test
    fun extractPlaylistThumbnail_fromSingleColumnBrowseResults_findsCover() {
        val json = org.json.JSONObject(
            """{
                "contents": {
                    "singleColumnBrowseResultsRenderer": {
                        "tabs": [{
                            "tabRenderer": {
                                "content": {
                                    "sectionListRenderer": {
                                        "contents": [{
                                            "musicDetailHeaderRenderer": {
                                                "thumbnail": {
                                                    "musicThumbnailRenderer": {
                                                        "thumbnail": {
                                                            "thumbnails": [
                                                                {"url": "https://yt3.googleusercontent.com/sc_art=s300", "width": 300, "height": 300}
                                                            ]
                                                        }
                                                    }
                                                }
                                            }
                                        }]
                                    }
                                }
                            }
                        }]
                    }
                }
            }"""
        )
        val cover = YouTubePlaylistImporter.extractPlaylistThumbnail(json)
        assertEquals("https://yt3.googleusercontent.com/sc_art=s1200-c", cover)
    }
}
