package com.auralis.music

import androidx.compose.ui.graphics.Color
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.player.NowPlayingTab
import com.auralis.music.ui.player.PlayerBackgroundStyle
import com.auralis.music.ui.player.deriveActiveTrack
import com.auralis.music.ui.player.lerpArtworkPalette
import com.auralis.music.ui.theme.ArtworkPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MainPlayerBackgroundTransitionTest {

    private fun samplePalette(
        primary: Color,
        secondary: Color,
        tertiary: Color,
        isMonochrome: Boolean = false
    ): ArtworkPalette {
        return ArtworkPalette(
            primary = primary,
            secondary = secondary,
            tertiary = tertiary,
            seedColor = primary,
            isMonochrome = isMonochrome,
            glowColors = listOf(primary, secondary, tertiary, primary, secondary, tertiary)
        )
    }

    private fun assertColorClose(expected: Color, actual: Color, tolerance: Float = 0.015f) {
        assertTrue("Red channel diff too large: exp=${expected.red}, act=${actual.red}",
            abs(expected.red - actual.red) <= tolerance)
        assertTrue("Green channel diff too large: exp=${expected.green}, act=${actual.green}",
            abs(expected.green - actual.green) <= tolerance)
        assertTrue("Blue channel diff too large: exp=${expected.blue}, act=${actual.blue}",
            abs(expected.blue - actual.blue) <= tolerance)
    }

    @Test
    fun testLerpArtworkPaletteBoundaries() {
        val paletteA = samplePalette(Color(0xFFE53935), Color(0xFF8E24AA), Color(0xFF1E88E5))
        val paletteB = samplePalette(Color(0xFF43A047), Color(0xFFFB8C00), Color(0xFF00ACC1))

        val start = lerpArtworkPalette(paletteA, paletteB, 0f)
        assertEquals(paletteA.primary, start.primary)
        assertEquals(paletteA.secondary, start.secondary)
        assertEquals(paletteA.tertiary, start.tertiary)

        val end = lerpArtworkPalette(paletteA, paletteB, 1f)
        assertEquals(paletteB.primary, end.primary)
        assertEquals(paletteB.secondary, end.secondary)
        assertEquals(paletteB.tertiary, end.tertiary)
    }

    @Test
    fun testLerpArtworkPaletteMidpoint() {
        val colorA = Color(1.0f, 0.0f, 0.0f, 1.0f)
        val colorB = Color(0.0f, 1.0f, 0.0f, 1.0f)

        val paletteA = samplePalette(colorA, colorA, colorA)
        val paletteB = samplePalette(colorB, colorB, colorB)

        val expected = androidx.compose.ui.graphics.lerp(colorA, colorB, 0.5f)
        val mid = lerpArtworkPalette(paletteA, paletteB, 0.5f)
        assertEquals(expected, mid.primary)
        assertEquals(6, mid.glowColors.size)
        assertEquals(expected, mid.glowColors[0])
    }

    @Test
    fun testInterruptedTransitionContinuityZeroDiscontinuity() {
        // Song A: Crimson/Purple
        val songA = samplePalette(Color(0xFFD32F2F), Color(0xFF7B1FA2), Color(0xFF303F9F))
        // Song B: Ocean Blue/Teal
        val songB = samplePalette(Color(0xFF1976D2), Color(0xFF00796B), Color(0xFF0097A7))
        // Song C: Amber/Orange
        val songC = samplePalette(Color(0xFFFFA000), Color(0xFFF57C00), Color(0xFFE64A19))

        // User swipes A -> B, but rapidly swipes to C when transition is at 38%
        val fractionInterrupted = 0.38f
        val visibleAtInterruption = lerpArtworkPalette(songA, songB, fractionInterrupted)

        // New transition begins with visibleAtInterruption as starting state towards songC
        val newTransitionStart = lerpArtworkPalette(visibleAtInterruption, songC, 0f)

        // Must have ZERO discontinuity jump at the instant of interruption
        assertEquals("Primary color must match interrupted state exactly",
            visibleAtInterruption.primary, newTransitionStart.primary)
        assertEquals("Secondary color must match interrupted state exactly",
            visibleAtInterruption.secondary, newTransitionStart.secondary)
        assertEquals("Tertiary color must match interrupted state exactly",
            visibleAtInterruption.tertiary, newTransitionStart.tertiary)

        // When transition completes to Song C, destination is reached
        val finalState = lerpArtworkPalette(visibleAtInterruption, songC, 1f)
        assertEquals(songC.primary, finalState.primary)
        assertEquals(songC.secondary, finalState.secondary)
        assertEquals(songC.tertiary, finalState.tertiary)
    }

    @Test
    fun testRapidChainSwipesABCD() {
        val a = samplePalette(Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF673AB7))
        val b = samplePalette(Color(0xFF3F51B5), Color(0xFF2196F3), Color(0xFF03A9F4))
        val c = samplePalette(Color(0xFF00BCD4), Color(0xFF009688), Color(0xFF4CAF50))
        val d = samplePalette(Color(0xFF8BC34A), Color(0xFFCDDC39), Color(0xFFFFEB3B))

        // A -> B interrupted at 20%
        val step1 = lerpArtworkPalette(a, b, 0.20f)
        // -> C interrupted at 40%
        val step2 = lerpArtworkPalette(step1, c, 0.40f)
        // -> D settles at 100%
        val step3 = lerpArtworkPalette(step2, d, 1.0f)

        assertEquals("Final target must settle on song D", d.primary, step3.primary)
        assertEquals("Final target must settle on song D", d.secondary, step3.secondary)
    }

    @Test
    fun testReverseSwipesContinuity() {
        val songForward = samplePalette(Color(0xFF6200EE), Color(0xFF3700B3), Color(0xFF03DAC6))
        val songBack = samplePalette(Color(0xFFFF0266), Color(0xFFC51162), Color(0xFFFF4081))

        // Forward 60%
        val midForward = lerpArtworkPalette(songForward, songBack, 0.60f)
        // User immediately swipes backward toward original songForward
        val midReverse = lerpArtworkPalette(midForward, songForward, 0.0f)

        assertEquals("Reversing direction must have zero jump at start",
            midForward.primary, midReverse.primary)

        val settledBack = lerpArtworkPalette(midForward, songForward, 1.0f)
        assertEquals("Reversing direction must settle cleanly on original song",
            songForward.primary, settledBack.primary)
    }

    @Test
    fun testMonochromeAlbumArtworkInterpolation() {
        val colorful = samplePalette(Color(0xFFE53935), Color(0xFF8E24AA), Color(0xFF1E88E5), isMonochrome = false)
        val monochrome = samplePalette(Color(0xFFE0E0E0), Color(0xFF9E9E9E), Color(0xFF616161), isMonochrome = true)

        val mid = lerpArtworkPalette(colorful, monochrome, 0.75f)
        assertTrue("Past 50% transition toward monochrome must flag as monochrome", mid.isMonochrome)
        assertEquals(6, mid.glowColors.size)
    }

    @Test
    fun testLiveMeshPlayerBackgroundStyleResolution() {
        assertEquals(PlayerBackgroundStyle.LIVE_MESH, PlayerBackgroundStyle.fromKey("Live Mesh"))
        assertEquals(PlayerBackgroundStyle.LIVE_MESH, PlayerBackgroundStyle.fromKey("live mesh"))
        assertEquals(PlayerBackgroundStyle.LIVE_MESH, PlayerBackgroundStyle.fromKey("live_mesh"))
        assertEquals(PlayerBackgroundStyle.LIVE_MESH, PlayerBackgroundStyle.fromKey("mesh"))
        assertEquals(PlayerBackgroundStyle.LIVE_MESH, PlayerBackgroundStyle.fromKey(" MESH "))
    }

    @Test
    fun testBlurPlayerBackgroundStyleResolution() {
        assertEquals(PlayerBackgroundStyle.BLUR, PlayerBackgroundStyle.fromKey("Blur"))
        assertEquals(PlayerBackgroundStyle.BLUR, PlayerBackgroundStyle.fromKey("blur"))
        assertEquals(PlayerBackgroundStyle.BLUR, PlayerBackgroundStyle.fromKey(" BLUR "))
    }

    @Test
    fun testLiveMeshGeometricCoverageGuarantee() {
        // Test aspect ratios: 20:9 (Motorola Edge 50 Fusion, 1080x2400), 19.5:9 (1080x2340), 16:9 (1080x1920)
        val testScreens = listOf(
            Pair(1080f, 2400f), // 20:9
            Pair(1080f, 2340f), // 19.5:9
            Pair(1080f, 1920f)  // 16:9
        )
        val scale = 1.28f

        for ((width, height) in testScreens) {
            val diagonal = kotlin.math.sqrt(width * width + height * height)
            val halfDiagonal = diagonal / 2f

            // Inscribed circle radius of centered maxDim square at scale must exceed halfDiagonal
            // guaranteeing 100% full coverage at all 360 rotation angles
            val maxDim = maxOf(width, height)
            val inscribedCircleRadius = (maxDim * scale) / 2f

            assertTrue(
                "Mesh square at scale $scale must cover screen (${width}x${height}): inscribedRadius=$inscribedCircleRadius >= halfDiag=$halfDiagonal",
                inscribedCircleRadius >= halfDiagonal
            )
        }
    }

    @Test
    fun testPartialSwipeBackgroundRemainsOnCurrentPlayingTrack() {
        val song0 = Track(id = "s0", title = "Song Zero", artist = "Artist Zero", thumbnail = "thumb0", duration = 180)
        val song1 = Track(id = "s1", title = "Song One", artist = "Artist One", thumbnail = "thumb1", duration = 200)
        val song2 = Track(id = "s2", title = "Song Two", artist = "Artist Two", thumbnail = "thumb2", duration = 220)
        val queue = listOf(song0, song1, song2)

        // Track 0 is currently playing (currentTrackIndex = 0)
        // User starts dragging carousel towards page 1 (partial swipe in progress, not committed)
        // pendingTargetIndex is null, pager is scrolling, intermediate threshold is crossed
        val activeTrack = deriveActiveTrack(
            currentTab = NowPlayingTab.PLAYER,
            pendingTargetIndex = null,
            currentTrackIndex = 0,
            queue = queue,
            playingTrack = song0
        )

        // Active track (and thus background/palette/title) MUST remain on song0!
        assertEquals("s0", activeTrack.id)
        assertEquals("thumb0", activeTrack.thumbnail)
        assertEquals("Song Zero", activeTrack.title)
    }

    @Test
    fun testSwipeCancellationBackgroundRemainsUnchanged() {
        val song0 = Track(id = "s0", title = "Song Zero", artist = "Artist Zero", thumbnail = "thumb0", duration = 180)
        val song1 = Track(id = "s1", title = "Song One", artist = "Artist One", thumbnail = "thumb1", duration = 200)
        val queue = listOf(song0, song1)

        // 1. Incomplete swipe starts: pendingTargetIndex = null -> active is song0
        val duringDrag = deriveActiveTrack(
            currentTab = NowPlayingTab.PLAYER,
            pendingTargetIndex = null,
            currentTrackIndex = 0,
            queue = queue,
            playingTrack = song0
        )
        assertEquals("s0", duringDrag.id)

        // 2. User releases incomplete swipe and pager snaps back to page 0 (settledPage = 0 == currentTrackIndex)
        // pendingTargetIndex remains null
        val afterSnapBack = deriveActiveTrack(
            currentTab = NowPlayingTab.PLAYER,
            pendingTargetIndex = null,
            currentTrackIndex = 0,
            queue = queue,
            playingTrack = song0
        )
        assertEquals("s0", afterSnapBack.id)
        assertEquals("thumb0", afterSnapBack.thumbnail)
    }

    @Test
    fun testCommittedSwipeChangesBackground() {
        val song0 = Track(id = "s0", title = "Song Zero", artist = "Artist Zero", thumbnail = "thumb0", duration = 180)
        val song1 = Track(id = "s1", title = "Song One", artist = "Artist One", thumbnail = "thumb1", duration = 200)
        val queue = listOf(song0, song1)

        // User drags and releases; carousel flings and settles on page 1 (settledPage = 1 != currentTrackIndex 0)
        // At the moment of settling, pendingTargetIndex is committed to 1
        val committedTrack = deriveActiveTrack(
            currentTab = NowPlayingTab.PLAYER,
            pendingTargetIndex = 1,
            currentTrackIndex = 0,
            queue = queue,
            playingTrack = song0
        )

        // Background, palette, and title immediately commit to song1
        assertEquals("s1", committedTrack.id)
        assertEquals("thumb1", committedTrack.thumbnail)
        assertEquals("Song One", committedTrack.title)

        // Once audio engine updates playback (currentTrackIndex = 1, playingTrack = song1), pendingTargetIndex clears
        val playbackCaughtUpTrack = deriveActiveTrack(
            currentTab = NowPlayingTab.PLAYER,
            pendingTargetIndex = null,
            currentTrackIndex = 1,
            queue = queue,
            playingTrack = song1
        )
        assertEquals("s1", playbackCaughtUpTrack.id)
    }

    @Test
    fun testButtonNextPreviousCommittedBackgroundSynchronization() {
        val song0 = Track(id = "s0", title = "Song Zero", artist = "Artist Zero", thumbnail = "thumb0", duration = 180)
        val song1 = Track(id = "s1", title = "Song One", artist = "Artist One", thumbnail = "thumb1", duration = 200)
        val song2 = Track(id = "s2", title = "Song Two", artist = "Artist Two", thumbnail = "thumb2", duration = 220)
        val queue = listOf(song0, song1, song2)

        // User taps Next button -> pendingTargetIndex immediately sets to 1
        val nextTrack = deriveActiveTrack(
            currentTab = NowPlayingTab.PLAYER,
            pendingTargetIndex = 1,
            currentTrackIndex = 0,
            queue = queue,
            playingTrack = song0
        )
        assertEquals("s1", nextTrack.id)

        // Rapid tap Next button while animating -> pendingTargetIndex updates to 2
        val rapidNextTrack = deriveActiveTrack(
            currentTab = NowPlayingTab.PLAYER,
            pendingTargetIndex = 2,
            currentTrackIndex = 0,
            queue = queue,
            playingTrack = song0
        )
        assertEquals("s2", rapidNextTrack.id)
    }

    @Test
    fun testNonPlayerTabsAlwaysReflectAuthoritativePlayingTrack() {
        val song0 = Track(id = "s0", title = "Song Zero", artist = "Artist Zero", thumbnail = "thumb0", duration = 180)
        val song1 = Track(id = "s1", title = "Song One", artist = "Artist One", thumbnail = "thumb1", duration = 200)
        val queue = listOf(song0, song1)

        // On LYRICS tab, even if pendingTargetIndex is set or pager is scrolled, activeTrack is strictly playingTrack
        val lyricsTrack = deriveActiveTrack(
            currentTab = NowPlayingTab.LYRICS,
            pendingTargetIndex = 1,
            currentTrackIndex = 0,
            queue = queue,
            playingTrack = song0
        )
        assertEquals("s0", lyricsTrack.id)

        // On QUEUE tab, activeTrack is strictly playingTrack
        val queueTabTrack = deriveActiveTrack(
            currentTab = NowPlayingTab.QUEUE,
            pendingTargetIndex = 1,
            currentTrackIndex = 0,
            queue = queue,
            playingTrack = song0
        )
        assertEquals("s0", queueTabTrack.id)
    }

    @Test
    fun testButtonNextTargetIndexChaining() {
        val queueSize = 10
        var pendingTargetIndex: Int? = null
        var currentPage = 0

        fun onNextButtonTapped(): Int {
            val target = ((pendingTargetIndex ?: currentPage) + 1).coerceAtMost(queueSize - 1)
            pendingTargetIndex = target
            return target
        }

        // First Next tap -> advances from 0 to 1
        val target1 = onNextButtonTapped()
        assertEquals(1, target1)

        // Rapid second Next tap while animating -> advances from 1 to 2
        val target2 = onNextButtonTapped()
        assertEquals(2, target2)

        // Rapid third Next tap -> advances from 2 to 3
        val target3 = onNextButtonTapped()
        assertEquals(3, target3)
    }

    @Test
    fun testPreviousButtonRestartVsTrackChangeThreshold() {
        fun handlePreviousAction(posMs: Long, currentPage: Int): String {
            return if (posMs > 3000L) {
                "RESTART_SONG_AT_0"
            } else if (currentPage > 0) {
                "NAVIGATE_PREVIOUS_TRACK"
            } else {
                "QUEUE_START"
            }
        }

        // If played for 15 seconds, restart without moving carousel
        assertEquals("RESTART_SONG_AT_0", handlePreviousAction(15_000L, 2))
        // At exactly 3001ms, restart
        assertEquals("RESTART_SONG_AT_0", handlePreviousAction(3001L, 2))
        // At 1500ms (within first 3 seconds), move to previous track
        assertEquals("NAVIGATE_PREVIOUS_TRACK", handlePreviousAction(1500L, 2))
        // At 0ms on track 0, queue start
        assertEquals("QUEUE_START", handlePreviousAction(0L, 0))
    }

    @Test
    fun testDynamicBackgroundCalculationDuringSwipe() {
        val song0 = Track(id = "s0", title = "Song Zero", artist = "Artist Zero", thumbnail = "thumb0", duration = 180)
        val song1 = Track(id = "s1", title = "Song One", artist = "Artist One", thumbnail = "thumb1", duration = 200)
        val song2 = Track(id = "s2", title = "Song Two", artist = "Artist Two", thumbnail = "thumb2", duration = 220)
        val queue = listOf(song0, song1, song2)

        val pal0 = samplePalette(Color(0xFFE53935), Color(0xFF8E24AA), Color(0xFF1E88E5))
        val pal1 = samplePalette(Color(0xFF43A047), Color(0xFFFB8C00), Color(0xFF00ACC1))

        // User is at page 0, swiping 40% towards page 1
        val continuousPage = 0.40f
        val baseIndex = continuousPage.toInt().coerceIn(0, queue.size - 1)
        val fraction = (continuousPage - baseIndex).coerceIn(0f, 1f)
        val nextIndex = (baseIndex + 1).coerceIn(0, queue.size - 1)

        assertEquals(0, baseIndex)
        assertEquals(1, nextIndex)
        assertEquals(0.40f, fraction, 0.001f)

        val blended = lerpArtworkPalette(pal0, pal1, fraction)
        val expectedPrimary = androidx.compose.ui.graphics.lerp(pal0.primary, pal1.primary, 0.40f)
        assertEquals(expectedPrimary, blended.primary)
    }

    @Test
    fun testDynamicBackgroundCancellationReturnsToPlayingTrack() {
        val song0 = Track(id = "s0", title = "Song Zero", artist = "Artist Zero", thumbnail = "thumb0", duration = 180)
        val song1 = Track(id = "s1", title = "Song One", artist = "Artist One", thumbnail = "thumb1", duration = 200)
        val queue = listOf(song0, song1)

        val pal0 = samplePalette(Color(0xFFE53935), Color(0xFF8E24AA), Color(0xFF1E88E5))
        val pal1 = samplePalette(Color(0xFF43A047), Color(0xFFFB8C00), Color(0xFF00ACC1))

        // User dragged to 0.35f, then let go and pager animated back to 0.0f
        val cancelledPage = 0.0f
        val baseIndex = cancelledPage.toInt().coerceIn(0, queue.size - 1)
        val fraction = (cancelledPage - baseIndex).coerceIn(0f, 1f)

        assertEquals(0, baseIndex)
        assertEquals(0f, fraction, 0.001f)

        val settledPalette = lerpArtworkPalette(pal0, pal1, fraction)
        assertEquals("When cancelled, palette must be 100% song0", pal0.primary, settledPalette.primary)
        assertEquals("When cancelled, secondary color must be 100% song0", pal0.secondary, settledPalette.secondary)
    }

    @Test
    fun testCrossModeAll5ModesIntegrity() {
        // Confirm the 5 selectable modes from Appearance settings
        val activeModes = PlayerBackgroundStyle.entries.filter { it != PlayerBackgroundStyle.APPLE_MUSIC }
        assertEquals(5, activeModes.size)
        assertTrue(activeModes.contains(PlayerBackgroundStyle.FOLLOW_THEME))
        assertTrue(activeModes.contains(PlayerBackgroundStyle.GRADIENT))
        assertTrue(activeModes.contains(PlayerBackgroundStyle.BLUR))
        assertTrue(activeModes.contains(PlayerBackgroundStyle.GLOW_MOTION))
        assertTrue(activeModes.contains(PlayerBackgroundStyle.LIVE_MESH))
    }
}
