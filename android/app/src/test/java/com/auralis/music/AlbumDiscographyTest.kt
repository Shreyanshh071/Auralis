package com.auralis.music

import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumDiscographyTest {

    @Test
    fun testOfficialAlbumTracklistSanitization() {
        val tracks = listOf(
            Track(id = "1", title = "Curtains Up (Skit)", artist = "Eminem", duration = 30),
            Track(id = "2", title = "White America", artist = "Eminem", duration = 325),
            Track(id = "3", title = "Business", artist = "Eminem", duration = 252),
            Track(id = "4", title = "Cleanin' Out My Closet", artist = "Eminem", duration = 298),
            Track(id = "5", title = "Square Dance", artist = "Eminem", duration = 324),
            Track(id = "6", title = "The Kiss (Skit)", artist = "Eminem", duration = 76),
            Track(id = "7", title = "Soldier", artist = "Eminem", duration = 227),
            Track(id = "8", title = "Say Goodbye Hollywood", artist = "Eminem", duration = 273),
            Track(id = "9", title = "Drips (feat. Obie Trice)", artist = "Eminem", duration = 286),
            Track(id = "10", title = "Without Me", artist = "Eminem", duration = 291),
            Track(id = "11", title = "Paul Rosenberg (Skit)", artist = "Eminem", duration = 23),
            Track(id = "12", title = "Sing For The Moment", artist = "Eminem", duration = 340),
            Track(id = "13", title = "Superman (feat. Dina Rae)", artist = "Eminem", duration = 351),
            Track(id = "14", title = "Hailie's Song", artist = "Eminem", duration = 321),
            Track(id = "15", title = "Steve Berman (Skit)", artist = "Eminem", duration = 34),
            Track(id = "16", title = "When The Music Stops (feat. D12)", artist = "Eminem", duration = 270),
            Track(id = "17", title = "Say What You Say (feat. Dr. Dre)", artist = "Eminem", duration = 310),
            Track(id = "18", title = "Till I Collapse (feat. Nate Dogg)", artist = "Eminem", duration = 298),
            Track(id = "19", title = "My Dad's Gone Crazy (feat. Hailie Jade)", artist = "Eminem", duration = 268),
            Track(id = "20", title = "Curtains Close", artist = "Eminem", duration = 62),
            // Bonus / Expanded additions:
            Track(id = "21", title = "Stimulate", artist = "Eminem", duration = 304),
            Track(id = "22", title = "Freestyle #1 (Live From Tramps, New York / 1999)", artist = "Eminem", duration = 78),
            Track(id = "23", title = "Cleanin' Out My Closet (Instrumental)", artist = "Eminem", duration = 343)
        )

        val album = PlaylistResult(
            id = "MPREb_nVOvQM1ODKM",
            title = "The Eminem Show",
            author = "Eminem"
        )

        // Filter out instrumentals and live extras
        val isExplicitLiveAlbum = album.title.contains("Live", ignoreCase = true)
        val isExplicitInstrumentalAlbum = album.title.contains("Instrumental", ignoreCase = true)
        val clean = tracks.filter { track ->
            val isInst = !isExplicitInstrumentalAlbum && track.title.contains("(Instrumental", ignoreCase = true)
            val isLive = !isExplicitLiveAlbum && track.title.contains("(Live From", ignoreCase = true)
            !isInst && !isLive
        }

        val outroIndex = clean.indexOfFirst { it.title.equals("Curtains Close", ignoreCase = true) }
        val official = if (outroIndex in 10..25) clean.take(outroIndex + 1) else clean

        assertEquals(20, official.size)
        assertEquals("Curtains Up (Skit)", official.first().title)
        assertEquals("Curtains Close", official.last().title)
    }
}

