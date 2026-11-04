package com.auralis.music.domain.lyrics

import com.auralis.music.domain.model.RomanizationScript

data class CachedRomanizedLine(val text: String?)

/** Cheap UI reads without initializing transliteration tables or the Japanese dictionary. */
object LyricsRomanizationCache {
    private data class Key(val text: String, val scripts: Set<RomanizationScript>, val japaneseContext: Boolean)
    private val entries = object : java.util.LinkedHashMap<Key, CachedRomanizedLine>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, CachedRomanizedLine>): Boolean = size > 512
    }

    fun get(text: String, scripts: Set<RomanizationScript>, japaneseContext: Boolean): CachedRomanizedLine? =
        synchronized(entries) { entries[Key(text, scripts.toSet(), japaneseContext)] }

    internal fun put(text: String, scripts: Set<RomanizationScript>, japaneseContext: Boolean, value: String?) {
        if (text.length <= 4096) synchronized(entries) {
            entries[Key(text, scripts.toSet(), japaneseContext)] = CachedRomanizedLine(value)
        }
    }
}
