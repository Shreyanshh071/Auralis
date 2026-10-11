package com.auralis.music.data.network

/**
 * YouTube answers a network that sent too many requests with a 403 "Sorry..." page (its
 * unusual-traffic block) for minutes to hours. Requests sent meanwhile only prolong it, so
 * after one, YouTube searches and stream lookups pause for [PAUSE_MS] and fail at once.
 */
object YouTubeBackoff {
    private const val PAUSE_MS = 5L * 60_000L
    @Volatile private var pausedUntil = 0L

    val active: Boolean get() = System.currentTimeMillis() < pausedUntil

    /** Whether [body] is YouTube's unusual-traffic block page. */
    fun isBlockPage(body: String): Boolean =
        body.contains("<title>Sorry...</title>") || body.contains("Got HTML document, expected JSON")

    fun trip() {
        if (!active) com.auralis.music.util.Log.w("YouTubeBackoff", "YouTube is blocking this network; pausing its requests for ${PAUSE_MS / 60_000} min")
        pausedUntil = System.currentTimeMillis() + PAUSE_MS
    }
}
