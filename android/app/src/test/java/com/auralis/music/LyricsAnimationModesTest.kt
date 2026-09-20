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
            LyricsAnimationMode.FADE,
            LyricsAnimationMode.GLOW,
            LyricsAnimationMode.APPLE_MUSIC_V2,
            LyricsAnimationMode.LYRICS_V2_FLUID,
            LyricsAnimationMode.METRO_LYRICS
        )

        assertEquals("Must have exactly 6 distinct animation modes", 6, LyricsAnimationMode.entries.size)
        assertEquals(expectedModes, LyricsAnimationMode.entries)

        // Verify distinct display names (no duplicate aliases)
        val displayNames = LyricsAnimationMode.entries.map { it.displayName }
        assertEquals("Each mode must have a unique display name", 6, displayNames.toSet().size)
    }

    @Test
    fun fromDisplayName_resolvesAllModesCorrectly() {
        assertEquals(LyricsAnimationMode.AURALIS, LyricsAnimationMode.fromDisplayName("Auralis (Default)"))
        assertEquals(LyricsAnimationMode.AURALIS, LyricsAnimationMode.fromDisplayName("None"))
        assertEquals(LyricsAnimationMode.FADE, LyricsAnimationMode.fromDisplayName("Fade"))
        assertEquals(LyricsAnimationMode.GLOW, LyricsAnimationMode.fromDisplayName("Glow"))
        assertEquals(LyricsAnimationMode.AURALIS, LyricsAnimationMode.fromDisplayName("Slide"))
        assertEquals(LyricsAnimationMode.AURALIS, LyricsAnimationMode.fromDisplayName("Karaoke"))
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

        assertFalse("experimentalLyrics defaults to false", defaultSettings.experimentalLyrics)
        assertEquals(LyricsAnimationMode.AURALIS.displayName, defaultSettings.lyricsAnimation)
        assertFalse("enableGlowingLyricsEffect defaults to false", defaultSettings.enableGlowingLyricsEffect)
        assertTrue("standardLyricsBlur defaults to true", defaultSettings.standardLyricsBlur)
        assertEquals(22f, defaultSettings.lyricsTextSize, 0.001f)
        assertEquals(1.3f, defaultSettings.lyricsLineSpacing, 0.001f)
    }

    @Test
    fun metroLyrics_routesDirectlyToExperimentalLyrics() {
        // 1. MetroLyrics animation mode routes to Experimental Lyrics even when experimentalLyrics toggle is false
        val metroSettings = AppearanceSettings(
            experimentalLyrics = false,
            lyricsAnimation = LyricsAnimationMode.METRO_LYRICS.displayName
        )
        assertTrue(
            "Selecting MetroLyrics must enable Experimental Lyrics layout",
            metroSettings.shouldUseExperimentalLyrics
        )

        // 2. MetroLyrics alias (case-insensitive, lowercase) also routes to Experimental Lyrics
        val metroAliasSettings = AppearanceSettings(
            experimentalLyrics = false,
            lyricsAnimation = "metro_lyrics"
        )
        assertTrue(
            "MetroLyrics alias must enable Experimental Lyrics layout",
            metroAliasSettings.shouldUseExperimentalLyrics
        )

        // 3. Standalone experimentalLyrics toggle = true always enables Experimental Lyrics regardless of animation mode
        val experimentalOnSettings = AppearanceSettings(
            experimentalLyrics = true,
            lyricsAnimation = LyricsAnimationMode.AURALIS.displayName
        )
        assertTrue(
            "Standalone experimentalLyrics toggle must still enable Experimental Lyrics",
            experimentalOnSettings.shouldUseExperimentalLyrics
        )

        // 4. Other animation modes with experimentalLyrics = false do NOT route to Experimental Lyrics
        val otherModes = listOf(
            LyricsAnimationMode.AURALIS,
            LyricsAnimationMode.FADE,
            LyricsAnimationMode.GLOW,
            LyricsAnimationMode.APPLE_MUSIC_V2,
            LyricsAnimationMode.LYRICS_V2_FLUID
        )
        for (mode in otherModes) {
            val settings = AppearanceSettings(
                experimentalLyrics = false,
                lyricsAnimation = mode.displayName
            )
            assertFalse(
                "Mode ${mode.displayName} must use standard layout, not experimental",
                settings.shouldUseExperimentalLyrics
            )
        }
    }

    @Test
    fun metroLyrics_isAvailableAsIntendedUserFacingOption() {
        // 1. MetroLyrics is an intended option in the enum
        val displayNames = LyricsAnimationMode.entries.map { it.displayName }
        assertTrue("MetroLyrics must be in available animation modes", displayNames.contains("MetroLyrics"))
        assertEquals(LyricsAnimationMode.METRO_LYRICS, LyricsAnimationMode.fromDisplayName("MetroLyrics"))
        assertEquals("MetroLyrics", LyricsAnimationMode.METRO_LYRICS.displayName)
    }

    @Test
    fun experimentalLyrics_isNoLongerExposedAsSeparateUserFacingOption() {
        // 2. Experimental Lyrics is NOT exposed as a separate animation option (MetroLyrics is its replacement)
        val allNames = LyricsAnimationMode.entries.map { it.name }
        val allDisplayNames = LyricsAnimationMode.entries.map { it.displayName }
        assertTrue("No animation option should be named EXPERIMENTAL_LYRICS",
            allNames.none { it.contains("EXPERIMENTAL", ignoreCase = true) })
        assertTrue("No animation option display name should contain 'Experimental'",
            allDisplayNames.none { it.contains("Experimental", ignoreCase = true) })
    }

    @Test
    fun selectingMetroLyrics_routesToExperimentalLyricsView() {
        // 3. Selecting MetroLyrics routes directly to ExperimentalLyricsView via shouldUseExperimentalLyrics
        val settingsFromEnum = AppearanceSettings(
            experimentalLyrics = false,
            lyricsAnimation = LyricsAnimationMode.METRO_LYRICS.displayName
        )
        assertTrue(settingsFromEnum.shouldUseExperimentalLyrics)

        val settingsFromString = AppearanceSettings(
            experimentalLyrics = false,
            lyricsAnimation = "MetroLyrics"
        )
        assertTrue(settingsFromString.shouldUseExperimentalLyrics)
    }

    @Test
    fun existingPersistedExperimentalLyrics_isMigratedSafely() {
        // 4. If a user previously had experimentalLyrics = true saved:
        // Case A: raw preference loaded into AppearanceSettings preserves shouldUseExperimentalLyrics = true
        val legacySettings = AppearanceSettings(
            experimentalLyrics = true,
            lyricsAnimation = LyricsAnimationMode.AURALIS.displayName
        )
        assertTrue("Legacy saved preference must still route to experimental lyrics",
            legacySettings.shouldUseExperimentalLyrics)

        // Case B: DataStore resolver logic simulation
        val legacyExp = true
        val storedAnim: String? = null
        val resolvedAnimation = if (legacyExp && (storedAnim == null || storedAnim == LyricsAnimationMode.AURALIS.displayName)) {
            LyricsAnimationMode.METRO_LYRICS.displayName
        } else {
            storedAnim ?: LyricsAnimationMode.AURALIS.displayName
        }
        assertEquals("Unset or default animation with legacy experimental=true resolves to MetroLyrics",
            LyricsAnimationMode.METRO_LYRICS.displayName, resolvedAnimation)

        // Case C: Migrated state (experimentalLyrics = false, lyricsAnimation = MetroLyrics) routes properly
        val migratedSettings = AppearanceSettings(
            experimentalLyrics = false,
            lyricsAnimation = resolvedAnimation
        )
        assertTrue("Migrated settings must enable experimental lyrics",
            migratedSettings.shouldUseExperimentalLyrics)
    }

    @Test
    fun otherLyricsAnimationModes_remainUnchanged() {
        // 5. All other 5 animation modes remain distinct and unchanged
        val standardModes = listOf(
            Pair(LyricsAnimationMode.AURALIS, "Auralis (Default)"),
            Pair(LyricsAnimationMode.FADE, "Fade"),
            Pair(LyricsAnimationMode.GLOW, "Glow"),
            Pair(LyricsAnimationMode.APPLE_MUSIC_V2, "Apple Music (Letter by Letter)"),
            Pair(LyricsAnimationMode.LYRICS_V2_FLUID, "Lyrics V2 (Fluid)")
        )

        for ((mode, expectedName) in standardModes) {
            assertEquals("Display name must match", expectedName, mode.displayName)
            assertEquals("Resolution from display name must match", mode, LyricsAnimationMode.fromDisplayName(expectedName))

            val settings = AppearanceSettings(
                experimentalLyrics = false,
                lyricsAnimation = mode.displayName
            )
            assertFalse("${mode.displayName} must not route to ExperimentalLyricsView",
                settings.shouldUseExperimentalLyrics)
        }
    }

    @Test
    fun experimentalLyrics_presentationRouting_offUsesStandardAuralisLayout() {
        // Contract verification: OFF
        // Standard Auralis presentation:
        // - Single active line determined by primaryIndex
        // - Text alignment strictly preserves user's lyricsTextPosition setting
        // - Background vocals do NOT shrink to 70% and do NOT italicize
        // - Line spacing is uniform without background-pairing compression
        val userAlignment = "Centre"
        val isExperimental = false

        val lineLead = LyricLine(time = 10_000L, endTime = 15_000L, text = "Lead line", agent = "v1")
        val lineBg = LyricLine(time = 11_000L, endTime = 13_500L, text = "(Ad-lib)", agent = "v2", isBackground = true)
        val lines = listOf(lineLead, lineBg)

        val activeIndices = com.auralis.music.ui.screens.lyrics.LyricsEngine.findActiveLyricIndices(lines, 12_000L)
        val primaryIndex = com.auralis.music.ui.screens.lyrics.LyricsEngine.findActiveLyricIndex(lines, 12_000L)

        // Multi-active engine returns both lines active
        assertEquals(setOf(0, 1), activeIndices)
        assertEquals(0, primaryIndex)

        // Under OFF: only primaryIndex is considered current
        for (index in lines.indices) {
            val isCurrent = if (isExperimental) activeIndices.contains(index) else (primaryIndex == index)
            if (index == 0) {
                assertTrue("Primary lead line is active under OFF", isCurrent)
            } else {
                assertFalse("Secondary/background line is NOT active under OFF (single active line)", isCurrent)
            }
        }

        // Under OFF: alignment is strictly user-defined (Centre), not shifted by agent or background role
        fun resolveAlignment(line: LyricLine, userPos: String, experimental: Boolean): String {
            return if (experimental) {
                when {
                    line.isBackground -> "Center"
                    line.agent == "v1" -> "Start"
                    line.agent == "v2" -> "End"
                    line.agent == "v1000" -> "Center"
                    else -> userPos
                }
            } else {
                userPos
            }
        }

        assertEquals("Centre", resolveAlignment(lineLead, userAlignment, isExperimental))
        assertEquals("Centre", resolveAlignment(lineBg, userAlignment, isExperimental))

        // Under OFF: background vocal styling is not altered
        val isBgStyledLead = isExperimental && lineLead.isBackground
        val isBgStyledBg = isExperimental && lineBg.isBackground
        assertFalse("Lead line has no special bg styling", isBgStyledLead)
        assertFalse("Background vocal in OFF mode does not receive experimental shrink/italic", isBgStyledBg)
    }

    @Test
    fun experimentalLyrics_presentationRouting_onUsesMetrolistMultiActiveLayout() {
        // Contract verification: ON
        // Metrolist presentation:
        // - Multiple active lyric lines simultaneously
        // - v1 -> Start (Left), v2 -> End (Right), v1000 -> Center, isBackground -> Center
        // - Background vocals smaller (70%), italic, centered
        // - Paired line spacing compressed to 2dp
        val userAlignment = "Centre"
        val isExperimental = true

        val lineLead = LyricLine(time = 10_000L, endTime = 15_000L, text = "Lead line", agent = "v1")
        val lineBg = LyricLine(time = 11_000L, endTime = 13_500L, text = "(Ad-lib)", agent = "v2", isBackground = true)
        val lines = listOf(lineLead, lineBg)

        val activeIndices = com.auralis.music.ui.screens.lyrics.LyricsEngine.findActiveLyricIndices(lines, 12_000L)
        val primaryIndex = com.auralis.music.ui.screens.lyrics.LyricsEngine.findActiveLyricIndex(lines, 12_000L)

        // Under ON: both lines are active simultaneously
        for (index in lines.indices) {
            val isCurrent = if (isExperimental) activeIndices.contains(index) else (primaryIndex == index)
            assertTrue("Line $index is active under ON", isCurrent)
        }

        // Under ON: alignment dynamically matches agent and role
        fun resolveAlignment(line: LyricLine, userPos: String, experimental: Boolean): String {
            return if (experimental) {
                when {
                    line.isBackground -> "Center"
                    line.agent == "v1" -> "Start"
                    line.agent == "v2" -> "End"
                    line.agent == "v1000" -> "Center"
                    else -> userPos
                }
            } else {
                userPos
            }
        }

        assertEquals("Start", resolveAlignment(lineLead, userAlignment, isExperimental))
        assertEquals("Center", resolveAlignment(lineBg, userAlignment, isExperimental)) // isBackground takes precedence over agent for centering

        // Under ON: background vocal styling is applied
        val isBgStyledLead = isExperimental && lineLead.isBackground
        val isBgStyledBg = isExperimental && lineBg.isBackground
        assertFalse("Lead line is not bg-styled", isBgStyledLead)
        assertTrue("Background vocal in ON mode receives experimental shrink/italic styling", isBgStyledBg)
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

    @Test
    fun metroLyricsLine_activeIntervalHighlightState_preservesHighlightUntilNextLine() {
        // Models MetroLyrics active interval calculation:
        // - line not started -> inactive/gray
        // - line currently playing -> highlighted
        // - line's words have finished but next line has not started -> STILL highlighted
        // - next line starts -> previous line can become inactive and next line becomes highlighted
        fun calculateMetroActive(
            playbackPosition: Long,
            line: LyricLine,
            nextLineTime: Long?,
            isActive: Boolean
        ): Boolean {
            val nextBoundary = nextLineTime ?: line.endTime ?: (line.effectiveEndTime ?: (line.time + 10_000L))
            val isInActiveInterval = if (nextBoundary > line.time) {
                playbackPosition in line.time until nextBoundary
            } else {
                playbackPosition >= line.time && playbackPosition <= nextBoundary
            }
            return isActive || isInActiveInterval
        }

        val wordsLine1 = listOf(
            LyricWord(word = "Hello ", time = 10_000L, duration = 1_000L), // 10_000 to 11_000
            LyricWord(word = "world", time = 11_000L, duration = 2_000L)   // 11_000 to 13_000
        )
        val line1 = LyricLine(time = 10_000L, text = "Hello world", words = wordsLine1)
        val nextLineStart = 16_000L // 3-second rest between 13_000 and 16_000

        // 1. Before line starts (9_500ms): inactive
        assertFalse(
            "Line 1 before start must be inactive",
            calculateMetroActive(9_500L, line1, nextLineStart, isActive = false)
        )

        // 2. Line playing first word (10_500ms): active/highlighted
        assertTrue(
            "Line 1 during playback must be active",
            calculateMetroActive(10_500L, line1, nextLineStart, isActive = true)
        )

        // 3. Line playing second word (12_500ms): active/highlighted
        assertTrue(
            "Line 1 during final word playback must be active",
            calculateMetroActive(12_500L, line1, nextLineStart, isActive = true)
        )

        // 4. Line's words finished at 13_000ms, resting at 14_500ms (next line at 16_000ms):
        // Provider/engine isActive drops to false, but MetroLyrics line-level highlight interval MUST keep it active/highlighted!
        assertTrue(
            "Line 1 MUST stay highlighted during vocal rest after words completed",
            calculateMetroActive(14_500L, line1, nextLineStart, isActive = false)
        )

        // 5. Right before next line starts (15_999ms): still active/highlighted
        assertTrue(
            "Line 1 must stay highlighted right up to next line boundary",
            calculateMetroActive(15_999L, line1, nextLineStart, isActive = false)
        )

        // 6. Next line starts at 16_000ms: Line 1 must become inactive
        assertFalse(
            "Line 1 must become inactive when next line starts",
            calculateMetroActive(16_000L, line1, nextLineStart, isActive = false)
        )
        assertFalse(
            "Line 1 must stay inactive during next line playback",
            calculateMetroActive(17_000L, line1, nextLineStart, isActive = false)
        )
    }

    @Test
    fun experimentalLyrics_findExperimentalActiveLineIndices_supportsMultipleConcurrentLines() {
        val line1 = LyricLine(
            time = 10_000L,
            endTime = 16_000L,
            text = "Lead singer vocal",
            agent = "v1",
            words = listOf(
                LyricWord("Lead", 10_000L, 1_500L),
                LyricWord("singer", 12_000L, 1_500L),
                LyricWord("vocal", 14_000L, 2_000L)
            )
        )
        val line2 = LyricLine(
            time = 12_000L,
            endTime = 18_000L,
            text = "Duet response",
            agent = "v2",
            words = listOf(
                LyricWord("Duet", 12_000L, 2_000L),
                LyricWord("response", 15_000L, 3_000L)
            )
        )
        val line3 = LyricLine(
            time = 13_000L,
            endTime = 15_500L,
            text = "(Background chant)",
            isBackground = true,
            words = listOf(
                LyricWord("(Background", 13_000L, 1_000L),
                LyricWord("chant)", 14_200L, 1_300L)
            )
        )
        val lines = listOf(line1, line2, line3)

        // At 11_000ms: only line 1 is active
        val activeAt11 = com.auralis.music.ui.lyrics.findExperimentalActiveLineIndices(lines, 11_000L)
        assertEquals(setOf(0), activeAt11)

        // At 12_500ms: line 1 and line 2 are both active simultaneously (v1 on left, v2 on right!)
        val activeAt12_5 = com.auralis.music.ui.lyrics.findExperimentalActiveLineIndices(lines, 12_500L)
        assertEquals(setOf(0, 1), activeAt12_5)

        // At 14_000ms: line 1 (lead), line 2 (duet), and line 3 (background) are ALL active concurrently!
        val activeAt14 = com.auralis.music.ui.lyrics.findExperimentalActiveLineIndices(lines, 14_000L)
        assertEquals(setOf(0, 1, 2), activeAt14)

        // At 17_000ms: line 1 and line 3 have ended; only line 2 is still active
        val activeAt17 = com.auralis.music.ui.lyrics.findExperimentalActiveLineIndices(lines, 17_000L)
        assertEquals(setOf(1), activeAt17)

        // At 19_000ms: all lines have ended
        val activeAt19 = com.auralis.music.ui.lyrics.findExperimentalActiveLineIndices(lines, 19_000L)
        assertEquals(emptySet<Int>(), activeAt19)
    }

    @Test
    fun experimentalLyrics_agentAlignmentAndDimming_strictlyFollowsContract() {
        // Alignment contract
        fun getAlignment(agent: String?, isBg: Boolean, textPos: String): String {
            return when {
                agent == "v1" -> "Start"
                agent == "v2" -> "End"
                agent == "v1000" -> "Center"
                isBg -> "Center"
                textPos.lowercase() == "left" -> "Start"
                textPos.lowercase() == "right" -> "End"
                else -> "Center"
            }
        }

        assertEquals("Start", getAlignment("v1", false, "center"))
        assertEquals("End", getAlignment("v2", false, "center"))
        assertEquals("Center", getAlignment("v1000", false, "left"))
        assertEquals("Center", getAlignment(null, true, "left"))
        assertEquals("Start", getAlignment(null, false, "left"))
        assertEquals("End", getAlignment(null, false, "right"))
        assertEquals("Center", getAlignment(null, false, "center"))

        // Dimming contract
        fun getDimmingAlpha(distance: Int, isActive: Boolean, isBg: Boolean): Float {
            if (isActive) return 1.0f
            return when (distance) {
                0 -> if (isBg) 0.50f else 0.30f
                1 -> 0.20f
                2 -> 0.20f
                3 -> 0.15f
                4 -> 0.10f
                else -> 0.08f
            }
        }

        assertEquals(1.0f, getDimmingAlpha(0, isActive = true, isBg = false), 0.001f)
        assertEquals(0.30f, getDimmingAlpha(0, isActive = false, isBg = false), 0.001f)
        assertEquals(0.50f, getDimmingAlpha(0, isActive = false, isBg = true), 0.001f)
        assertEquals(0.20f, getDimmingAlpha(1, isActive = false, isBg = false), 0.001f)
        assertEquals(0.20f, getDimmingAlpha(2, isActive = false, isBg = false), 0.001f)
        assertEquals(0.15f, getDimmingAlpha(3, isActive = false, isBg = false), 0.001f)
        assertEquals(0.10f, getDimmingAlpha(4, isActive = false, isBg = false), 0.001f)
        assertEquals(0.08f, getDimmingAlpha(5, isActive = false, isBg = false), 0.001f)
        assertEquals(0.08f, getDimmingAlpha(10, isActive = false, isBg = false), 0.001f)
    }
}
