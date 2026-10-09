package com.auralis.music.ui.components

import android.view.MotionEvent

/** Blocks startup touches, including the rest of a gesture held past the splash fade. */
internal class LaunchTouchGate {
    private var splashVisible = true
    private var swallowingGesture = false

    fun splashRemoved() {
        splashVisible = false
    }

    fun shouldConsume(action: Int): Boolean {
        // A new DOWN begins a fresh gesture even if the old stream was interrupted.
        if (action == MotionEvent.ACTION_DOWN) swallowingGesture = splashVisible
        val consume = splashVisible || swallowingGesture
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            swallowingGesture = false
        }
        return consume
    }
}
