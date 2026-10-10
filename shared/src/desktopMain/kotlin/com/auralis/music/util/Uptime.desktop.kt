package com.auralis.music.util

actual fun uptimeMillis(): Long = System.nanoTime() / 1_000_000L
