package com.auralis.music

import com.auralis.music.ui.lyrics.LyricsGlideMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LyricsGlideMotionTest {
    private val frame = 1f / 60f

    /** Positions over [frames] frames while gliding [distance] px. */
    private fun glide(distance: Float, frames: Int): List<Float> {
        val motion = LyricsGlideMotion()
        var position = 0f
        return List(frames) {
            position += motion.advance(distance - position, frame)
            position
        }
    }

    @Test fun `a three line jump starts gently instead of lurching`() {
        val path = glide(400f, 90)
        assertTrue("Moved ${path[5]}px in 100ms", path[5] < 400f * .08f)
        assertTrue("Moved ${path[11]}px in 200ms", path[11] < 400f * .30f)
    }

    @Test fun `the fastest moment is mid glide, not at the start`() {
        val path = glide(400f, 90)
        val steps = path.zipWithNext { a, b -> b - a }
        val peak = steps.indices.maxByOrNull { steps[it] }!!
        assertTrue("Peak speed at frame $peak", peak in 8..24)
    }

    @Test fun `it reaches the line quickly without overshooting`() {
        val path = glide(400f, 150)
        assertTrue("At ${path[44]}px after 0.75s", path[44] > 400f * .9f)
        assertTrue(path.all { it <= 400.5f })
        assertEquals(400f, path.last(), 2f)
    }

    @Test fun `a new target mid glide bends the motion without a speed jump`() {
        val motion = LyricsGlideMotion()
        var position = 0f
        var goal = 120f
        var previousSpeed = 0f
        repeat(120) { index ->
            if (index == 20) goal = 420f
            position += motion.advance(goal - position, frame)
            assertTrue("Speed jumped at frame $index", abs(motion.velocity - previousSpeed) < 90f)
            previousSpeed = motion.velocity
        }
    }

    @Test fun `upward moves mirror downward ones`() {
        assertEquals(glide(300f, 40)[39], -glide(-300f, 40)[39], .01f)
    }
}
