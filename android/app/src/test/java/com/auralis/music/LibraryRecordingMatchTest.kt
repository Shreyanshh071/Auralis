package com.auralis.music

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.search.SearchQueryMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cases from auditing 150 songs of a real Spotify library against live YouTube Music results
 * (Spotify metadata and candidates exactly as each service returned them).
 */
class LibraryRecordingMatchTest {

    private fun t(id: String, title: String, artist: String, album: String?, duration: Long) =
        Track(id = id, title = title, artist = artist, album = album, duration = duration)

    @Test
    fun `a re-recording a few seconds off is another recording`() {
        val spotify = t("sp_6a9J", "Mere Mehboob Qayamat Hogi", "Kishore Kumar, Laxmikant–Pyarelal", "Classic Revival Ni Sultana Re", 243)
        val results = listOf(
            t("odrhc32fiLo", "Mere Mehboob Qayamat Hogi", "Kishore Kumar", "Kishore Sings For Kishore", 229),
            t("eTliqrlJP-U", "Mere Mehboob Qayamat Hogi (part 2)", "Kishore Kumar", "Mr. X In Bombay", 126),
            t("zUANbDijWfA", "Mere Mehboob Qayamat Hogi", "Kishore Kumar", "Suhane Pal - Gems Of Kishore Kumar", 229),
            t("zsVPx_6_FmM", "Mere Mehboob Qayamat Hogi Revival", "Kishore Kumar", "MrX In Bombay", 244)
        )
        assertEquals("zsVPx_6_FmM", SearchQueryMatcher.findBestCandidateForTrack(spotify, results)?.id)
        assertNotNull(SearchQueryMatcher.recordingMismatch(spotify, results[0]))
    }

    @Test
    fun `a guest named only in the release title is still an uncredited guest`() {
        // YouTube Music lists the JENNIE version as plain "Dracula" by "Tame Impala", same length.
        val jennie = t("ccdpZl47hwc", "Dracula", "Tame Impala", "Dracula (with JENNIE) + Instrumental", 206)
        val deadbeat = t("cuMuMnCRfqk", "Dracula", "Tame Impala", "Deadbeat", 206)
        for (spotifyAlbum in listOf("Deadbeat", "Dracula", null)) {
            val spotify = t("sp_dracula", "Dracula", "Tame Impala", spotifyAlbum, 205)
            assertNotNull(SearchQueryMatcher.recordingMismatch(spotify, jennie))
            assertEquals(spotifyAlbum.toString(), "cuMuMnCRfqk", SearchQueryMatcher.findBestCandidateForTrack(spotify, listOf(jennie, deadbeat))?.id)
        }
        val spotifyJennie = t("sp_dracula_j", "Dracula (with JENNIE)", "Tame Impala, JENNIE", "Dracula (with JENNIE)", 206)
        assertNull(SearchQueryMatcher.recordingMismatch(spotifyJennie, jennie))
    }

    @Test
    fun `titles in non-Latin scripts are compared, not treated as empty`() {
        val spotify = t("sp_jam", "静寂のアポストル", "JAM Project", "静寂のアポストル", 218)
        val wrong = t("97KJgZgGgWw", "Schwatch!: Kimi o Mamoritai", "JAM Project", "TOKYO DIVE", 218)
        assertNull(SearchQueryMatcher.findBestCandidateForTrack(spotify, listOf(wrong)))
        val right = t("x", "静寂のアポストル", "JAM Project", "静寂のアポストル", 219)
        assertEquals("x", SearchQueryMatcher.findBestCandidateForTrack(spotify, listOf(wrong, right))?.id)
    }

    @Test
    fun `an artist written in another script is not a mismatch`() {
        val spotify = t("sp_jojo", "JOJO SONO CHINO SADAME", "Hiroaki Tommy Tominaga", "JOJO SONO CHINO SADAME", 262)
        val yt = t("_N_Uqd7JBcM", "JOJO SONO CHINO SADAME", "富永TOMMY弘明", "JOJO SONO CHINO SADAME", 263)
        assertEquals("_N_Uqd7JBcM", SearchQueryMatcher.findBestCandidateForTrack(spotify, listOf(yt))?.id)
    }

    @Test
    fun `extra singers YouTube credits don't reject the right song`() {
        val spotify = t("sp_lagaan", "O Paalanhaare", "Lata Mangeshkar, Udit Narayan, A.R. Rahman", "Lagaan (Original Motion Picture Soundtrack)", 318)
        val yt = t("7BRb4x9SWKk", "O Paalanhaare", "Lata Mangeshkar, Udit Narayan & Sadhana Sargam", "Lagaan (Original Motion Picture Soundtrack)", 319)
        assertEquals("7BRb4x9SWKk", SearchQueryMatcher.findBestCandidateForTrack(spotify, listOf(yt))?.id)
    }

    @Test
    fun `album identity ignores edition labels only`() {
        assertEquals(
            SearchQueryMatcher.albumIdentity("Urban Hymns (Remastered 2016)"),
            SearchQueryMatcher.albumIdentity("Urban Hymns (Super Deluxe / Remastered 2016)")
        )
        assertEquals(SearchQueryMatcher.albumIdentity("Breezy"), SearchQueryMatcher.albumIdentity("Breezy (Deluxe)"))
        assertNotEquals(SearchQueryMatcher.albumIdentity("Dracula"), SearchQueryMatcher.albumIdentity("Dracula (with JENNIE) + Instrumental"))
        assertNotEquals(SearchQueryMatcher.albumIdentity("Fearless"), SearchQueryMatcher.albumIdentity("Fearless (Taylor's Version)"))
    }
}
