package com.auralis.music

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.search.SearchQueryMatcher
import org.junit.Assert.assertEquals
import org.junit.Test

/** Popularity is compared smoothly: songs of 207M and 727M plays are not "equally popular". */
class SearchPopularityRankTest {

    private fun t(id: String, title: String, artist: String, album: String, views: String) =
        Track(id = id, title = title, artist = artist, album = album, views = views)

    @Test
    fun `a far more played song wins over a bracketed suffix`() {
        // Songs results on the phone for "mere mehboob qayamat hogi", in YouTube Music's order.
        val results = listOf(
            t("a", "Mere Mehboob Qayamat Hogi", "Kishore Kumar, Sanam", "LP Classics - Original LP Recordings", "207M plays"),
            t("eTliqrlJP-U", "Mere Mehboob Qayamat Hogi (part 2)", "Kishore Kumar", "Mr. X In Bombay", "727M plays"),
            t("ybaJh2tfOKw", "MERE MEHBOOB QAYAMAT HOGI - SANAM", "SANAM", "Mere Mehboob Qayamat Hogi - Sanam", "207M plays"),
            t("BOtgfRUcUvw", "Mere Mehboob Qayamat Hogi", "Mohd Faizan", "Mere Mehboob Qayamat Hogi", "9.1M plays")
        )
        val (ranked, _) = SearchQueryMatcher.partitionResults(results, "mere mehboob qayamat hogi")
        assertEquals("eTliqrlJP-U", ranked.first().id)
    }

    @Test
    fun `among title matches the most played is first`() {
        val results = listOf(
            t("exact", "Song Name", "Artist", "Album", "200M plays"),
            t("suffix", "Song Name (part 2)", "Artist", "Album", "300M plays")
        )
        val (ranked, _) = SearchQueryMatcher.partitionResults(results, "song name")
        assertEquals(listOf("suffix", "exact"), ranked.map { it.id })
    }

    @Test
    fun `a cover stays below the original however many plays it has`() {
        val results = listOf(
            t("cover", "Song Name (Cover)", "Someone", "Covers", "900M plays"),
            t("original", "Song Name", "Artist", "Album", "100M plays")
        )
        val (ranked, _) = SearchQueryMatcher.partitionResults(results, "song name")
        assertEquals("original", ranked.first().id)
        val (asked, _) = SearchQueryMatcher.partitionResults(results, "song name cover")
        assertEquals("cover", asked.first().id)
    }
}
