package com.auralis.music

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.search.SearchQueryMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seen live searching "currents": the singular "Current" outranked the album's big songs. */
class AlbumQuerySongOrderTest {
    private fun t(id: String, title: String, artist: String, album: String?, views: String) =
        Track(id = id, title = title, artist = artist, album = album, thumbnail = "", duration = 200, views = views)

    @Test
    fun `plural stem is not an exact title and album songs rank by plays`() {
        val songs = listOf(
            t("1", "Current", "Amanraj Gill & Shiva Choudhary", "Current", "25M plays"),
            t("2", "Currents", "Drake", "Honestly, Nevermind", "11M plays"),
            t("3", "The Less I Know The Better", "Tame Impala", "Currents", "988M plays"),
            t("4", "Let It Happen", "Tame Impala", "Currents", "428M plays")
        )
        val (matched, _) = SearchQueryMatcher.partitionResults(songs, "currents", maxRecommendations = 3)
        val order = matched.map { it.id }
        assertEquals("the real exact title comes first", "2", order.first())
        assertTrue("Tame Impala's Currents songs outrank the singular 'Current'",
            order.indexOf("3") < order.indexOf("1") && order.indexOf("4") < order.indexOf("1"))
    }
}
