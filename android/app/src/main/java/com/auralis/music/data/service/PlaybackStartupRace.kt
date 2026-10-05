package com.auralis.music.data.service

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

/** Accept a prepared stream only until the web engine starts audio. */
internal suspend fun awaitNativeDuringWebStartup(
    nativeStream: Deferred<String?>,
    webPlaying: StateFlow<Boolean>
): String? = coroutineScope {
    val webStarted = async(start = CoroutineStart.UNDISPATCHED) { webPlaying.first { it } }
    try {
        select {
            webStarted.onAwait { null }
            nativeStream.onAwait { url -> url?.takeIf { !webPlaying.value } }
        }
    } finally {
        webStarted.cancel()
    }
}
