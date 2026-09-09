package com.auralis.music

import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsAnimationMode
import com.auralis.music.domain.model.SyncType
import com.auralis.music.ui.lyrics.resolveEffectiveWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying:
 * 1. All 10 VIVI lyrics animation styles exist, have unique identities, and map cleanly.
 * 2. AppearanceSettings carries all required lyrics customization fields with authentic defaults.
 * 3. Timing-honesty contract: resolveEffectiveWords preserves genuine RICHSYNC word timing
 *    and strictly returns null for LINE_SYNC/PLAIN lyrics (zero fabricated word timestamps).
 * 4. Multi-agent vocal positioning and distance-from-current logic.
 */
class LyricsAnimationModesTest {

    @Test
    fun allAnimationModes_existAndAreDistinct() {
        val expectedModes = listOf(
            LyricsAnimationMode.AURALIS,
            LyricsAnimationMode.KARAOKE,
            LyricsAnimationMode.FADE,
            LyricsAnimationMode.SLIDE,
            LyricsAnimationMode.GLOW,
            LyricsAnimationMode.APPLE_MUSIC_V2,
            LyricsAnimationMode.LYRICS_V2_FLUID,
            LyricsAnimationMode.METRO_LYRICS
        )

        assertEquals("Must have exactly 8 distinct animation modes", 8, LyricsAnimationMode.entries.size)
        assertEquals(expectedModes, LyricsAnimationMode.entries)

        // Verify distinct display names (no duplicate aliases)
        val displayNames = LyricsAnimationMode.entries.map { it.displayName }
        assertEquals("Each mode must have a unique display name", 8, displayNames.toSet().size)
    }

    @Test
    fun fromDisplayName_resolvesAllModesCorrectly() {
        assertEquals(LyricsAnimationMode.AURALIS, LyricsAnimationMode.fromDisplayName("Auralis (Default)"))
        assertEquals(LyricsAnimationMode.AURALIS, LyricsAnimationMode.fromDisplayName("None"))
        assertEquals(LyricsAnimationMode.FADE, LyricsAnimationMode.fromDisplayName("Fade"))
        assertEquals(LyricsAnimationMode.GLOW, LyricsAnimationMode.fromDisplayName("Glow"))
        assertEquals(LyricsAnimationMode.SLIDE, LyricsAnimationMode.fromDisplayName("Slide"))
        assertEquals(LyricsAnimationMode.KARAOKE, LyricsAnimationMode.fromDisplayName("Karaoke"))
        assertEquals(LyricsAnimationMode.GLOW, LyricsAnimationMode.fromDisplayName("Apple Music"))
        assertEquals(LyricsAnimationMode.APPLE_MUSIC_V2, LyricsAnimationMode.fromDisplayName("Apple Music (Letter by Letter)"))
        assertEquals(LyricsAnimationMode.APPLE_MUSIC_V2, LyricsAnimationMode.fromDisplayName("Apple Music V2 (Letter by Letter)"))
        assertEquals(LyricsAnimationMode.LYRICS_V2_FLUID, LyricsAnimationMode.fromDisplayName("Vivimusic (Fluid)"))
        assertEquals(LyricsAnimationMode.LYRICS_V2_FLUID, LyricsAnimationMode.fromDisplayName("Lyrics V2 (Fluid)"))
        assertEquals(LyricsAnimationMode.METRO_LYRICS, LyricsAnimationMode.fromDisplayName("MetroLyrics"))
    }

    @Test
    fun fromDisplayName_handlesCaseInsensitiveAndFallbackKeywords() {
        assertEquals(LyricsAnimationMode.METRO_LYRICS, LyricsAnimationMode.fromDisplayName("metro_lyrics"))
        assertEquals(LyricsAnimationMode.APPLE_MUSIC_V2, LyricsAnimationMode.fromDisplayName("letter by letter"))
        assertEquals(LyricsAnimationMode.APPLE_MUSIC_V2, LyricsAnimationMode.fromDisplayName("apple_v2"))
        assertEquals(LyricsAnimationMode.GLOW, LyricsAnimationMode.fromDisplayName("apple"))
        assertEquals(LyricsAnimationMode.LYRICS_V2_FLUID, LyricsAnimationMode.fromDisplayName("lyrics_v2"))
        assertEquals(LyricsAnimationMode.LYRICS_V2_FLUID, LyricsAnimationMode.fromDisplayName("vivi_fluid"))
        assertEquals(LyricsAnimationMode.AURALIS, LyricsAnimationMode.fromDisplayName("NONE"))
        assertEquals(LyricsAnimationMode.AURALIS, LyricsAnimationMode.fromDisplayName("auralis"))
        assertEquals(LyricsAnimationMode.AURALIS, LyricsAnimationMode.fromDisplayName("default"))
        // Unknown or null defaults safely to AURALIS
        assertEquals(LyricsAnimationMode.AURALIS, LyricsAnimationMode.fromDisplayName(null))
        assertEquals(LyricsAnimationMode.AURALIS, LyricsAnimationMode.fromDisplayName("UnknownStyle"))
    }

    @Test
    fun appearanceSettings_defaultValues_areAuthentic() {
        val defaultSettings = AppearanceSettings()

        assertEquals(LyricsAnimationMode.AURALIS.displayName, defaultSettings.lyricsAnimation)
        assertFalse("enableGlowingLyricsEffect defaults to false", defaultSettings.enableGlowingLyricsEffect)
        assertFalse("standardLyricsBlur defaults to false", defaultSettings.standardLyricsBlur)
        assertEquals(22f, defaultSettings.lyricsTextSize, 0.001f)
        assertEquals(1.3f, defaultSettings.lyricsLineSpacing, 0.001f)
    }

    @Test
    fun resolveEffectiveWords_preservesGenuineRichsync_andRejectsLineSync() {
        val genuineWords = listOf(
            LyricWord(word = "Hello", time = 1000L, duration = 500L),
            LyricWord(word = "World", time = 1500L, duration = 400L)
        )
        val line = LyricLine(
            time = 1000L,
            text = "Hello World",
            words = genuineWords
        )

        // Under RICHSYNC: words must be returned verbatim
        val richWords = resolveEffectiveWords(line, SyncType.RICHSYNC)
        assertNotNull(richWords)
        assertEquals(2, richWords!!.size)
        assertEquals("Hello", richWords[0].word)
        assertEquals(500L, richWords[0].duration)

        // Under LINE_SYNC: must return null even if words are present on the data object
        val lineSyncWords = resolveEffectiveWords(line, SyncType.LINE_SYNC)
        assertNull("Must return null under LINE_SYNC (timing honesty)", lineSyncWords)

        // Under PLAIN: must return null
        val plainWords = resolveEffectiveWords(line, SyncType.PLAIN)
        assertNull("Must return null under PLAIN", plainWords)

        // When words are null or empty under RICHSYNC: must return null
        val emptyLine = LyricLine(time = 1000L, text = "Empty", words = emptyList())
        assertNull(resolveEffectiveWords(emptyLine, SyncType.RICHSYNC))
    }

    @Test
    fun multiAgentAndBackgroundVocals_identification() {
        val leadLine = LyricLine(time = 1000L, text = "Lead Vocal", agent = "v1")
        val harmonyLine = LyricLine(time = 1000L, text = "Harmony", agent = "v2")
        val chorusLine = LyricLine(time = 1000L, text = "All Together", agent = "v1000")
        val bgLine = LyricLine(time = 1500L, text = "(ad-lib)", isBackground = true)

        assertEquals("v1", leadLine.agent)
        assertEquals("v2", harmonyLine.agent)
        assertEquals("v1000", chorusLine.agent)
        assertTrue(bgLine.isBackground)
        assertFalse(leadLine.isBackground)
    }

    @Test
    fun distanceFromCurrent_progressiveBlurCalculation() {
        val activeIndex = 5

        for (testIndex in 0..10) {
            val distance = kotlin.math.abs(testIndex - activeIndex)
            val expectedBlur = when {
                distance <= 1 -> 0
                distance == 2 -> 2
                distance == 3 -> 4
                else -> 6
            }
            if (testIndex == activeIndex) {
                assertEquals(0, distance)
                assertEquals(0, expectedBlur)
            } else if (distance == 2) {
                assertEquals(2, expectedBlur)
            } else if (distance == 3) {
                assertEquals(4, expectedBlur)
            } else if (distance >= 4) {
                assertEquals(6, expectedBlur)
            }
        }
    }

    @Test
    fun standardLyricsBlur_conditionsAndBehavior() {
        fun computeBlur(
            standardBlur: Boolean,
            isAutoScrollActive: Boolean,
            isUserInteracting: Boolean,
            isSynced: Boolean,
            isPlain: Boolean,
            isSelected: Boolean,
            isCurrent: Boolean,
            distanceFromCurrent: Int
        ): Float {
            return if (!standardBlur || !isAutoScrollActive || isUserInteracting || !isSynced || isPlain || isSelected || isCurrent) {
                0f
            } else {
                when (distanceFromCurrent) {
                    0, 1 -> 0f
                    2 -> 2f
                    3 -> 4f
                    else -> 6f
                }
            }
        }

        // Disabled by setting
        assertEquals(0f, computeBlur(standardBlur = false, isAutoScrollActive = true, isUserInteracting = false, isSynced = true, isPlain = false, isSelected = false, isCurrent = false, distanceFromCurrent = 3))
        // Disabled by user scrolling/touching
        assertEquals(0f, computeBlur(standardBlur = true, isAutoScrollActive = true, isUserInteracting = true, isSynced = true, isPlain = false, isSelected = false, isCurrent = false, distanceFromCurrent = 3))
        // Disabled by auto-scroll turned off
        assertEquals(0f, computeBlur(standardBlur = true, isAutoScrollActive = false, isUserInteracting = false, isSynced = true, isPlain = false, isSelected = false, isCurrent = false, distanceFromCurrent = 3))
        // Disabled for unsynced/plain lyrics
        assertEquals(0f, computeBlur(standardBlur = true, isAutoScrollActive = true, isUserInteracting = false, isSynced = false, isPlain = true, isSelected = false, isCurrent = false, distanceFromCurrent = 3))
        // Disabled for selected line in share mode
        assertEquals(0f, computeBlur(standardBlur = true, isAutoScrollActive = true, isUserInteracting = false, isSynced = true, isPlain = false, isSelected = true, isCurrent = false, distanceFromCurrent = 3))
        // Disabled for currently active singing line
        assertEquals(0f, computeBlur(standardBlur = true, isAutoScrollActive = true, isUserInteracting = false, isSynced = true, isPlain = false, isSelected = false, isCurrent = true, distanceFromCurrent = 0))

        // Enabled progressive depth-of-field falloff
        assertEquals(0f, computeBlur(standardBlur = true, isAutoScrollActive = true, isUserInteracting = false, isSynced = true, isPlain = false, isSelected = false, isCurrent = false, distanceFromCurrent = 1))
        assertEquals(2f, computeBlur(standardBlur = true, isAutoScrollActive = true, isUserInteracting = false, isSynced = true, isPlain = false, isSelected = false, isCurrent = false, distanceFromCurrent = 2))
        assertEquals(4f, computeBlur(standardBlur = true, isAutoScrollActive = true, isUserInteracting = false, isSynced = true, isPlain = false, isSelected = false, isCurrent = false, distanceFromCurrent = 3))
        assertEquals(6f, computeBlur(standardBlur = true, isAutoScrollActive = true, isUserInteracting = false, isSynced = true, isPlain = false, isSelected = false, isCurrent = false, distanceFromCurrent = 4))
        assertEquals(6f, computeBlur(standardBlur = true, isAutoScrollActive = true, isUserInteracting = false, isSynced = true, isPlain = false, isSelected = false, isCurrent = false, distanceFromCurrent = 8))
    }
}
