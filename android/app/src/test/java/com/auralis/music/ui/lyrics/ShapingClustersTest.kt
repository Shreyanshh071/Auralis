package com.auralis.music.ui.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ShapingClustersTest {

    private val viramas = setOf('्', '্', '੍', '્', '୍', '்', '్', '್', '്')

    private fun assertNoBrokenConjunct(text: String) {
        val clusters = text.toShapingClusters()
        assertEquals("clusters must rebuild the text exactly", text, clusters.joinToString(""))
        // A virama before a space is normal (Tamil "காதல்" shows it); only one followed by a
        // letter means a conjunct was split.
        for ((c, next) in clusters.zipWithNext()) {
            assertFalse("'$c' + '$next' splits a conjunct in \"$text\"", c.last() in viramas && next.first().isLetter())
        }
    }

    @Test
    fun `hindi conjuncts stay whole`() {
        // Seen live: "इश्क" drew as "इश्" + a stray "क".
        assertNoBrokenConjunct("इश्क की धुनी रोज़ जलाए")
        assertNoBrokenConjunct("कच्ची करारी जवानी कुंवारी हूँ")
        assertEquals(listOf("इ", "श्क"), "इश्क".toShapingClusters())
    }

    @Test
    fun `other indic scripts keep their conjuncts too`() {
        assertNoBrokenConjunct("ਇਸ਼ਕ ਦੀ ਗੱਲ")        // Punjabi (Gurmukhi)
        assertNoBrokenConjunct("ভালোবাসা ক্ষমা")       // Bengali
        assertNoBrokenConjunct("காதல் ஸ்ரீ")           // Tamil
    }

    @Test
    fun `latin text is split per character exactly as before`() {
        assertEquals(listOf("L", "o", "v", "e", " ", "m", "e"), "Love me".toShapingClusters())
    }
}
