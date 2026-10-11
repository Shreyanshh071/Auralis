package com.auralis.music.util

/** Milliseconds since boot (Android's SystemClock.uptimeMillis); a monotonic clock on desktop. */
expect fun uptimeMillis(): Long
