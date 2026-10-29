package com.auralis.music.ui.viewmodel

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Keep optional feed/maintenance work out of the first frames and active Home scrolling. */
internal class HomeBackgroundWork(private val enabled: Boolean) {
    private val scrolling = MutableStateFlow(false)
    fun onScrollChanged(active: Boolean) { scrolling.value = active }

    suspend fun awaitIdle() {
        if (!enabled) return
        do {
            scrolling.first { !it }
            delay(600)
        } while (scrolling.value)
    }

    suspend fun awaitStartup() {
        if (!enabled) return
        delay(2_500)
        awaitIdle()
    }
}
