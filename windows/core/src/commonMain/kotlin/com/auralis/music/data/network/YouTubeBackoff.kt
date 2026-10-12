package com.auralis.music.data.network

/**
 * YouTube answers a network that sent too many requests with a 403 "Sorry..." page (its
 * unusual-traffic block) for minutes to hours. Requests sent meanwhile only prolong it, so
 * after one, YouTube searches and stream lookups pause for [PAUSE_MS] and fail at once.
 */
object YouTubeBackoff {
    private const val PAUSE_MS = 5L * 60_000L
    @Volatile private var pausedUntil = 0L
    /** How many times YouTube has blocked us; a change during a task means its answers were cut short. */
    @Volatile var trips = 0
        private set

    val active: Boolean get() = System.currentTimeMillis() < pausedUntil

    /** Whether [body] is YouTube's unusual-traffic block page. */
    fun isBlockPage(body: String): Boolean =
        body.contains("<title>Sorry...</title>") || body.contains("Got HTML document, expected JSON")

    fun trip() {
        if (!active) com.auralis.music.util.Log.w("YouTubeBackoff", "YouTube is blocking this network; pausing its requests for ${PAUSE_MS / 60_000} min")
        pausedUntil = System.currentTimeMillis() + PAUSE_MS
        trips++
    }

    /** Suspends until any block pause is over. */
    suspend fun awaitClear() {
        while (active) kotlinx.coroutines.delay((pausedUntil - System.currentTimeMillis()).coerceIn(1_000L, 15_000L))
    }
}

/**
 * Paces background YouTube searches (playlist matching, Home's album and artist lookups) one at a
 * time, [SPACING_MS] apart, and holds them while YouTube is blocking us. A burst of them at
 * launch (a 250-song match plus Home's lookups) is what got the network blocked, and the block
 * then stopped everything. Searches the listener types are not queued here.
 */
object YouTubeBackgroundGate {
    /** Gap after one search request; a full search (about six requests) asks for more. */
    const val SPACING_MS = 800L
    private val mutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var nextAt = 0L

    suspend fun <T> run(spacingMs: Long = SPACING_MS, block: suspend () -> T): T {
        YouTubeBackoff.awaitClear()
        mutex.lock()
        try {
            YouTubeBackoff.awaitClear()
            val wait = nextAt - System.currentTimeMillis()
            if (wait > 0) kotlinx.coroutines.delay(wait)
            return block()
        } finally {
            nextAt = System.currentTimeMillis() + spacingMs
            mutex.unlock()
        }
    }
}
