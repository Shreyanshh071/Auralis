package com.auralis.music

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.search.SearchQueryMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A Spotify track must bind to the same recording on YouTube Music, not a radio edit,
 * remix, feat. release or "part 2" of it: those desync lyrics. Candidates below are real
 * YouTube Music "Songs" search results, in the order YouTube Music returned them.
 */
class RecordingMatchTest {

    private fun t(id: String, title: String, artist: String, album: String?, duration: Long) =
        Track(id = id, title = title, artist = artist, album = album, duration = duration)

    @Test
    fun `album cut beats a top-ranked radio edit`() {
        val spotify = t("sp_bss", "Bitter Sweet Symphony", "The Verve", "Urban Hymns", 358)
        val results = listOf(
            t("_UWOHofs0kA", "Bitter Sweet Symphony (Radio Edit)", "The Verve", "Bitter Sweet Symphony", 276),
            t("1WTATreAg08", "Bitter Sweet Symphony (Extended Version)", "The Verve", "Urban Hymns (Super Deluxe / Remastered 2016)", 470),
            t("JnRw8bXVbPI", "Bitter Sweet Symphony", "The Verve", "Urban Hymns (Super Deluxe / Remastered 2016)", 358),
            t("e2BmBubctOs", "Bitter Sweet Symphony", "The Verve", "Bitter Sweet Symphony", 361)
        )
        assertEquals("JnRw8bXVbPI", SearchQueryMatcher.findBestCandidateForTrack(spotify, results)?.id)
    }

    @Test
    fun `radio edit is chosen when Spotify names the radio edit`() {
        val spotify = t("sp_bss_edit", "Bitter Sweet Symphony - Radio Edit", "The Verve", "Bitter Sweet Symphony", 275)
        val results = listOf(
            t("JnRw8bXVbPI", "Bitter Sweet Symphony", "The Verve", "Urban Hymns", 358),
            t("_UWOHofs0kA", "Bitter Sweet Symphony (Radio Edit)", "The Verve", "Bitter Sweet Symphony", 276)
        )
        assertEquals("_UWOHofs0kA", SearchQueryMatcher.findBestCandidateForTrack(spotify, results)?.id)
    }

    @Test
    fun `solo track never binds to an uncredited feat release even when it ranks first`() {
        val spotify = t("sp_dracula", "Dracula", "Tame Impala", "Deadbeat", 206)
        val results = listOf(
            t("DXO288FnB-A", "Dracula (feat. JENNIE)", "Tame Impala", "Dracula (with JENNIE) + Instrumental", 210),
            t("6KjVYeQ9SRw", "Dracula (feat. JENNIE)", "Tame Impala", "Dracula", 210),
            t("cuMuMnCRfqk", "Dracula", "Tame Impala", "Deadbeat", 206),
            t("JRoPx1TwaCI", "Dracula (Boys Noize Disko Version) (feat. JENNIE)", "Tame Impala & Boys Noize", "Dracula (Boys Noize Disko Version)", 241)
        )
        assertEquals("cuMuMnCRfqk", SearchQueryMatcher.findBestCandidateForTrack(spotify, results)?.id)
        assertNotNull(SearchQueryMatcher.recordingMismatch(spotify, results[0]))
    }

    @Test
    fun `feat release is accepted when Spotify credits the featured artist`() {
        val spotify = t("sp_dracula_j", "Dracula (feat. JENNIE)", "Tame Impala, JENNIE", "Dracula", 210)
        val results = listOf(
            t("cuMuMnCRfqk", "Dracula", "Tame Impala", "Deadbeat", 206),
            t("6KjVYeQ9SRw", "Dracula (feat. JENNIE)", "Tame Impala", "Dracula", 210)
        )
        assertEquals("6KjVYeQ9SRw", SearchQueryMatcher.findBestCandidateForTrack(spotify, results)?.id)
    }

    @Test
    fun `part 2 and covers never stand in for the original`() {
        val spotify = t("sp_mmqh", "Mere Mehboob Qayamat Hogi", "Kishore Kumar", "Mr. X In Bombay", 229)
        val results = listOf(
            t("eTliqrlJP-U", "Mere Mehboob Qayamat Hogi (part 2)", "Kishore Kumar", "Mr. X In Bombay", 126),
            t("kSzrMMll-WQ", "Mere Mehboob Qayamat Hogi - Live Performance", "Rajessh Iyer", "Open Stage Live - Vol 5", 253),
            t("anc-dMP53BU", "Mere Mehboob Qayamat Hogi", "Aly Haydar", "Saregama Open Stage Vol-36", 228),
            t("odrhc32fiLo", "Mere Mehboob Qayamat Hogi", "Kishore Kumar", "Kishore Sings For Kishore", 229)
        )
        assertEquals("odrhc32fiLo", SearchQueryMatcher.findBestCandidateForTrack(spotify, results)?.id)
    }

    @Test
    fun `source album picks the right master when lengths tie`() {
        val spotify = t("sp_hk", "Heaven Knows I'm Miserable Now", "The Smiths", "Hatful of Hollow", 216)
        val results = listOf(
            t("3Mr0pDNVms0", "Heaven Knows I'm Miserable Now (2008 Remaster)", "The Smiths", "The Sound of the Smiths (Deluxe; 2008 Remaster)", 217),
            t("10z6-vQm23w", "Heaven Knows I'm Miserable Now", "The Smiths", "Hatful of Hollow", 216),
            t("NG_FRi_61tE", "Heaven Knows I'm Miserable Now (Trap Remix)", "Trap Remix Guys", "Heaven Knows I'm Miserable Now (Trap Remix)", 102)
        )
        assertEquals("10z6-vQm23w", SearchQueryMatcher.findBestCandidateForTrack(spotify, results)?.id)
    }

    @Test
    fun `no confident recording means no match rather than a wrong one`() {
        val spotify = t("sp_bss", "Bitter Sweet Symphony", "The Verve", "Urban Hymns", 358)
        val results = listOf(
            t("_UWOHofs0kA", "Bitter Sweet Symphony (Radio Edit)", "The Verve", "Bitter Sweet Symphony", 276),
            t("1WTATreAg08", "Bitter Sweet Symphony (Extended Version)", "The Verve", "Urban Hymns", 470)
        )
        assertNull(SearchQueryMatcher.findBestCandidateForTrack(spotify, results))
    }

    @Test
    fun `core-title words are not mistaken for version tags`() {
        val spotify = t("sp_dj", "DJ Got Us Fallin' in Love", "Usher, Pitbull", "Raymond v Raymond", 221)
        val cand = t("yt_dj", "DJ Got Us Fallin' in Love", "Usher & Pitbull", "Raymond v Raymond", 221)
        assertNull(SearchQueryMatcher.recordingMismatch(spotify, cand))
        assertEquals("yt_dj", SearchQueryMatcher.findBestCandidateForTrack(spotify, listOf(cand))?.id)
    }

    @Test
    fun `unknown lengths do not reject a correct match`() {
        val spotify = t("sp_x", "Dracula", "Tame Impala", "Spotify Playlist", 0)
        val cand = t("cuMuMnCRfqk", "Dracula", "Tame Impala", "Deadbeat", 206)
        assertEquals("cuMuMnCRfqk", SearchQueryMatcher.findBestCandidateForTrack(spotify, listOf(cand))?.id)
    }

    @Test
    fun `rounding-only 4 s gap is the same song`() {
        // Spotify 208.786 s floors to 208; YouTube Music lists 211.475 s as 3:32.
        val spotify = t("sp_5cF0dROlMOK5uNZtivgu50", "Attention", "Charlie Puth", null, 208)
        val cand = t("vxUBYHz_q1I", "Attention", "Charlie Puth", "Attention", 212)
        assertNotNull(SearchQueryMatcher.recordingMismatch(spotify, cand))
        assertEquals(true, SearchQueryMatcher.isRoundingOnlyLengthGap(spotify, cand))
    }

    @Test
    fun `rounding allowance never lets a featured cut match the solo one`() {
        val feat = t("sp_moral", "Moral of the Story", "Ashe, Niall Horan", null, 198)
        val solo = t("GLTGm0zb3zU", "Moral of the Story", "Ashe", "Ashlyn", 202)
        assertEquals(false, SearchQueryMatcher.isRoundingOnlyLengthGap(feat, solo))
        val featTitle = t("sp_moral2", "Moral of the Story (feat. Niall Horan)", "Ashe", null, 198)
        assertEquals(false, SearchQueryMatcher.isRoundingOnlyLengthGap(featTitle, solo))
    }

    @Test
    fun `rounding allowance is exactly one second and needs a clean version`() {
        val spotify = t("sp_a", "Attention", "Charlie Puth", null, 207)
        assertEquals(false, SearchQueryMatcher.isRoundingOnlyLengthGap(spotify, t("v1", "Attention", "Charlie Puth", null, 212)))
        val s208 = spotify.copy(duration = 208)
        assertEquals(false, SearchQueryMatcher.isRoundingOnlyLengthGap(s208, t("v2", "Attention (Remix)", "Charlie Puth", null, 212)))
        assertEquals(false, SearchQueryMatcher.isRoundingOnlyLengthGap(s208, t("v3", "Attention", "redpillow & koma", null, 212)))
    }
}
