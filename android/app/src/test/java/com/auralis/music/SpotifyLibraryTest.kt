package com.auralis.music

import com.auralis.music.data.network.SpotifyLibrary
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyLibraryTest {

    @Test
    fun `playlist trackDuration is imported when duration is absent or zero`() {
        for (duration in listOf("", "\"duration\":{\"totalMilliseconds\":0},")) {
            val items = JSONArray("""[{"itemV2":{"_uri":"spotify:track:recording","data":{
              $duration "name":"A Song","trackDuration":{"totalMilliseconds":299827},
              "artists":{"items":[{"profile":{"name":"Singer"}}]}
            }}}]""")
            val tracks = mutableListOf<com.auralis.music.domain.model.Track>()
            SpotifyLibrary.parseGqlTracks(items, false, tracks)
            assertEquals(299L, tracks.single().duration)
        }
    }

    @Test
    fun `Spotify paging continues without a total count until an empty page`() {
        org.junit.Assert.assertTrue(SpotifyLibrary.hasMorePages(50, 50, -1))
        org.junit.Assert.assertTrue(SpotifyLibrary.hasMorePages(50, 100, 150))
        org.junit.Assert.assertFalse(SpotifyLibrary.hasMorePages(50, 150, 150))
        org.junit.Assert.assertFalse(SpotifyLibrary.hasMorePages(0, 150, -1))
    }

    @Test
    fun `libraryV3 includes nested and collaborative playlists but skips folders`() {
        val items = JSONArray("""[
          {"item":{"__typename":"PlaylistResponseWrapper","_uri":"spotify:playlist:private123",
            "data":{"__typename":"Playlist","name":"Private Mix",
              "ownerV2":{"data":{"name":"Me"}},"content":{"totalCount":12},
              "images":{"items":[{"sources":[{"url":"https://i.scdn.co/image/private"}]}]}}}},
          {"item":{"__typename":"FolderResponseWrapper","_uri":"spotify:folder:1"}},
          {"item":{"__typename":"CollaborativePlaylistResponseWrapper","_uri":"spotify:playlist:collab456",
            "data":{"__typename":"Playlist","name":"Shared Mix"}}}
        ]""")
        val playlists = SpotifyLibrary.parseGqlPlaylists(items)
        assertEquals(2, playlists.size)
        assertEquals("private123", playlists[0].id)
        assertEquals("Private Mix", playlists[0].title)
        assertEquals("Me • 12 tracks", playlists[0].subtitle)
        assertEquals("https://i.scdn.co/image/private", playlists[0].thumbnail)
        assertEquals("collab456", playlists[1].id)
        assertEquals(-1, playlists[1].trackCount)
        assertEquals("Spotify • … tracks", playlists[1].subtitle)
        assertEquals("Spotify • 7 tracks", playlists[1].withTrackCount(7).subtitle)
    }

    @Test
    fun `GraphQL playlist and liked song track wrappers both import`() {
        val data = """{"name":"A Song","duration":{"totalMilliseconds":215000},
          "artists":{"items":[{"uri":"spotify:artist:a","profile":{"name":"Artist"}}]},
          "albumOfTrack":{"name":"Album","coverArt":{"sources":[{"url":"https://i.scdn.co/image/cover"}]}}}"""
        val playlistItems = JSONArray("""[{"itemV2":{"_uri":"spotify:track:track1","data":$data}}]""")
        val likedItems = JSONArray("""[{"track":{"_uri":"spotify:track:track2","data":$data}}]""")
        val tracks = mutableListOf<com.auralis.music.domain.model.Track>()
        SpotifyLibrary.parseGqlTracks(playlistItems, likedSongs = false, tracks)
        SpotifyLibrary.parseGqlTracks(likedItems, likedSongs = true, tracks)
        assertEquals(listOf("sp_track1", "sp_track2"), tracks.map { it.id })
        assertEquals("A Song", tracks[0].title)
        assertEquals("Artist", tracks[0].artist)
        assertEquals("Album", tracks[0].album)
        assertEquals("https://i.scdn.co/image/cover", tracks[0].thumbnail)
        assertEquals(215L, tracks[0].duration)
    }

    @Test
    fun `relinked Spotify track uses the current playable URI with its metadata`() {
        val items = JSONArray("""[{"itemV2":{"_uri":"spotify:track:old","data":{
          "uri":"spotify:track:replacement","name":"Lay All Your Love On Me - Slowed & Reverb",
          "artists":{"items":[{"profile":{"name":"DancingRoom"}}]},
          "duration":{"totalMilliseconds":343000}
        }}}]""")
        val tracks = mutableListOf<com.auralis.music.domain.model.Track>()
        SpotifyLibrary.parseGqlTracks(items, likedSongs = false, tracks)
        assertEquals("sp_replacement", tracks.single().id)
        assertEquals("DancingRoom", tracks.single().artist)
        assertEquals(343L, tracks.single().duration)
    }

    @Test
    fun `GraphQL track cover fallback imports album images`() {
        val items = JSONArray("""[{"itemV2":{"_uri":"spotify:track:track3","data":{
          "name":"A Song","artists":{"items":[]},
          "albumOfTrack":{"name":"Album","images":[{"url":"https://i.scdn.co/image/album"}]}
        }}}]""")
        val tracks = mutableListOf<com.auralis.music.domain.model.Track>()

        SpotifyLibrary.parseGqlTracks(items, likedSongs = false, tracks)

        assertEquals("https://i.scdn.co/image/album", tracks.single().thumbnail)
    }
}
