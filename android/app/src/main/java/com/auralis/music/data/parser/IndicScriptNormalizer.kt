package com.auralis.music.data.parser

import java.text.Normalizer

/**
 * Normalizer and Phonetic Transliteration Engine for Indic Scripts (Devanagari, Gurmukhi, Bengali, etc.)
 * Provides seamless cross-script matching between Romanized metadata (e.g. "Raja Ji Ke Dilwa")
 * and native Indic script metadata (e.g. "राजा जी के दिलवा").
 */
object IndicScriptNormalizer {

    private val VOWEL_MAP = mapOf(
        // Devanagari Independent Vowels
        "अ" to "a", "आ" to "a", "इ" to "i", "ई" to "i", "उ" to "u", "ऊ" to "u",
        "ऋ" to "ri", "ए" to "e", "ऐ" to "ai", "ओ" to "o", "औ" to "au",
        // Gurmukhi Vowels
        "ਅ" to "a", "ਆ" to "a", "ਇ" to "i", "ਈ" to "i", "ਉ" to "u", "ਊ" to "u",
        "ਏ" to "e", "ਐ" to "ai", "ਓ" to "o", "ਔ" to "au",
        // Bengali Vowels
        "অ" to "a", "আ" to "a", "ই" to "i", "ঈ" to "i", "উ" to "u", "ঊ" to "u",
        "ঋ" to "ri", "এ" to "e", "ঐ" to "oi", "ও" to "o", "ঔ" to "ou"
    )

    private val MATRA_MAP = mapOf(
        // Devanagari Matras
        "ा" to "a", "ि" to "i", "ी" to "i", "ु" to "u", "ू" to "u",
        "ृ" to "ri", "े" to "e", "ै" to "ai", "ो" to "o", "ौ" to "au",
        "ं" to "n", "ँ" to "n", "ः" to "h", "ॅ" to "e", "ॉ" to "o",
        // Gurmukhi Matras
        "ਾ" to "a", "ਿ" to "i", "ੀ" to "i", "ੁ" to "u", "ੂ" to "u",
        "ੇ" to "e", "ੈ" to "ai", "ੋ" to "o", "ੌ" to "au",
        "ਂ" to "n", "ੰ" to "n",
        // Bengali Matras
        "া" to "a", "ি" to "i", "ী" to "i", "ু" to "u", "ূ" to "u",
        "ৃ" to "ri", "ে" to "e", "ৈ" to "oi", "ো" to "o", "ৌ" to "ou",
        "ং" to "ng", "ঁ" to "n", "ঃ" to "h"
    )

    private val CONSONANT_MAP = mapOf(
        // Devanagari Consonants
        "क" to "k", "ख" to "kh", "ग" to "g", "घ" to "gh", "ङ" to "ng",
        "च" to "ch", "छ" to "chh", "ज" to "j", "झ" to "jh", "ञ" to "ny",
        "ट" to "t", "ठ" to "th", "ड" to "d", "ढ" to "dh", "ण" to "n",
        "त" to "t", "थ" to "th", "द" to "d", "ध" to "dh", "न" to "n",
        "प" to "p", "फ" to "ph", "ब" to "b", "भ" to "bh", "म" to "m",
        "य" to "y", "र" to "r", "ल" to "l", "व" to "v",
        "श" to "sh", "ष" to "sh", "स" to "s", "ह" to "h",
        // Gurmukhi Consonants
        "ਕ" to "k", "ਖ" to "kh", "ਗ" to "g", "ਘ" to "gh",
        "ਚ" to "ch", "ਛ" to "chh", "ਜ" to "j", "ਝ" to "jh",
        "ਟ" to "t", "ਠ" to "th", "ਡ" to "d", "ਢ" to "dh", "ਣ" to "n",
        "ਤ" to "t", "ਥ" to "th", "ਦ" to "d", "ਧ" to "dh", "ਨ" to "n",
        "ਪ" to "p", "ਫ" to "ph", "ਬ" to "b", "ਭ" to "bh", "ਮ" to "m",
        "ਯ" to "y", "ਰ" to "r", "ਲ" to "l", "ਵ" to "v", "ਸ" to "s", "ਹ" to "h",
        // Bengali Consonants
        "ক" to "k", "খ" to "kh", "গ" to "g", "ঘ" to "gh", "ঙ" to "ng",
        "চ" to "ch", "ছ" to "chh", "জ" to "j", "ঝ" to "jh", "ঞ" to "ny",
        "ট" to "t", "ঠ" to "th", "ড" to "d", "ঢ" to "dh", "ণ" to "n",
        "ত" to "t", "থ" to "th", "দ" to "d", "ਧ" to "dh", "ন" to "n",
        "প" to "p", "ফ" to "ph", "ব" to "b", "ভ" to "bh", "ম" to "m",
        "য" to "j", "র" to "r", "ল" to "l", "শ" to "sh", "ষ" to "sh", "স" to "s", "ਹ" to "h",
        "ড়" to "r", "ঢ়" to "rh", "য়" to "y"
    )

    private fun isVirama(c: Char): Boolean = c == '्' || c == '্' || c == '੍'

    /**
     * Checks whether text contains any Indic script characters (Devanagari, Bengali, Gurmukhi, Tamil, Telugu, etc.).
     */
    fun containsIndicScript(text: String): Boolean {
        for (ch in text) {
            val code = ch.code
            if (code in 0x0900..0x0DFF) return true
        }
        return false
    }

    /**
     * Normalizes punctuation, zero-width characters, and script quirks.
     */
    fun normalizeIndicText(text: String): String {
        var result = Normalizer.normalize(text, Normalizer.Form.NFC)
        result = result
            .replace('\u0964', ' ') // Devanagari Danda ।
            .replace('\u0965', ' ') // Devanagari Double Danda ॥
            .replace("\u200C", "")  // Zero Width Non-Joiner (ZWNJ)
            .replace("\u200D", "")  // Zero Width Joiner (ZWJ)
            .replace('\uFEFF', ' ') // Byte Order Mark
            .replace(Regex("""\s+"""), " ")
            .trim()
        return result
    }

    /**
     * Transliterates an Indic string into phonetic Latin Romanization tokens with proper schwa handling.
     */
    fun transliterateToPhoneticLatin(text: String): String {
        return transliterateToReadableHinglish(text)
    }

    /**
     * Converts Indic text (Hindi, Bhojpuri, Punjabi, Bengali, etc.) into clean, natural,
     * human-readable Hinglish / Romanized Latin phonetic lyrics with proper schwa deletion and capitalization.
     */
    fun transliterateToReadableHinglish(text: String): String {
        if (text.isBlank()) return text
        if (!containsIndicScript(text)) return text

        val normalized = normalizeIndicText(text)
        val sb = StringBuilder()

        var i = 0
        while (i < normalized.length) {
            val chStr = normalized[i].toString()

            // 1. Independent Vowel
            val vowel = VOWEL_MAP[chStr]
            if (vowel != null) {
                sb.append(vowel)
                i++
                continue
            }

            // 2. Consonant
            val consonant = CONSONANT_MAP[chStr]
            if (consonant != null) {
                sb.append(consonant)
                val nextChar = if (i + 1 < normalized.length) normalized[i + 1] else null

                if (nextChar != null) {
                    val nextStr = nextChar.toString()
                    if (isVirama(nextChar)) {
                        i += 2
                        continue
                    } else if (MATRA_MAP.containsKey(nextStr)) {
                        sb.append(MATRA_MAP[nextStr])
                        i += 2
                        continue
                    } else if (CONSONANT_MAP.containsKey(nextStr)) {
                        val charAfterNext = if (i + 2 < normalized.length) normalized[i + 2] else null
                        if (charAfterNext != null && !isVirama(charAfterNext) && (MATRA_MAP.containsKey(charAfterNext.toString()) || CONSONANT_MAP.containsKey(charAfterNext.toString()))) {
                            sb.append("a")
                        } else if (charAfterNext == null || charAfterNext.isWhitespace() || charAfterNext in ".,!?:;\"'()[]") {
                            // Schwa deletion at word final consonant in Hindi/Bhojpuri
                        } else {
                            sb.append("a")
                        }
                    }
                }
                i++
                continue
            }

            // 3. Matra standalone
            val matra = MATRA_MAP[chStr]
            if (matra != null) {
                sb.append(matra)
                i++
                continue
            }

            // 4. Other character
            sb.append(chStr)
            i++
        }

        val raw = sb.toString()
            .replace("  ", " ")
            .trim()

        return raw.split(" ").joinToString(" ") { word ->
            if (word.isNotEmpty()) word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } else ""
        }
    }

    /**
     * Reduces Latin strings to canonical phonetic forms (e.g. 'w' <-> 'v', 'ee' <-> 'i', 'oo' <-> 'u', 'sh' <-> 's').
     */
    fun toPhoneticCanonical(text: String): String {
        return text.lowercase()
            .replace(Regex("""(?i)\bsingh\b"""), "sing")
            .replace(Regex("""(?i)\bsinh\b"""), "sing")
            .replace("pawn", "pavn")
            .replace("pawan", "pavn")
            .replace("pavan", "pavn")
            .replace("dilwa", "dilva")
            .replace("dilava", "dilva")
            .replace("w", "v")
            .replace("ee", "i")
            .replace("oo", "u")
            .replace("aa", "a")
            .replace("sh", "s")
            .replace("z", "j")
            .replace("kh", "k")
            .replace("gh", "g")
            .replace("bh", "b")
            .replace("dh", "d")
            .replace("th", "t")
            .replace("ph", "f")
            .replace(Regex("""(.)\1+"""), "$1") // deduplicate repeated letters like ll -> l
            .replace(Regex("""[^\p{L}\p{Nd}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
