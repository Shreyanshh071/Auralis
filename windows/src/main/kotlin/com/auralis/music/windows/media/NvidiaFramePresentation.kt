package com.auralis.music.windows.media

/** NVIDIA's documented VSYNCMODE values. Applied to the Auralis.exe profile only. */
internal object NvidiaFramePresentation {
    const val SETTING = 0x00A879CF
    const val FORCE_OFF = 0x08416747
    const val APPLICATION_CONTROLLED = 0x60925292
    // Respect other driver modes. Repair only the override that defeats Skiko's synced presents.
    fun repair(value: Int?): Int? = if (value == FORCE_OFF) APPLICATION_CONTROLLED else null
}
