package com.auralis.music

import com.auralis.music.data.repository.withoutRemixesOfAlbumSongs
import com.auralis.music.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumRemixFilterTest {

    private fun titles(vararg t: String) = t.map { Track(id = it, title = it) }

    @Test
    fun `housefull 2 keeps the film songs and drops their dj remixes`() {
        // YouTube Music's own tracklist for the album (MPREb_arV46FJffzk), live 2026-09-25.
        val album = titles(
            "Papa Toh Band Bajaye",
            "Anarkali Disco Chali",
            "Right Now Now",
            "Do U Know",
            "Anarkali Disco Chali (Hyper Mix)[Remix By Dj Shiva]",
            "Right Now Now (Remix By Dj Khushi)",
            "Do U Know (Remix By Dj Shiva)",
            "Anarkali Disco Chali (Remix By Dj Khushi)"
        )
        assertEquals(
            listOf("Papa Toh Band Bajaye", "Anarkali Disco Chali", "Right Now Now", "Do U Know"),
            withoutRemixesOfAlbumSongs(album).map { it.title }
        )
    }

    @Test
    fun `dash-style remix tags are recognised too`() {
        val album = titles("Levitating", "Levitating - The Blessed Madonna Remix", "Physical")
        assertEquals(listOf("Levitating", "Physical"), withoutRemixesOfAlbumSongs(album).map { it.title })
    }

    @Test
    fun `a remix whose original isn't on the album stays`() {
        val remixSingle = titles("Kesariya (Dance Mix)", "Kesariya (Dance Mix) - Extended")
        assertEquals(remixSingle, withoutRemixesOfAlbumSongs(remixSingle))

        val mixed = titles("Song A", "Song B (Club Mix)")
        assertEquals(mixed, withoutRemixesOfAlbumSongs(mixed))
    }

    @Test
    fun `songs that merely contain the word mix are not remixes`() {
        val album = titles("Mix It Up", "Mixtape Love", "Mix It Up (Reprise)")
        assertEquals(album, withoutRemixesOfAlbumSongs(album))
    }
}
