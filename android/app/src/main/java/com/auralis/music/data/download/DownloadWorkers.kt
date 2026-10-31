package com.auralis.music.data.download

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** Fixed workers keep large playlists bounded without waiting for a slow song between batches. */
internal suspend fun <T> forEachDownload(
    items: List<T>,
    parallelism: Int = 2,
    download: suspend (T) -> Unit
) = coroutineScope {
    require(parallelism > 0)
    val next = AtomicInteger()
    repeat(minOf(parallelism, items.size)) {
        launch {
            try {
                while (true) {
                    ensureActive()
                    val index = next.getAndIncrement()
                    if (index >= items.size) break
                    download(items[index])
                }
            } catch (e: CancellationException) {
                // A user cancellation detected by one worker must stop the entire playlist.
                this@coroutineScope.cancel(e)
                throw e
            }
        }
    }
}
