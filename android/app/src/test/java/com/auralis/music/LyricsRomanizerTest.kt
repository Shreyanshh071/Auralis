package com.auralis.music

import com.auralis.music.domain.lyrics.LyricsRomanizer
import com.auralis.music.domain.model.RomanizationScript
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class LyricsRomanizerTest {
    private val all = RomanizationScript.entries.toSet()

    @Test fun koreanAssimilationDoesNotDuplicateTheFollowingConsonant() {
        assertEquals("hangugeo", LyricsRomanizer.romanize("한국어", all))
        assertEquals("silla", LyricsRomanizer.romanize("신라", all))
        assertEquals("dongnip", LyricsRomanizer.romanize("독립", all))
        assertEquals("joa", LyricsRomanizer.romanize("좋아", all))
        assertEquals("gachi", LyricsRomanizer.romanize("같이", all))
        assertEquals("inneun", LyricsRomanizer.romanize("있는", all))
        assertEquals("jeongni", LyricsRomanizer.romanize("정리", all))
    }

    @Test fun kanjiUsesJapaneseDictionaryAndSongContextInsteadOfMandarinReadings() {
        assertEquals("toukyou", LyricsRomanizer.romanize("東京", setOf(RomanizationScript.JAPANESE)))
        assertEquals("nihongo", LyricsRomanizer.romanize("日本語", setOf(RomanizationScript.JAPANESE)))
        assertEquals("yoru ni", LyricsRomanizer.romanize("夜に", all))
        assertEquals("toukyou", LyricsRomanizer.romanize("東京", all, japaneseContext = true))
        assertEquals("dong jing", LyricsRomanizer.romanize("東京", all))
        assertNull(LyricsRomanizer.romanize("夜に", setOf(RomanizationScript.CHINESE)))
    }

    @Test fun cyrillicLanguagesAndTheirPunctuationSurviveScriptConversion() {
        assertEquals("privet!", LyricsRomanizer.romanize("привет!", all))
        assertEquals("Ukrayina", LyricsRomanizer.romanize("Україна", all))
        assertEquals("Ljubav", LyricsRomanizer.romanize("Љубав", all))
    }

    @Test fun hindiKeepsInherentVowelsAndConjuncts() {
        assertEquals("namaste", LyricsRomanizer.romanize("नमस्ते", all))
        assertEquals("man", LyricsRomanizer.romanize("मन", all))
        assertEquals("pyaar", LyricsRomanizer.romanize("प्यार", all))
    }

    @Test fun punjabiKeepsInherentVowelsAndGemination() {
        assertEquals("man", LyricsRomanizer.romanize("ਮਨ", all))
        assertEquals("kitthe", LyricsRomanizer.romanize("ਕਿੱਥੇ", all))
        assertEquals("gall", LyricsRomanizer.romanize("ਗੱਲ", all))
        assertEquals("dass", LyricsRomanizer.romanize("ਦੱਸ", all))
    }

    @Test fun japaneseHandlesSmallTsuLongVowelsAndDecomposedKana() {
        val japanese = setOf(RomanizationScript.JAPANESE)
        assertEquals("kitte", LyricsRomanizer.romanize("きって", japanese))
        assertEquals("koohii", LyricsRomanizer.romanize("コーヒー", japanese))
        assertEquals("ga", LyricsRomanizer.romanize("か\u3099", japanese))
        assertEquals("hai", LyricsRomanizer.romanize("ﾊｲ", japanese))
    }

    @Test fun latinTextAndPunctuationArePreservedInMixedLyrics() {
        assertEquals("LOVE arigatou!", LyricsRomanizer.romanize("LOVE ありがとう!", all))
        assertEquals("namaste 안녕", LyricsRomanizer.romanize("नमस्ते 안녕", setOf(RomanizationScript.HINDI)))
        assertEquals("namaste annyeong", LyricsRomanizer.romanize("नमस्ते 안녕", all))
    }

    @Test fun decomposedHangulAndAnUnselectedScriptAreHandled() {
        assertEquals("annyeong", LyricsRomanizer.romanize("안녕", all))
        assertNull(LyricsRomanizer.romanize("नमस्ते", emptySet()))
        assertNull(LyricsRomanizer.romanize("Already Latin", all))
    }

    @Test fun chineseOutputDoesNotDependOnPhoneLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("ni hao!", LyricsRomanizer.romanize("你好!", setOf(RomanizationScript.CHINESE)))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
