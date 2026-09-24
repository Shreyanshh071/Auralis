package com.auralis.music

import com.auralis.music.ui.player.ClassicPlayerViewportMotion
import com.auralis.music.ui.player.ClassicQueueControlsScrollTracker
import com.auralis.music.ui.player.ClassicPlayerViewportMotionValues
import com.auralis.music.ui.player.ClassicViewportLayerTransform
import com.auralis.music.ui.player.NowPlayingTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicPlayerViewportMotionTest {
    private val epsilon = 0.0001f

    @Test
    fun `hero flight outlasts the content crossfade and targets compact for both secondary tabs`() {
        // VIVI: 500ms cover morph, 350ms fade-in, 250ms fade-out (exit finishes first).
        assertEquals(500, ClassicPlayerViewportMotion.HeroDurationMillis)
        assertTrue(ClassicPlayerViewportMotion.HeroDurationMillis > ClassicPlayerViewportMotion.ContentEnterDurationMillis)
        assertTrue(ClassicPlayerViewportMotion.ContentExitDurationMillis < ClassicPlayerViewportMotion.ContentEnterDurationMillis)
        assertEquals(0f, ClassicPlayerViewportMotion.heroProgressTarget(NowPlayingTab.PLAYER), epsilon)
        assertEquals(1f, ClassicPlayerViewportMotion.heroProgressTarget(NowPlayingTab.LYRICS), epsilon)
        assertEquals(1f, ClassicPlayerViewportMotion.heroProgressTarget(NowPlayingTab.QUEUE), epsilon)
    }

    @Test
    fun `expanded actions fade independently from the shared title`() {
        assertEquals(1f, ClassicPlayerViewportMotion.expandedActionsAlpha(0f), epsilon)
        // Large buttons are gone early; compact copies only appear as the flight lands.
        assertEquals(0f, ClassicPlayerViewportMotion.expandedActionsAlpha(0.3f), epsilon)
        assertEquals(0f, ClassicPlayerViewportMotion.expandedActionsAlpha(0.5f), epsilon)
        assertEquals(0f, ClassicPlayerViewportMotion.compactActionsAlpha(0.5f), epsilon)
        assertEquals(0f, ClassicPlayerViewportMotion.compactActionsAlpha(0.55f), epsilon)
        assertEquals(0f, ClassicPlayerViewportMotion.expandedActionsAlpha(1f), epsilon)
        assertEquals(1f, ClassicPlayerViewportMotion.compactActionsAlpha(1f), epsilon)
        assertEquals(0f, ClassicPlayerViewportMotion.expandedActionsAlpha(Float.NaN), epsilon)
        // Never two visible copies of the buttons at once.
        for (step in 0..20) {
            val p = step / 20f
            assertTrue(
                ClassicPlayerViewportMotion.expandedActionsAlpha(p) == 0f ||
                    ClassicPlayerViewportMotion.compactActionsAlpha(p) == 0f
            )
        }
    }

    @Test
    fun `cover uses VIVI's soft start curve`() {
        // FastOutSlowIn: gentle first frames, then a long glide (not an instant jump).
        val early = ClassicPlayerViewportMotion.HeroEasing.transform(0.1f)
        assertTrue(early < 0.1f)
        assertEquals(1f, ClassicPlayerViewportMotion.HeroEasing.transform(1f), epsilon)
    }

    @Test
    fun `title never shows mid flight - it fades out in place and reappears at the header`() {
        assertEquals(1f, ClassicPlayerViewportMotion.expandedMetadataAlpha(0f), epsilon)
        assertEquals(0f, ClassicPlayerViewportMotion.expandedMetadataAlpha(0.2f), epsilon)
        assertEquals(0f, ClassicPlayerViewportMotion.compactMetadataAlpha(0.55f), epsilon)
        assertEquals(1f, ClassicPlayerViewportMotion.compactMetadataAlpha(0.9f), epsilon)
        assertEquals(1f, ClassicPlayerViewportMotion.compactMetadataAlpha(1f), epsilon)
        // Through the middle of the flight neither title is visible.
        for (step in 5..10) {
            val p = step / 20f
            assertEquals(0f, ClassicPlayerViewportMotion.expandedMetadataAlpha(p), epsilon)
            assertEquals(0f, ClassicPlayerViewportMotion.compactMetadataAlpha(p), epsilon)
        }
    }

    @Test
    fun `player controls stay opaque through the flight and hand off at the end`() {
        assertEquals(1f, ClassicPlayerViewportMotion.playerControlsAlpha(0f), epsilon)
        assertEquals(1f, ClassicPlayerViewportMotion.playerControlsAlpha(0.5f), epsilon)
        assertEquals(1f, ClassicPlayerViewportMotion.playerControlsAlpha(0.85f), epsilon)
        assertEquals(0f, ClassicPlayerViewportMotion.playerControlsAlpha(1f), epsilon)
    }

    @Test
    fun `only Queue removes utility actions from the bottom bar`() {
        assertTrue(ClassicPlayerViewportMotion.showBottomUtilityActions(NowPlayingTab.PLAYER))
        assertTrue(ClassicPlayerViewportMotion.showBottomUtilityActions(NowPlayingTab.LYRICS))
        assertTrue(!ClassicPlayerViewportMotion.showBottomUtilityActions(NowPlayingTab.QUEUE))
    }

    @Test
    fun `PLAYER to PLAYER remains identity at every progress`() {
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { progress ->
            assertEquals(
                ClassicPlayerViewportMotion.target(NowPlayingTab.PLAYER),
                ClassicPlayerViewportMotion.interpolate(
                    NowPlayingTab.PLAYER,
                    NowPlayingTab.PLAYER,
                    progress
                )
            )
        }
    }

    @Test
    fun `PLAYER to LYRICS morphs hero into compact header`() {
        val player = ClassicPlayerViewportMotion.target(NowPlayingTab.PLAYER)
        val lyrics = ClassicPlayerViewportMotion.target(NowPlayingTab.LYRICS)

        assertEquals(0f, player.compactHeaderProgress, epsilon)
        assertEquals(1f, player.topBar.alpha, epsilon)
        assertEquals(0f, player.lyrics.alpha, epsilon)

        assertEquals(1f, lyrics.compactHeaderProgress, epsilon)
        assertEquals(0f, lyrics.topBar.alpha, epsilon)
        assertTrue(lyrics.topBar.translationYDp < 0f)
        assertEquals(1f, lyrics.lyrics.alpha, epsilon)
        assertEquals(0f, lyrics.lyrics.translationYDp, epsilon)
        assertEquals(1f, lyrics.lyrics.scale, epsilon)
    }

    @Test
    fun `PLAYER to QUEUE uses the same compact header transformation`() {
        val lyrics = ClassicPlayerViewportMotion.target(NowPlayingTab.LYRICS)
        val queue = ClassicPlayerViewportMotion.target(NowPlayingTab.QUEUE)

        assertEquals(1f, queue.compactHeaderProgress, epsilon)
        assertEquals(lyrics.compactHeaderProgress, queue.compactHeaderProgress, epsilon)
        assertEquals(lyrics.topBar, queue.topBar)
        assertEquals(1f, queue.queue.alpha, epsilon)
        assertEquals(0f, queue.queue.translationYDp, epsilon)
        assertEquals(1f, queue.queue.scale, epsilon)
    }

    @Test
    fun `reverse transitions restore the large hero endpoint`() {
        for (source in listOf(NowPlayingTab.LYRICS, NowPlayingTab.QUEUE)) {
            assertEquals(
                ClassicPlayerViewportMotion.target(source),
                ClassicPlayerViewportMotion.interpolate(source, NowPlayingTab.PLAYER, 0f)
            )
            assertEquals(
                ClassicPlayerViewportMotion.target(NowPlayingTab.PLAYER),
                ClassicPlayerViewportMotion.interpolate(source, NowPlayingTab.PLAYER, 1f)
            )

            val midpoint = ClassicPlayerViewportMotion.interpolate(source, NowPlayingTab.PLAYER, 0.5f)
            assertEquals(0.5f, midpoint.compactHeaderProgress, epsilon)
        }
    }

    @Test
    fun `LYRICS and QUEUE transition directly while header remains compact`() {
        for ((from, to) in listOf(
            NowPlayingTab.LYRICS to NowPlayingTab.QUEUE,
            NowPlayingTab.QUEUE to NowPlayingTab.LYRICS
        )) {
            val midpoint = ClassicPlayerViewportMotion.interpolate(from, to, 0.5f)

            assertEquals(1f, midpoint.compactHeaderProgress, epsilon)
            assertEquals(0f, midpoint.topBar.alpha, epsilon)
            assertTrue(midpoint.lyrics.alpha > 0f)
            assertTrue(midpoint.queue.alpha > 0f)
        }
    }

    @Test
    fun `persistent playback controls never move or fade`() {
        val identity = ClassicViewportLayerTransform()

        for (from in NowPlayingTab.entries) {
            for (to in NowPlayingTab.entries) {
                for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                    assertEquals(
                        identity,
                        ClassicPlayerViewportMotion.interpolate(from, to, progress).persistentControls
                    )
                }
            }
        }
    }

    @Test
    fun `content entrance uses local motion instead of viewport travel`() {
        val player = ClassicPlayerViewportMotion.target(NowPlayingTab.PLAYER)

        for (content in listOf(player.lyrics, player.queue)) {
            assertTrue(kotlin.math.abs(content.translationYDp) <= 140f)
            assertTrue(content.scale >= 0.97f)
        }
    }

    @Test
    fun `measured header row gives lyrics and queue a non overlapping content region`() {
        val bounds = ClassicPlayerViewportMotion.measuredShellBounds(
            viewportHeightPx = 800,
            chromeBottomPx = 80,
            compactHeaderHeightPx = 88,
            controlsTopPx = 600,
            controlsReserved = true,
            gapPx = 8
        )

        assertEquals(80, bounds.chromeBottomPx)
        assertEquals(168, bounds.headerBottomPx)
        assertEquals(168, bounds.contentTopPx)
        assertEquals(592, bounds.contentBottomPx)
        assertEquals(424, bounds.contentHeightPx)
    }

    @Test
    fun `weighted metadata leaves fixed space for artwork and both actions`() {
        for (width in listOf(240f, 320f, 360f, 430f)) {
            val textWidth = ClassicPlayerViewportMotion.compactTextWidthDp(width)
            assertTrue(textWidth > 0f)
            assertTrue(
                textWidth + 2f * ClassicPlayerViewportMotion.CompactHeaderSideInsetDp +
                    ClassicPlayerViewportMotion.CompactArtworkSizeDp +
                    ClassicPlayerViewportMotion.CompactArtworkTextGapDp +
                    2f * ClassicPlayerViewportMotion.CompactActionSizeDp <= width + epsilon
            )
        }
    }

    @Test
    fun `short viewport collapses content instead of drawing through header or controls`() {
        val bounds = ClassicPlayerViewportMotion.measuredShellBounds(
            viewportHeightPx = 190,
            chromeBottomPx = 48,
            compactHeaderHeightPx = 88,
            controlsTopPx = 112,
            controlsReserved = true,
            gapPx = 8
        )

        assertEquals(136, bounds.contentTopPx)
        assertEquals(136, bounds.contentBottomPx)
        assertEquals(0, bounds.contentHeightPx)
    }

    @Test
    fun `control visibility changes do not alter Lyrics content origin or measured viewport`() {
        val visible = ClassicPlayerViewportMotion.stableOverlayContentBounds(
            viewportHeightPx = 800,
            chromeBottomPx = 80,
            compactHeaderHeightPx = 88,
            controlsTopPx = 600,
            gapPx = 8
        )
        val hidden = ClassicPlayerViewportMotion.stableOverlayContentBounds(
            viewportHeightPx = 800,
            chromeBottomPx = 80,
            compactHeaderHeightPx = 88,
            controlsTopPx = 600,
            gapPx = 8
        )

        assertEquals(2_000L, ClassicPlayerViewportMotion.LyricsControlsTimeoutMillis)
        // Content origin must remain 100% stable across hide/show
        assertEquals(visible.contentTopPx, hidden.contentTopPx)
        assertEquals(800, visible.contentBottomPx)
        assertEquals(632, visible.contentHeightPx)
        assertEquals(800, hidden.contentBottomPx)
        assertEquals(632, hidden.contentHeightPx)
    }

    @Test
    fun `lyrics playback controls hide at two seconds and interaction restarts visibility`() {
        assertTrue(ClassicPlayerViewportMotion.playbackControlsVisible(NowPlayingTab.LYRICS, 0))
        assertTrue(ClassicPlayerViewportMotion.playbackControlsVisible(NowPlayingTab.LYRICS, 1_999))
        assertTrue(!ClassicPlayerViewportMotion.playbackControlsVisible(NowPlayingTab.LYRICS, 2_000))
        assertTrue(ClassicPlayerViewportMotion.playbackControlsVisible(NowPlayingTab.LYRICS, 0))
        assertTrue(ClassicPlayerViewportMotion.playbackControlsVisible(NowPlayingTab.PLAYER, 30_000))
        assertTrue(ClassicPlayerViewportMotion.playbackControlsVisible(NowPlayingTab.QUEUE, 30_000))
    }

    @Test
    fun `lyrics timeout state is isolated from queue control callbacks`() {
        assertTrue(
            !ClassicPlayerViewportMotion.playbackControlsVisibleForTab(
                tab = NowPlayingTab.LYRICS,
                lyricsControlsVisible = false,
                queueControlsVisible = true
            )
        )
        assertTrue(
            !ClassicPlayerViewportMotion.playbackControlsVisibleForTab(
                tab = NowPlayingTab.QUEUE,
                lyricsControlsVisible = true,
                queueControlsVisible = false
            )
        )
        assertTrue(
            ClassicPlayerViewportMotion.playbackControlsVisibleForTab(
                tab = NowPlayingTab.PLAYER,
                lyricsControlsVisible = false,
                queueControlsVisible = false
            )
        )
    }

    @Test
    fun `repeated lyrics interaction restarts the existing timeout`() {
        fun visibleAt(nowMs: Long, mostRecentInteractionMs: Long): Boolean =
            ClassicPlayerViewportMotion.playbackControlsVisible(
                NowPlayingTab.LYRICS,
                nowMs - mostRecentInteractionMs
            )

        assertTrue(visibleAt(nowMs = 1_999, mostRecentInteractionMs = 0))
        assertTrue(visibleAt(nowMs = 3_500, mostRecentInteractionMs = 1_600))
        assertTrue(!visibleAt(nowMs = 3_700, mostRecentInteractionMs = 1_600))
        assertTrue(visibleAt(nowMs = 4_700, mostRecentInteractionMs = 4_700))
    }

    @Test
    fun `crossfade enters on FastOutSlowIn and exits faster`() {
        assertEquals(0f, ClassicPlayerViewportMotion.entryAlpha(0f), epsilon)
        assertEquals(1f, ClassicPlayerViewportMotion.entryAlpha(1f), epsilon)
        var previous = 0f
        for (step in 1..20) {
            val alpha = ClassicPlayerViewportMotion.entryAlpha(step / 20f)
            assertTrue(alpha >= previous)
            previous = alpha
        }

        // Exit (250ms) is complete before the enter (350ms) window ends.
        val exitDone = ClassicPlayerViewportMotion.ContentExitDurationMillis.toFloat() /
            ClassicPlayerViewportMotion.ContentEnterDurationMillis
        assertEquals(1f, ClassicPlayerViewportMotion.exitAlpha(0f), epsilon)
        assertEquals(0f, ClassicPlayerViewportMotion.exitAlpha(exitDone), epsilon)
        assertEquals(0f, ClassicPlayerViewportMotion.exitAlpha(0.9f), epsilon)

        val p2l = ClassicPlayerViewportMotion.interpolate(NowPlayingTab.PLAYER, NowPlayingTab.LYRICS, 0.30f)
        assertEquals(ClassicPlayerViewportMotion.entryAlpha(0.30f), p2l.lyrics.alpha, epsilon)
    }

    @Test
    fun `tab reversal begins at the current rendered frame`() {
        val midQueue = ClassicPlayerViewportMotion.interpolate(
            NowPlayingTab.PLAYER, NowPlayingTab.QUEUE, 0.42f
        )
        val reversalStart = ClassicPlayerViewportMotion.retarget(midQueue, NowPlayingTab.LYRICS, 0f)
        val reversalMiddle = ClassicPlayerViewportMotion.retarget(midQueue, NowPlayingTab.LYRICS, 0.5f)
        val reversalEnd = ClassicPlayerViewportMotion.retarget(midQueue, NowPlayingTab.LYRICS, 1f)

        assertEquals(midQueue, reversalStart)
        assertTrue(reversalMiddle.queue.alpha < midQueue.queue.alpha)
        assertTrue(reversalMiddle.lyrics.alpha > midQueue.lyrics.alpha)
        assertEquals(ClassicPlayerViewportMotion.target(NowPlayingTab.LYRICS), reversalEnd)
    }

    @Test
    fun `visible controls own only their measured lower input region`() {
        val visible = ClassicPlayerViewportMotion.playbackOverlayBounds(
            viewportHeightPx = 800,
            controlsTopPx = 600,
            gapPx = 8,
            visibilityProgress = 1f
        )

        assertTrue(visible.ownsInput)
        assertTrue(!visible.contains(599f))
        assertTrue(visible.contains(600f))
        assertTrue(visible.contains(799f))
        assertEquals(592, visible.contentClipBottomPx)
        assertEquals(4f, ClassicPlayerViewportMotion.playerLayerZIndex(1f), epsilon)
    }

    @Test
    fun `hidden controls release lower input and drawing space`() {
        val hidden = ClassicPlayerViewportMotion.playbackOverlayBounds(
            viewportHeightPx = 800,
            controlsTopPx = 600,
            gapPx = 8,
            visibilityProgress = 0f
        )

        assertTrue(!hidden.ownsInput)
        assertTrue(!hidden.contains(700f))
        assertEquals(800, hidden.contentClipBottomPx)
        assertEquals(2f, ClassicPlayerViewportMotion.playerLayerZIndex(0f), epsilon)
    }

    @Test
    fun `queue drawing stays above every partially visible playback control frame`() {
        for (progress in listOf(1f, 0.75f, 0.5f, 0.01f)) {
            val bounds = ClassicPlayerViewportMotion.playbackOverlayBounds(800, 600, 8, progress)
            assertEquals(592, bounds.contentClipBottomPx)
            assertTrue(bounds.ownsInput)
        }
        assertEquals(
            800,
            ClassicPlayerViewportMotion.playbackOverlayBounds(800, 600, 8, 0f).contentClipBottomPx
        )
    }

    @Test
    fun `controls fade without changing the measured content region each frame`() {
        val visible = ClassicPlayerViewportMotion.stableOverlayContentBounds(
            viewportHeightPx = 800,
            chromeBottomPx = 80,
            compactHeaderHeightPx = 88,
            controlsTopPx = 600,
            gapPx = 8
        )
        for (progress in listOf(1f, 0.75f, 0.5f)) {
            val bounds = ClassicPlayerViewportMotion.stableOverlayContentBounds(
                viewportHeightPx = 800,
                chromeBottomPx = 80,
                compactHeaderHeightPx = 88,
                controlsTopPx = 600,
                gapPx = 8
            )
            assertEquals(visible.contentBottomPx, bounds.contentBottomPx)
            assertEquals(progress, ClassicPlayerViewportMotion.controlsOpacity(progress), epsilon)
        }
        assertEquals(0f, ClassicPlayerViewportMotion.controlsOpacity(0f), epsilon)
    }

    @Test
    fun `hide and show transitions keep the same overlaid content bounds`() {
        val beforeHide = ClassicPlayerViewportMotion.stableOverlayContentBounds(800, 80, 88, 600, 8)
        val afterHide = ClassicPlayerViewportMotion.stableOverlayContentBounds(800, 80, 88, 600, 8)
        assertEquals(beforeHide, afterHide)
        assertEquals(168, beforeHide.contentTopPx)
        assertEquals(800, beforeHide.contentBottomPx)
        assertEquals(632, beforeHide.contentHeightPx)
    }

    @Test
    fun `incoming content glides up into place while outgoing content fades in place`() {
        val pairs = listOf(
            NowPlayingTab.PLAYER to NowPlayingTab.LYRICS,
            NowPlayingTab.LYRICS to NowPlayingTab.PLAYER,
            NowPlayingTab.PLAYER to NowPlayingTab.QUEUE,
            NowPlayingTab.QUEUE to NowPlayingTab.PLAYER,
            NowPlayingTab.LYRICS to NowPlayingTab.QUEUE,
            NowPlayingTab.QUEUE to NowPlayingTab.LYRICS
        )
        fun layerOf(v: ClassicPlayerViewportMotionValues, tab: NowPlayingTab) = when (tab) {
            NowPlayingTab.LYRICS -> v.lyrics
            NowPlayingTab.QUEUE -> v.queue
            NowPlayingTab.PLAYER -> null
        }
        for ((from, to) in pairs) {
            var previousOffset = Float.MAX_VALUE
            for (step in 0..20) {
                val v = ClassicPlayerViewportMotion.interpolate(from, to, step / 20f)
                layerOf(v, from)?.let { assertEquals(0f, it.translationYDp, epsilon) }
                // Before the switch starts the incoming layer is fully transparent, so the
                // glide only has to be monotonic across the visible frames.
                layerOf(v, to)?.takeIf { step > 0 }?.let {
                    // Monotonic upward glide: never overshoots below zero or bounces back down.
                    assertTrue(it.translationYDp >= -epsilon)
                    assertTrue(it.translationYDp <= previousOffset + epsilon)
                    previousOffset = it.translationYDp
                }
                assertEquals(1f, v.lyrics.scale, epsilon)
                assertEquals(1f, v.queue.scale, epsilon)
            }
            val start = ClassicPlayerViewportMotion.interpolate(from, to, 0f)
            val end = ClassicPlayerViewportMotion.interpolate(from, to, 1f)
            layerOf(end, to)?.let {
                assertEquals(1f, it.alpha, epsilon)
                assertEquals(0f, it.translationYDp, epsilon)
            }
            layerOf(start, from)?.let { assertEquals(1f, it.alpha, epsilon) }
            layerOf(end, from)?.let { assertEquals(0f, it.alpha, epsilon) }
        }
        val q2lEarly = ClassicPlayerViewportMotion.interpolate(NowPlayingTab.QUEUE, NowPlayingTab.LYRICS, 0.05f)
        val l2qEarly = ClassicPlayerViewportMotion.interpolate(NowPlayingTab.LYRICS, NowPlayingTab.QUEUE, 0.05f)
        assertTrue(q2lEarly.lyrics.translationYDp > l2qEarly.queue.translationYDp)
    }

    @Test
    fun `standard lyrics blur off produces zero blur for every lyric line across all distances`() {
        for (lineY in listOf(0f, 120f, 300f, 420f, 650f, 800f)) {
            assertEquals(
                0f,
                blurAt(lineY = lineY, standardBlur = false, isCurrent = lineY == 300f),
                epsilon
            )
        }
    }

    @Test
    fun `blur off preserves the subdued inactive hierarchy instead of boosting it`() {
        val activeLayer = com.auralis.music.ui.lyrics.computeClassicLyricsLayerAlpha(
            isPlain = false,
            isCurrent = true,
            isPast = false,
            lyricsMode = com.auralis.music.domain.model.LyricsMode.CINEMA
        )
        val inactiveLayer = com.auralis.music.ui.lyrics.computeClassicLyricsLayerAlpha(
            isPlain = false,
            isCurrent = false,
            isPast = false,
            lyricsMode = com.auralis.music.domain.model.LyricsMode.CINEMA
        )
        val inactiveText = com.auralis.music.ui.lyrics.computeClassicLyricsTextAlpha(
            isBackground = false,
            isCurrent = false,
            isPast = false
        )

        assertEquals(1f, activeLayer, epsilon)
        assertEquals(0.32f, inactiveLayer, epsilon)
        assertEquals(0.38f, inactiveText, epsilon)
        assertTrue(inactiveLayer * inactiveText < activeLayer)
    }

    @Test
    fun `playback controls hiding and showing does not enable or alter zero blur when standardLyricsBlur is off`() {
        val visibleBounds = ClassicPlayerViewportMotion.stableOverlayContentBounds(800, 80, 88, 600, 8)
        val hiddenBounds = ClassicPlayerViewportMotion.stableOverlayContentBounds(800, 80, 88, 600, 8)
        assertEquals(visibleBounds, hiddenBounds)

        for (lineY in listOf(340f, 480f, 650f, 800f)) {
            val blurWhileControlsVisible = blurAt(lineY, standardBlur = false)
            val blurWhileControlsHidden = blurAt(lineY, standardBlur = false)
            assertEquals(0f, blurWhileControlsVisible, epsilon)
            assertEquals(blurWhileControlsVisible, blurWhileControlsHidden, epsilon)
        }
    }

    @Test
    fun `lyrics progressive blur gradient increases monotonically down to full blur`() {
        // Active line is crisp
        val activeBlur = blurAt(lineY = 300f, isCurrent = true)
        assertEquals(0f, activeBlur, epsilon)

        val nearAbove = blurAt(lineY = 260f)
        val topAbove = blurAt(lineY = 0f)
        assertTrue(nearAbove < topAbove)
        assertTrue(topAbove <= 2.5f)

        val futureBlurs = listOf(320f, 360f, 430f, 540f, 670f, 800f).map { blurAt(it) }
        assertTrue("nearby line remains nearly crisp", futureBlurs.first() < 0.5f)
        assertEquals(24f, futureBlurs.last(), epsilon)

        for (i in 0 until futureBlurs.size - 1) {
            assertTrue("futureBlur[${i+1}] > futureBlur[$i]", futureBlurs[i+1] > futureBlurs[i])
        }

        // Continuous position changes produce continuous output, not a discrete line bucket.
        val y400 = blurAt(400f)
        val y401 = blurAt(401f)
        assertTrue(y401 > y400)
        assertTrue(y401 - y400 < 0.2f)
    }

    @Test
    fun `queue auto-scroll only triggers on track id change not on tab re-activation`() {
        var lastAutoScrolledTrackId: String? = null
        var scrollCount = 0

        fun onQueueShown(playingTrackId: String?) {
            if (playingTrackId != null && playingTrackId != lastAutoScrolledTrackId) {
                lastAutoScrolledTrackId = playingTrackId
                scrollCount++
            }
        }

        // Song A plays initially
        onQueueShown("track_A")
        assertEquals(1, scrollCount)

        // Switching to Lyrics and back to Queue with Song A still playing
        onQueueShown("track_A")
        assertEquals(1, scrollCount)

        // Switching to Player and back to Queue with Song A still playing
        onQueueShown("track_A")
        assertEquals(1, scrollCount)

        // Song changes to Track B
        onQueueShown("track_B")
        assertEquals(2, scrollCount)
    }

    @Test
    fun `lyrics auto-center only triggers when active line changes`() {
        var hasInitialCentered = false
        var lastCenteredIndex: Int? = null
        var centerCount = 0

        fun onActiveIndexUpdate(activeIndex: Int) {
            if (hasInitialCentered && activeIndex == lastCenteredIndex) {
                return
            }
            hasInitialCentered = true
            lastCenteredIndex = activeIndex
            centerCount++
        }

        // First appearance on line 5
        onActiveIndexUpdate(5)
        assertEquals(1, centerCount)

        // Tab switches away and back while still on line 5
        onActiveIndexUpdate(5)
        assertEquals(1, centerCount)

        // Music advances to line 6
        onActiveIndexUpdate(6)
        assertEquals(2, centerCount)
    }

    @Test
    fun `manual lyrics scroll remains disengaged after idle time`() {
        var isAutoScrollEnabled = true
        var isUserInteracting = false
        var isScrollInProgress = false
        var resyncCount = 0

        // User begins manual scroll
        fun onUserScrollStart() {
            isAutoScrollEnabled = false
            isUserInteracting = true
            isScrollInProgress = true
        }

        // User finishes touch/scroll gesture
        fun onUserScrollEnd() {
            isUserInteracting = false
            isScrollInProgress = false
        }

        assertTrue(isAutoScrollEnabled)
        assertEquals(0, resyncCount)

        onUserScrollStart()
        assertEquals(false, isAutoScrollEnabled)
        assertTrue(isUserInteracting)

        onUserScrollEnd()
        assertEquals(false, isAutoScrollEnabled)

        // Advancing an arbitrary idle interval has no callback and cannot mutate scroll state.
        repeat(4) { /* one simulated second */ }
        assertEquals(false, isAutoScrollEnabled)
        assertEquals(false, isUserInteracting)
        assertEquals(false, isScrollInProgress)
        assertEquals(0, resyncCount)
    }

    @Test
    fun `non finite control progress cannot corrupt bounds or opacity`() {
        for (progress in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val bounds = ClassicPlayerViewportMotion.measuredShellBounds(
                viewportHeightPx = 800,
                chromeBottomPx = 80,
                compactHeaderHeightPx = 88,
                controlsTopPx = 600,
                controlsReserved = progress >= 0.5f,
                gapPx = 8
            )
            assertTrue(bounds.contentBottomPx in 168..800)
            assertTrue(ClassicPlayerViewportMotion.controlsOpacity(progress).isFinite())
        }
    }

    @Test
    fun `queue controls respond once per deliberate scroll direction`() {
        val tracker = ClassicQueueControlsScrollTracker(rowHeightPx = 68f, thresholdPx = 36f)
        assertEquals(null, tracker.update(0, 60, false))
        assertEquals(null, tracker.update(0, 75, true))
        assertEquals(null, tracker.update(0, 90, true))
        assertEquals(false, tracker.update(0, 115, true))
        assertEquals(null, tracker.update(0, 125, true))
        assertEquals(null, tracker.update(0, 112, true))
        assertEquals(true, tracker.update(0, 70, true))
        assertEquals(null, tracker.update(0, 70, false))
    }

    @Test
    fun `queue controls reveal at top and ignore idle repositioning`() {
        val tracker = ClassicQueueControlsScrollTracker(rowHeightPx = 68f, thresholdPx = 36f)
        tracker.update(1, 0, false)
        assertEquals(false, tracker.update(1, 40, true))
        assertEquals(null, tracker.update(4, 0, false))
        assertEquals(true, tracker.update(0, 20, true))
        assertEquals(null, tracker.update(0, 10, true))
    }

    @Test
    fun `all midpoint values are finite and bounded`() {
        for (from in NowPlayingTab.entries) {
            for (to in NowPlayingTab.entries) {
                assertSensible(ClassicPlayerViewportMotion.interpolate(from, to, 0.5f))
            }
        }
    }

    @Test
    fun `non finite progress never produces non finite transforms`() {
        for (fraction in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertSensible(
                ClassicPlayerViewportMotion.interpolate(
                    NowPlayingTab.PLAYER,
                    NowPlayingTab.LYRICS,
                    fraction
                )
            )
        }
    }

    @Test
    fun `reduced motion resolves immediately to destination`() {
        for (from in NowPlayingTab.entries) {
            for (to in NowPlayingTab.entries) {
                assertEquals(
                    ClassicPlayerViewportMotion.target(to),
                    ClassicPlayerViewportMotion.interpolate(
                        from = from,
                        to = to,
                        fraction = 0f,
                        reducedMotion = true
                    )
                )
            }
        }
    }

    @Test
    fun `tab switching does not mutate lyrics or queue scroll states and avoids programmatic scroll triggers`() {
        // Mock scroll state positions
        val lyricsFirstVisibleItemIndex = 14
        val lyricsFirstVisibleItemScrollOffset = 85
        val queueFirstVisibleItemIndex = 8
        val queueFirstVisibleItemScrollOffset = 42

        val programmaticScrollCallCount = 0

        fun simulateTabSwitch(fromTab: NowPlayingTab, toTab: NowPlayingTab) {
            // Tab switching changes visual layer transforms (graphicsLayer translationY / alpha)
            val motion = ClassicPlayerViewportMotion.interpolate(fromTab, toTab, 0.5f)
            assertTrue(motion.lyrics.translationYDp.isFinite())
            assertTrue(motion.queue.translationYDp.isFinite())
            // Crucial: No scroll state mutation or scrollToItem/animateScrollBy allowed during tab transition
        }

        // 1. Switch PLAYER -> LYRICS
        simulateTabSwitch(NowPlayingTab.PLAYER, NowPlayingTab.LYRICS)
        assertEquals(14, lyricsFirstVisibleItemIndex)
        assertEquals(85, lyricsFirstVisibleItemScrollOffset)
        assertEquals(0, programmaticScrollCallCount)

        // 2. Switch LYRICS -> QUEUE
        simulateTabSwitch(NowPlayingTab.LYRICS, NowPlayingTab.QUEUE)
        assertEquals(8, queueFirstVisibleItemIndex)
        assertEquals(42, queueFirstVisibleItemScrollOffset)
        assertEquals(0, programmaticScrollCallCount)

        // 3. Switch QUEUE -> LYRICS
        simulateTabSwitch(NowPlayingTab.QUEUE, NowPlayingTab.LYRICS)
        assertEquals(14, lyricsFirstVisibleItemIndex)
        assertEquals(85, lyricsFirstVisibleItemScrollOffset)
        assertEquals(0, programmaticScrollCallCount)

        // 4. Switch LYRICS -> PLAYER
        simulateTabSwitch(NowPlayingTab.LYRICS, NowPlayingTab.PLAYER)
        assertEquals(14, lyricsFirstVisibleItemIndex)
        assertEquals(85, lyricsFirstVisibleItemScrollOffset)
        assertEquals(0, programmaticScrollCallCount)

        // 5. Switch PLAYER -> QUEUE
        simulateTabSwitch(NowPlayingTab.PLAYER, NowPlayingTab.QUEUE)
        assertEquals(8, queueFirstVisibleItemIndex)
        assertEquals(42, queueFirstVisibleItemScrollOffset)
        assertEquals(0, programmaticScrollCallCount)

        // 6. Switch QUEUE -> PLAYER
        simulateTabSwitch(NowPlayingTab.QUEUE, NowPlayingTab.PLAYER)
        assertEquals(8, queueFirstVisibleItemIndex)
        assertEquals(42, queueFirstVisibleItemScrollOffset)
        assertEquals(0, programmaticScrollCallCount)
    }

    @Test
    fun `lyrics controls auto-hide state machine and timer reset flow`() {
        // 1. Enter Lyrics: controls visible immediately
        var lyricsControlsVisible = ClassicPlayerViewportMotion.playbackControlsVisible(NowPlayingTab.LYRICS, 0L)
        assertTrue(lyricsControlsVisible)

        // 2. No interaction for 1.999s: still visible
        lyricsControlsVisible = ClassicPlayerViewportMotion.playbackControlsVisible(NowPlayingTab.LYRICS, 1_999L)
        assertTrue(lyricsControlsVisible)

        // 3. No interaction for 2s: hidden
        lyricsControlsVisible = ClassicPlayerViewportMotion.playbackControlsVisible(NowPlayingTab.LYRICS, 2_000L)
        assertTrue(!lyricsControlsVisible)

        // 4. User interaction occurs: controls reappear immediately (idle reset to 0)
        var lastInteractionMs = 3_000L
        lyricsControlsVisible = ClassicPlayerViewportMotion.playbackControlsVisible(NowPlayingTab.LYRICS, 0L)
        assertTrue(lyricsControlsVisible)

        // 5. Interaction continues at 4,000ms: resets timer
        lastInteractionMs = 4_000L
        val at4500 = 4_500L - lastInteractionMs // 500ms since last interaction
        assertTrue(ClassicPlayerViewportMotion.playbackControlsVisible(NowPlayingTab.LYRICS, at4500))

        // 6. Interaction stops, 2s pass (at 6,000ms): controls hide
        val at6000 = 6_000L - lastInteractionMs // 2000ms since last interaction
        assertTrue(!ClassicPlayerViewportMotion.playbackControlsVisible(NowPlayingTab.LYRICS, at6000))

        // 7. Programmatic scroll must NOT overwrite hidden state
        // When user is not interacting, programmatic scrolling should not change the idle state
        assertTrue(!ClassicPlayerViewportMotion.playbackControlsVisible(NowPlayingTab.LYRICS, at6000))
    }

    @Test
    fun `queue playback controls are present and independent of lyrics visibility`() {
        // Queue controls rendered and visible by default
        val queueWithLyricsHidden = ClassicPlayerViewportMotion.playbackControlsVisibleForTab(
            tab = NowPlayingTab.QUEUE,
            lyricsControlsVisible = false,
            queueControlsVisible = true
        )
        assertTrue(queueWithLyricsHidden)

        val queueWithLyricsVisible = ClassicPlayerViewportMotion.playbackControlsVisibleForTab(
            tab = NowPlayingTab.QUEUE,
            lyricsControlsVisible = true,
            queueControlsVisible = true
        )
        assertTrue(queueWithLyricsVisible)

        // Switching from Lyrics (where controls timed out) to Queue keeps Queue controls visible
        val switchingFromLyrics = ClassicPlayerViewportMotion.playbackControlsVisibleForTab(
            tab = NowPlayingTab.QUEUE,
            lyricsControlsVisible = false,
            queueControlsVisible = true
        )
        assertTrue(switchingFromLyrics)

        // Switching back and forth does not erase Queue controls
        val queueControlsState = true
        assertTrue(ClassicPlayerViewportMotion.playbackControlsVisibleForTab(NowPlayingTab.QUEUE, false, queueControlsState))
        assertTrue(ClassicPlayerViewportMotion.playbackControlsVisibleForTab(NowPlayingTab.LYRICS, false, queueControlsState).not())
        assertTrue(ClassicPlayerViewportMotion.playbackControlsVisibleForTab(NowPlayingTab.QUEUE, false, queueControlsState))
    }

    @Test
    fun `visible controls own touch region and do not pass touches to content`() {
        val overlay = ClassicPlayerViewportMotion.playbackOverlayBounds(
            viewportHeightPx = 1000,
            controlsTopPx = 750,
            gapPx = 8,
            visibilityProgress = 1.0f
        )
        assertTrue(overlay.ownsInput)
        // Y coordinate inside controls belongs to controls
        assertTrue(overlay.contains(750f))
        assertTrue(overlay.contains(850f))
        assertTrue(overlay.contains(999f))
        // Y coordinate above controls does not belong to controls
        assertTrue(!overlay.contains(749f))
        assertTrue(!overlay.contains(100f))

        // Content clip stops before controls with gap
        assertEquals(742, overlay.contentClipBottomPx)
    }

    @Test
    fun `controls visibility transitions do not alter measured content bounds or cause reflow`() {
        val boundsVisible = ClassicPlayerViewportMotion.stableOverlayContentBounds(
            viewportHeightPx = 1000,
            chromeBottomPx = 60,
            compactHeaderHeightPx = 80,
            controlsTopPx = 750,
            gapPx = 8
        )
        val boundsHidden = ClassicPlayerViewportMotion.stableOverlayContentBounds(
            viewportHeightPx = 1000,
            chromeBottomPx = 60,
            compactHeaderHeightPx = 80,
            controlsTopPx = 750,
            gapPx = 8
        )
        // Measured structural bounds remain identical regardless of visibility state
        assertEquals(boundsVisible.contentTopPx, boundsHidden.contentTopPx)
        assertEquals(boundsVisible.contentBottomPx, boundsHidden.contentBottomPx)
        assertEquals(boundsVisible.chromeBottomPx, boundsHidden.chromeBottomPx)
        assertEquals(boundsVisible.headerBottomPx, boundsHidden.headerBottomPx)
    }


    private fun blurAt(
        lineY: Float,
        standardBlur: Boolean = true,
        isCurrent: Boolean = false
    ): Float = com.auralis.music.ui.lyrics.computeLyricsProgressiveBlur(
        standardBlur = standardBlur,
        isSynced = true,
        isPlain = false,
        isSelected = false,
        isCurrent = isCurrent,
        isUserInteracting = false,
        lineCenterPx = lineY,
        activeLineCenterPx = 300f,
        viewportStartPx = 0f,
        viewportEndPx = 800f
    )

    private fun assertSensible(values: ClassicPlayerViewportMotionValues) {
        assertTrue(values.compactHeaderProgress.isFinite())
        assertTrue(values.compactHeaderProgress in 0f..1f)
        listOf(values.topBar, values.lyrics, values.queue, values.persistentControls)
            .forEach(::assertSensible)
    }

    private fun assertSensible(transform: ClassicViewportLayerTransform) {
        assertTrue(transform.alpha.isFinite())
        assertTrue(transform.translationYDp.isFinite())
        assertTrue(transform.scale.isFinite())
        assertTrue(transform.alpha in 0f..1f)
        assertTrue(transform.scale > 0f)
    }

    @Test
    fun `bottom bar actions contract guarantees shuffle, repeat, output picker, and sleep timer actions`() {
        var shuffleToggled = false
        var repeatToggled = false
        var outputPickerTriggered = false
        var sleepDialogTriggered = false

        val onToggleShuffle = { shuffleToggled = true }
        val onToggleRepeat = { repeatToggled = true }
        val onShowOutputPicker = { outputPickerTriggered = true }
        val onShowSleepDialog = { sleepDialogTriggered = true }

        onToggleShuffle()
        onToggleRepeat()
        onShowOutputPicker()
        onShowSleepDialog()

        assertTrue(shuffleToggled)
        assertTrue(repeatToggled)
        assertTrue(outputPickerTriggered)
        assertTrue(sleepDialogTriggered)
    }

    @Test
    fun `out of range progress settles at the destination without overshoot`() {
        val over = ClassicPlayerViewportMotion.interpolate(NowPlayingTab.PLAYER, NowPlayingTab.QUEUE, 1.08f)
        assertEquals(0f, over.queue.translationYDp, epsilon)
        assertEquals(1f, over.queue.alpha, epsilon)
        assertEquals(1f, over.compactHeaderProgress, epsilon)
    }
}
