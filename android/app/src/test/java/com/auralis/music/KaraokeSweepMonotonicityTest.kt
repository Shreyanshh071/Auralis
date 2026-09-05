package com.auralis.music

import com.auralis.music.domain.model.LyricWord
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import org.junit.Assert.*
import org.junit.Test

/**
 * Diagnostic test: verifies the karaoke sweep highlight visibility is monotonically
 * increasing as word progress advances from 0.0 to 1.0, and that no frame produces
 * an invisible (fully transparent) or non-monotonic mask.
 *
 * Also verifies that overlapping word durations (as produced by synthetic word pacing)
 * never cause a word to disappear from both finishedPath and activeSweep.
 */
class KaraokeSweepMonotonicityTest {

    // ── 1. Gradient mask monotonicity ────────────────────────────────────────

    /**
     * Simulates the LTR feathered gradient mask from SyncedLyricsView and checks
     * that the visible fraction of the word is monotonically non-decreasing.
     */
    @Test
    fun `gradient mask produces monotonically increasing visible fraction`() {
        val boundsLeft = 50f
        val boundsWidth = 200f
        val edgeWidth = 16.5f  // ~6dp * 2.75 density
        val progressSteps = listOf(0.0f, 0.01f, 0.05f, 0.1f, 0.25f, 0.5f, 0.75f, 0.9f, 0.95f, 0.99f, 1.0f)

        var prevVisible = -1f

        for (progress in progressSteps) {
            // Replicate the center calculation from SyncedLyricsView (LTR case)
            val center = boundsLeft + (boundsWidth + edgeWidth * 2) * progress - edgeWidth
            val startX = center - edgeWidth  // opaque boundary (Black)
            val endX = center + edgeWidth    // transparent boundary (Transparent)
            val boundsRight = boundsLeft + boundsWidth

            // Opaque fraction: area of [boundsLeft, boundsRight] where x < startX
            val opaqueRight = startX.coerceAtMost(boundsRight)
            val opaqueFrac = if (opaqueRight <= boundsLeft) 0f
            else (opaqueRight - boundsLeft) / boundsWidth

            // Gradient fraction: area where startX <= x <= endX intersected with bounds
            val gradLeft = startX.coerceIn(boundsLeft, boundsRight)
            val gradRight = endX.coerceIn(boundsLeft, boundsRight)
            val gradFrac = if (gradRight > gradLeft) (gradRight - gradLeft) / boundsWidth else 0f

            // Approximate visible fraction (opaque + half the gradient)
            val visibleFrac = opaqueFrac + gradFrac * 0.5f

            println("progress=$progress center=$center gradient=[$startX, $endX] " +
                    "opaque=$opaqueFrac grad=$gradFrac ~visible=$visibleFrac")

            assertTrue(
                "Visible fraction must be monotonically non-decreasing: " +
                        "progress=$progress visible=$visibleFrac prev=$prevVisible",
                visibleFrac >= prevVisible - 0.001f
            )
            prevVisible = visibleFrac
        }
    }

    /**
     * Same test for short words (e.g., "I" or "a" — only 20px wide).
     */
    @Test
    fun `gradient mask is monotonic for short words`() {
        val boundsLeft = 50f
        val boundsWidth = 20f
        val edgeWidth = (boundsWidth * 0.45f).coerceAtLeast(4f)  // clamped
        val progressSteps = listOf(0.0f, 0.1f, 0.25f, 0.5f, 0.75f, 0.9f, 1.0f)

        var prevVisible = -1f

        for (progress in progressSteps) {
            val center = boundsLeft + (boundsWidth + edgeWidth * 2) * progress - edgeWidth
            val startX = center - edgeWidth
            val endX = center + edgeWidth
            val boundsRight = boundsLeft + boundsWidth

            val opaqueRight = startX.coerceAtMost(boundsRight)
            val opaqueFrac = if (opaqueRight <= boundsLeft) 0f
            else (opaqueRight - boundsLeft) / boundsWidth

            val gradLeft = startX.coerceIn(boundsLeft, boundsRight)
            val gradRight = endX.coerceIn(boundsLeft, boundsRight)
            val gradFrac = if (gradRight > gradLeft) (gradRight - gradLeft) / boundsWidth else 0f

            val visibleFrac = opaqueFrac + gradFrac * 0.5f

            assertTrue(
                "Short word visible fraction must be monotonic: " +
                        "progress=$progress visible=$visibleFrac prev=$prevVisible",
                visibleFrac >= prevVisible - 0.001f
            )
            prevVisible = visibleFrac
        }
    }

    // ── 2. Synthetic word overlap detection ──────────────────────────────────

    /**
     * Reproduces the synthetic word pacing from LyricLineRow and verifies that
     * overlapping durations (wordDur = stepMs * 1.25) cause frame-drops in
     * updateWordHighlightState by having two words simultaneously active.
     *
     * This demonstrates the root cause of the blinking bug.
     */
    @Test
    fun `synthetic word pacing produces overlapping durations`() {
        // Simulate the synthetic word generation from LyricLineRow
        val lineText = "When you were here before"
        val tokens = lineText.split(Regex("\\s+")).filter { it.isNotBlank() }
        val lineTime = 19764L
        val nextLineTime = 24594L

        val lineDur = (nextLineTime - lineTime).coerceIn(1000L, 6500L)
        val singDur = (lineDur * 0.78f).toLong().coerceAtLeast(600L)
        val stepMs = singDur / tokens.size.coerceAtLeast(1)
        val wordDur = (stepMs * 1.25f).toLong().coerceIn(180L, 800L)

        println("stepMs=$stepMs, wordDur=$wordDur, overlap=${wordDur - stepMs}ms per pair")

        // Generate words
        val words = tokens.mapIndexed { idx, token ->
            LyricWord(
                word = if (idx < tokens.size - 1) "$token " else token,
                time = lineTime + (idx * stepMs),
                duration = wordDur
            )
        }

        // Verify overlap exists
        var overlapCount = 0
        for (i in 0 until words.size - 1) {
            val currEnd = words[i].time + (words[i].duration ?: 0)
            val nextStart = words[i + 1].time
            if (currEnd > nextStart) {
                overlapCount++
                println("OVERLAP: word[$i] \"${words[i].word.trim()}\" ends at ${currEnd}ms, " +
                        "word[${i + 1}] \"${words[i + 1].word.trim()}\" starts at ${nextStart}ms " +
                        "(overlap=${currEnd - nextStart}ms)")
            }
        }

        assertTrue(
            "Synthetic word pacing with wordDur=stepMs*1.25 must produce overlaps",
            overlapCount > 0
        )

        // Verify the dropped-frame scenario: find a timestamp where two words are both active
        var droppedFrameFound = false
        for (i in 0 until words.size - 1) {
            val currEnd = words[i].time + (words[i].duration ?: 0)
            val nextStart = words[i + 1].time
            if (currEnd > nextStart) {
                // Check a timestamp in the overlap window
                val midOverlap = (nextStart + currEnd) / 2
                val p0 = LyricsEngine.calculateWordProgress(words[i], midOverlap, 0L)
                val p1 = LyricsEngine.calculateWordProgress(words[i + 1], midOverlap, 0L)

                println("At t=${midOverlap}ms: word[$i] progress=$p0, word[${i + 1}] progress=$p1")

                if (p0 > 0f && p0 < 1f && p1 > 0f && p1 < 1f) {
                    droppedFrameFound = true
                    println("*** BOTH ACTIVE: word[$i] would be dropped by activeSweep overwrite ***")
                }
            }
        }

        assertTrue(
            "Must find at least one frame where two synthetic words are both active (0 < p < 1), " +
                    "demonstrating the activeSweep overwrite bug",
            droppedFrameFound
        )
    }

    // ── 3. TTML words do NOT overlap ─────────────────────────────────────────

    /**
     * Verifies that genuine TTML word timing (after syllable merge) produces
     * non-overlapping word durations, confirming the bug is specific to
     * synthetic word pacing.
     */
    @Test
    fun `TTML Creep words have no overlapping durations`() {
        val creepFile = java.io.File("c:/Users/shrey/OneDrive/Desktop/Auralis/creep_raw.ttml")
        if (!creepFile.exists()) {
            println("creep_raw.ttml not found, skipping")
            return
        }

        val lyrics = com.auralis.music.data.parser.TtmlParser.parse(
            creepFile.readText(),
            com.auralis.music.domain.model.LyricsProvider.BETTER_LYRICS
        )

        var totalOverlaps = 0
        for (line in lyrics.lines) {
            val words = line.words ?: continue
            for (i in 0 until words.size - 1) {
                val currEnd = words[i].time + (words[i].duration ?: continue)
                val nextStart = words[i + 1].time
                if (currEnd > nextStart) {
                    totalOverlaps++
                    println("TTML OVERLAP in \"${line.text}\": " +
                            "\"${words[i].word}\" ends at $currEnd, " +
                            "\"${words[i + 1].word}\" starts at $nextStart")
                }
            }
        }

        assertEquals("TTML words after syllable merge must not overlap", 0, totalOverlaps)
    }
}
