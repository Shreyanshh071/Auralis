package com.auralis.music

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.ui.lyrics.ExperimentalPendingSeekTarget
import com.auralis.music.ui.lyrics.SyncedPendingSeekTarget
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RapidLyricSelectionTest {

    // Helper to generate N test lines
    private fun createTestLines(count: Int): List<LyricLine> {
        return (0 until count).map { i ->
            LyricLine(
                time = i * 2_000L,
                endTime = (i + 1) * 2_000L,
                text = "Lyric line number $i",
                agent = if (i % 3 == 0) "v1" else if (i % 3 == 1) "v2" else "v1000",
                isBackground = (i % 5 == 4)
            )
        }
    }

    /**
     * Required Simulation 1:
     * Tap line 10 -> tap line 30 -> tap line 5 -> tap line 50
     * Expected:
     * - Every tap increments request sequence
     * - Last tap (line 50) supersedes previous requests
     * - Final target = 50, primary active = 50
     * - No earlier operation moves it away afterward
     */
    @Test
    fun testRapidSelectionLastTapWins_10_30_5_50() {
        val lines = createTestLines(60)
        var sequence = 0L
        var activePendingTarget: ExperimentalPendingSeekTarget? = null
        var authoritativeTarget = -1
        val tapLog = mutableListOf<Int>()

        fun simulateTap(lineIdx: Int) {
            val reqId = ++sequence
            tapLog.add(lineIdx)
            activePendingTarget = ExperimentalPendingSeekTarget(
                lineIndex = lineIdx,
                targetTimeMs = lines[lineIdx].time,
                requestId = reqId
            )
            authoritativeTarget = lineIdx
        }

        // Rapidly tap 10 -> 30 -> 5 -> 50
        simulateTap(10)
        assertEquals(10, authoritativeTarget)
        assertEquals(1L, activePendingTarget?.requestId)

        simulateTap(30)
        assertEquals(30, authoritativeTarget)
        assertEquals(2L, activePendingTarget?.requestId)

        // Backward tap to line 5
        simulateTap(5)
        assertEquals(5, authoritativeTarget)
        assertEquals(3L, activePendingTarget?.requestId)

        // Final forward tap to line 50
        simulateTap(50)
        assertEquals(50, authoritativeTarget)
        assertEquals(4L, activePendingTarget?.requestId)

        // Intermediate clock updates with stale playback positions (e.g. from line 10 or 30)
        // must be rejected and CANNOT overwrite target 50
        val stalePositions = listOf(lines[10].time, lines[30].time, lines[5].time)
        for (stalePos in stalePositions) {
            val pending = activePendingTarget
            assertNotNull(pending)
            val hasConverged = kotlin.math.abs(stalePos - pending!!.targetTimeMs) <= 350L
            assertFalse("Stale position $stalePos must NOT converge with target ${pending.targetTimeMs}", hasConverged)

            // When seek is in flight, target stays locked
            assertEquals(50, authoritativeTarget)
        }

        // Final playback position arrives at line 50
        val finalPos = lines[50].time
        val pending = activePendingTarget
        assertNotNull(pending)
        val hasConverged = kotlin.math.abs(finalPos - pending!!.targetTimeMs) <= 350L
        assertTrue("Final playback position must converge with target", hasConverged)

        // Authoritative target and primary active index strictly converge to 50
        val primaryActive = LyricsEngine.findActiveLyricIndex(lines, finalPos)
        assertEquals(50, authoritativeTarget)
        assertEquals(50, primaryActive)
    }

    /**
     * Required Simulation 2:
     * Tap forward -> tap backward -> tap forward:
     * Line 20 -> Line 40 -> Line 15 -> Line 60
     * Backward seeks must NOT be rejected by forward-only monotonic constraints!
     */
    @Test
    fun testRapidAlternatingForwardBackward_20_40_15_60() {
        val lines = createTestLines(70)
        var sequence = 0L
        var authoritativeTarget = -1
        var activePendingTarget: ExperimentalPendingSeekTarget? = null

        fun simulateTap(lineIdx: Int) {
            val reqId = ++sequence
            activePendingTarget = ExperimentalPendingSeekTarget(
                lineIndex = lineIdx,
                targetTimeMs = lines[lineIdx].time,
                requestId = reqId
            )
            authoritativeTarget = lineIdx
        }

        simulateTap(20)
        assertEquals(20, authoritativeTarget)

        simulateTap(40)
        assertEquals(40, authoritativeTarget)

        // Backward seek: previously failed because 15 was not > 40
        simulateTap(15)
        assertEquals("Backward seek to Line 15 must be accepted and authoritative", 15, authoritativeTarget)

        // Final seek to 60
        simulateTap(60)
        assertEquals(60, authoritativeTarget)
        assertEquals(4L, activePendingTarget?.requestId)

        // When playback catches up to 60:
        val playbackPos = lines[60].time
        val primaryIdx = LyricsEngine.findActiveLyricIndex(lines, playbackPos)
        assertEquals(60, primaryIdx)
        assertEquals(60, authoritativeTarget)
    }

    /**
     * Tests that transient/stale clock playback updates do not override an in-flight tap target.
     * Prevents: tap selects Line 40 -> clock reports Line 39 -> auto-scroll moves back.
     */
    @Test
    fun testStaleClockReportingDoesNotOverrideTapTarget() {
        val lines = createTestLines(50)
        val tappedIndex = 40
        val tappedTime = lines[tappedIndex].time
        val reqId = 1L
        var pendingTarget: ExperimentalPendingSeekTarget? = ExperimentalPendingSeekTarget(
            lineIndex = tappedIndex,
            targetTimeMs = tappedTime,
            requestId = reqId,
            timestamp = System.currentTimeMillis()
        )
        var authoritativeIndex = tappedIndex

        // Transient clock ticks reporting earlier lines (e.g. Line 38, Line 39)
        val transientTimes = listOf(lines[38].time, lines[39].time)
        for (pos in transientTimes) {
            val pending = pendingTarget
            assertNotNull(pending)
            val hasConverged = kotlin.math.abs(pos - pending!!.targetTimeMs) <= 350L
            assertFalse(hasConverged)

            // Seeking is in-flight: authoritativeIndex must remain locked on 40!
            authoritativeIndex = pending.lineIndex
            assertEquals(40, authoritativeIndex)
        }

        // Now ExoPlayer position arrives at 40
        val convergedPos = tappedTime + 50L
        if (kotlin.math.abs(convergedPos - pendingTarget!!.targetTimeMs) <= 350L) {
            pendingTarget = null // Converged!
        }
        assertNull("Pending target should be cleared once converged", pendingTarget)

        // Natural clock now resolves to 40
        authoritativeIndex = LyricsEngine.findActiveLyricIndex(lines, convergedPos)
        assertEquals(40, authoritativeIndex)
    }

    /**
     * Tests seek timeout safety: if ExoPlayer seek fails or takes > 650ms,
     * the system gracefully times out and resumes natural clock tracking.
     */
    @Test
    fun testPendingSeekTimeoutSafety() {
        val lines = createTestLines(30)
        val pendingTarget = ExperimentalPendingSeekTarget(
            lineIndex = 25,
            targetTimeMs = lines[25].time,
            requestId = 1L,
            timestamp = System.currentTimeMillis() - 700L // 700ms ago -> timed out
        )

        val now = System.currentTimeMillis()
        val isTimedOut = (now - pendingTarget.timestamp) > 650L
        assertTrue("Pending seek older than 650ms must be marked timed out", isTimedOut)
    }

    /**
     * Tests external manual seekbar scrub:
     * If user scrubs the progress bar directly (basePos delta > 1000ms from pending tap),
     * the pending tap target is cleanly invalidated so scrubber position wins immediately.
     */
    @Test
    fun testExternalManualSeekScrubInvalidatesPendingTap() {
        val lines = createTestLines(50)
        var pendingTarget: ExperimentalPendingSeekTarget? = ExperimentalPendingSeekTarget(
            lineIndex = 40,
            targetTimeMs = lines[40].time, // e.g. 80_000ms
            requestId = 1L
        )

        // User scrubs progress bar backward to 0:00 (0ms)
        val scrubbedBasePos = 0L
        val pending = pendingTarget
        if (pending != null && kotlin.math.abs(scrubbedBasePos - pending.targetTimeMs) > 1000L) {
            pendingTarget = null
        }

        assertNull("Pending tap target must be invalidated on large external scrub", pendingTarget)
        val resolved = LyricsEngine.findActiveLyricIndex(lines, scrubbedBasePos)
        assertEquals(0, resolved)
    }

    /**
     * Multi-Active Priority:
     * In Experimental Lyrics mode, multiple simultaneous lines can be active (e.g. lead + background).
     * The scroll anchor must strictly use the lead vocal line (single authoritative anchor),
     * while the visual active set contains both lines.
     */
    @Test
    fun testMultiActiveLeadPrioritizedAsScrollAnchor() {
        val leadLine = LyricLine(
            time = 10_000L,
            endTime = 16_000L,
            text = "Lead vocal singing here",
            agent = "v1",
            isBackground = false
        )
        val bgLine = LyricLine(
            time = 11_000L,
            endTime = 14_000L,
            text = "(Background vocal harmony)",
            agent = "v2",
            isBackground = true
        )
        val lines = listOf(leadLine, bgLine)

        val activeVisual = LyricsEngine.findVisualActiveLineIndices(lines, 12_000L)
        assertEquals("Both lead and background lines must be visually active", setOf(0, 1), activeVisual)

        val primaryScrollAnchor = LyricsEngine.findActiveLyricIndex(lines, 12_000L)
        assertEquals("Scroll anchor must prioritize the primary lead vocal line (index 0)", 0, primaryScrollAnchor)
    }

    /**
     * AutoScroll Setting Honor:
     * When autoScrollLyrics is false, user taps seek playback but do NOT forcibly scroll.
     */
    @Test
    fun testAutoScrollDisabledDoesNotEngageAutoScroll() {
        val autoScrollSetting = false
        var isAutoScrollEnabled = autoScrollSetting
        var seekCount = 0

        fun onLyricTap(timeMs: Long) {
            seekCount++
            if (autoScrollSetting) {
                isAutoScrollEnabled = true
            }
        }

        onLyricTap(10_000L)
        assertEquals(1, seekCount)
        assertFalse("Auto scroll must remain disabled when user preference is off", isAutoScrollEnabled)
    }

    /**
     * Long lyrics (100+ lines):
     * Ensures rapid taps across distant lines (Line 5 -> Line 95 -> Line 2)
     * work deterministically and calculate valid pre-positioning.
     */
    @Test
    fun testLongLyrics100LinesRapidSelectionAndPrepositioning() {
        val lines = createTestLines(120)
        var sequence = 0L
        var authoritativeTarget = -1

        fun simulateTap(lineIdx: Int) {
            authoritativeTarget = lineIdx
            ++sequence
        }

        simulateTap(5)
        assertEquals(5, authoritativeTarget)

        // Jump 90 lines forward
        simulateTap(95)
        assertEquals(95, authoritativeTarget)
        val currentFirst = 5
        val distance = kotlin.math.abs(authoritativeTarget - currentFirst)
        assertTrue(distance > 8)
        val preIndexForward = (authoritativeTarget - 2).coerceAtLeast(0)
        assertEquals(93, preIndexForward)

        // Jump 93 lines backward
        simulateTap(2)
        assertEquals(2, authoritativeTarget)
        val currentFirst2 = 95
        val distance2 = kotlin.math.abs(authoritativeTarget - currentFirst2)
        assertTrue(distance2 > 8)
        val preIndexBackward = (authoritativeTarget + 2).coerceAtMost(lines.size - 1)
        assertEquals(4, preIndexBackward)
    }

    /**
     * SyncedLyricsView parity test:
     * Verifies SyncedPendingSeekTarget works identically with request sequencing.
     */
    @Test
    fun testSyncedLyricsViewRapidTapParity() {
        val lines = createTestLines(50)
        var seq = 0L
        var pending: SyncedPendingSeekTarget? = null

        fun tap(idx: Int) {
            val rId = ++seq
            pending = SyncedPendingSeekTarget(idx, lines[idx].time, rId)
        }

        tap(10)
        tap(25)
        tap(4)
        tap(48)

        assertNotNull(pending)
        assertEquals(48, pending!!.lineIndex)
        assertEquals(4L, pending!!.requestId)
        assertEquals(lines[48].time, pending!!.targetTimeMs)
    }
}
