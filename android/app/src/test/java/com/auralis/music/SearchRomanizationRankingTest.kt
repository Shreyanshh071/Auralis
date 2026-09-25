package com.auralis.music

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.search.SearchQueryMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "kya hua tera wada" put Sanam's cover first and Mohammed Rafi & Sushma Shrestha's original
 * (164M plays) 8th, below 91- and 29-play uploads. YouTube Music ranks the original first; it is
 * titled "Kya Hua Tera Vada", which Auralis treated as a typo of the query.
 */
class SearchRomanizationRankingTest {

    private fun t(id: String, title: String, artist: String, views: String, album: String? = null) =
        Track(id = id, title = title, artist = artist, album = album, duration = 250, views = views)

    // YouTube Music "Songs" results for the query, 2026-09-25, in YouTube Music's order.
    private val results = listOf(
        t("OnomKR46aP4", "Kya Hua Tera Vada", "Mohammed Rafi, Sushma Shrestha", "164M plays", "Babuji Dheere Chalna"),
        t("2BmvCB8RaZI", "Kya Hua Tera Vada", "Mohd. Rafi & Sushma Shreshtha", "164M plays", "Greatest Hits R D-Burman His Finest Ever"),
        t("EceZPXm2FFc", "Kya Hua Tera Wada", "Sanam", "11M plays", "Kya Hua Tera Wada"),
        t("t0loTFDNGyE", "Kya Hua Tera Wada", "Madhur Sharma", "2.7M plays", "Kya Hua Tera Wada"),
        t("0kuN5PKYg3E", "Kya Hua Tera Wada", "Shashikant Kachave", "91 plays", "Dil Ke Raste"),
        t("Q83GhkhlqHA", "Kya Hua Tera Vada", "Karthik S", "288 plays", "Open Stage Melodies - Vol 82"),
        t("GqFpye5PSM4", "Kya Hua Tera Vada", "Sadhana Sargam & Vipin Sachdeva", "100K plays", "Suhane Pal"),
        t("YXLfvAJPZQw", "Kya Hua Tera Wada", "Akshay Baheti", "29 plays", "Rumi's Garden"),
        t("yYgzbOBhU-4", "Kya Hua Tera Vaada", "Pratap Bhargava", "106 plays", "Open Stage Covers - Vol 69")
    )

    @Test
    fun `the 164M-play original ranks first for a different romanization of its title`() {
        val (matched, _) = SearchQueryMatcher.partitionResults(results, "kya hua tera wada")
        assertTrue(matched.first().artist.contains("Rafi"))
        val rafi = matched.indexOfFirst { it.artist.contains("Rafi") }
        val tinyUpload = matched.indexOfFirst { it.artist == "Shashikant Kachave" }
        assertTrue("original ($rafi) above 91-play upload ($tinyUpload)", rafi < tinyUpload)
    }

    @Test
    fun `romanization variants are the same title`() {
        fun key(s: String) = SearchQueryMatcher.romanizationKey(SearchQueryMatcher.normalize(s))
        assertEquals(key("Kya Hua Tera Wada"), key("Kya Hua Tera Vada"))
        assertEquals(key("Kya Hua Tera Wada"), key("Kya Hua Tera Vaada"))
        assertEquals(key("Pyaar Hua"), key("Pyar Hua"))
        assertEquals(key("Phir Se"), key("Fir Se"))
        assertTrue(key("Tum Hi Ho") != key("Tum Se Hi"))
    }

    @Test
    fun `an exact spelling still wins when popularity is comparable`() {
        val (matched, _) = SearchQueryMatcher.partitionResults(
            listOf(
                t("a", "Kya Hua Tera Vada", "Singer A", "2M plays"),
                t("b", "Kya Hua Tera Wada", "Singer B", "2M plays")
            ),
            "kya hua tera wada"
        )
        assertEquals("b", matched.first().id)
    }

    @Test
    fun `romanized variant is a title match, not a typo`() {
        val m = SearchQueryMatcher.evaluateMatch(results.first(), "kya hua tera wada")!!
        assertEquals(SearchQueryMatcher.MatchTier.EXACT_TITLE, m.tier)
    }
}
