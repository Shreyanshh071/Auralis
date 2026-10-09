package com.auralis.music

import android.view.MotionEvent.*
import com.auralis.music.ui.components.LaunchTouchGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchTouchGateTest {
    @Test fun startupTapNeverReachesPlayback() {
        val gate = LaunchTouchGate()
        assertTrue(gate.shouldConsume(ACTION_DOWN))
        assertTrue(gate.shouldConsume(ACTION_UP))
        gate.splashRemoved()
        assertFalse(gate.shouldConsume(ACTION_DOWN))
        assertFalse(gate.shouldConsume(ACTION_UP))
    }

    @Test fun heldMultiTouchGestureIsConsumedUntilReleaseAfterFade() {
        val gate = LaunchTouchGate()
        assertTrue(gate.shouldConsume(ACTION_DOWN))
        gate.splashRemoved()
        assertTrue(gate.shouldConsume(ACTION_POINTER_DOWN))
        assertTrue(gate.shouldConsume(ACTION_MOVE))
        assertTrue(gate.shouldConsume(ACTION_POINTER_UP))
        assertTrue(gate.shouldConsume(ACTION_UP))
        assertFalse(gate.shouldConsume(ACTION_DOWN))
    }

    @Test fun cancelledGestureDoesNotBlockHome() {
        val gate = LaunchTouchGate()
        assertTrue(gate.shouldConsume(ACTION_DOWN))
        gate.splashRemoved()
        assertTrue(gate.shouldConsume(ACTION_CANCEL))
        assertFalse(gate.shouldConsume(ACTION_DOWN))
    }

    @Test fun interruptedStreamDoesNotSwallowANewHomeTap() {
        val gate = LaunchTouchGate()
        assertTrue(gate.shouldConsume(ACTION_DOWN))
        gate.splashRemoved()
        assertFalse(gate.shouldConsume(ACTION_DOWN))
        assertFalse(gate.shouldConsume(ACTION_UP))
    }
}
