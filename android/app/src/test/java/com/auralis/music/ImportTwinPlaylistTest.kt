package com.auralis.music

import com.auralis.music.data.network.SpotifyIncrementalImport
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportTwinPlaylistTest {
    private fun song(id: String, title: String, artist: String = "Pritam") = Track(id = id, title = title, artist = artist)
    private val remote = (1..10).map { song("sp_$it", "Song $it") }
    private fun local(title: String, tracks: List<Track>) = Playlist(id = "local", title = title, tracks = tracks)

    @Test fun `same name and songs under YouTube titles is the same playlist`() {
        val copy = (1..10).map { song("yt$it", "Song $it (From \"Film\")", "Arijit Singh") }
        assertTrue(SpotifyIncrementalImport.isSamePlaylist("Freshers", remote, local("freshers", copy)))
    }

    @Test fun `matched ids count as the same song`() {
        val copy = (1..10).map { song("yt$it", "Other $it") }
        assertTrue(SpotifyIncrementalImport.isSamePlaylist("Freshers", remote, local("Freshers", copy)) { it.replace("sp_", "yt") })
    }

    @Test fun `one spelling difference in ten is still the same playlist`() {
        val copy = remote.dropLast(1).map { it.copy(id = "yt" + it.id) } + song("ytx", "Spelled Differently")
        assertTrue(SpotifyIncrementalImport.isSamePlaylist("Freshers", remote, local("Freshers", copy)))
    }

    @Test fun `another name is another playlist`() {
        assertFalse(SpotifyIncrementalImport.isSamePlaylist("Freshers", remote, local("Vibes", remote)))
    }

    @Test fun `another amount of songs is another playlist`() {
        assertFalse(SpotifyIncrementalImport.isSamePlaylist("Freshers", remote, local("Freshers", remote.drop(1))))
    }

    @Test fun `the same name with other songs is another playlist`() {
        val other = (1..10).map { song("x$it", "Different $it") }
        assertFalse(SpotifyIncrementalImport.isSamePlaylist("Freshers", remote, local("Freshers", other)))
    }
}
