package com.auralis.music.desktop.playback

/** Reject queued timestamps from before a seek, without blocking the UI or the VLC event thread. */
class PlaybackSeekGate {
    data class Request(val revision: Long, val targetMs: Long, val atNanos: Long)
    private var revision = 0L
    private var pending: Request? = null

    @Synchronized fun request(targetMs: Long, nowNanos: Long): Request =
        Request(++revision, targetMs.coerceAtLeast(0L), nowNanos).also { pending = it }

    @Synchronized fun isCurrent(request: Request): Boolean = revision == request.revision
    @Synchronized fun isPending(): Boolean = pending != null
    @Synchronized fun reset() { revision++; pending = null }

    @Synchronized fun accept(positionMs: Long, nowNanos: Long, speed: Float): Boolean {
        val request = pending ?: return true
        val elapsedMs = ((nowNanos - request.atNanos) / 1_000_000L).coerceAtLeast(0L)
        // The first decoded report can already be several hundred ms beyond the target.
        // After six seconds accept reality as recovery from a failed/rejected native seek.
        val reached = positionMs >= request.targetMs - 350L &&
            positionMs <= request.targetMs + 350L + (elapsedMs * speed.coerceIn(.5f, 2f)).toLong()
        if (!reached && elapsedMs < 6000L) return false
        pending = null
        return true
    }
}
