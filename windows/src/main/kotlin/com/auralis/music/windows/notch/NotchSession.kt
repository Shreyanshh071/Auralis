package com.auralis.music.windows.notch

import com.auralis.music.desktop.playback.VlcAudioPlayer

/** First local playback activates the notch for this app lifetime. */
internal class NotchSession {
    private var engaged = false

    fun update(
        enabled: Boolean,
        locked: Boolean,
        hasTrack: Boolean,
        localPlayback: Boolean,
        state: VlcAudioPlayer.State
    ): Boolean {
        // Restored queues and initial resolution/buffering must not expose the notch.
        if (hasTrack && localPlayback && state == VlcAudioPlayer.State.PLAYING) engaged = true
        // Lock, settings and remote playback temporarily suppress presentation only.
        return engaged && enabled && !locked && hasTrack && localPlayback
    }
}

internal data class NotchPeer(val pid: Int, val hidden: Boolean, val visibleWindows: Boolean = false) {
    val concealed: Boolean get() = hidden && !visibleWindows
}

/** Own only the visibility changes we made; never reopen an app the user quit. */
internal class NotchHandoff {
    private val owned = mutableSetOf<Int>()
    private val pending = mutableMapOf<Int, Long>()
    private val refused = mutableSetOf<Int>()

    fun update(
        wanted: Boolean,
        peers: List<NotchPeer>,
        nowMs: Long,
        hide: (Int) -> Boolean,
        restore: (Int) -> Unit
    ): Boolean {
        val alive = peers.mapTo(mutableSetOf()) { it.pid }
        owned.retainAll(alive)
        pending.keys.retainAll(alive)
        refused.retainAll(alive)
        // A successful request is asynchronous. Confirm the app actually became hidden.
        peers.filter { it.concealed && it.pid in pending }.forEach {
            pending.remove(it.pid)
            owned.add(it.pid)
        }
        if (!wanted) {
            // Also undo a hide that is still being delivered, before dropping its lease.
            (owned + pending.keys).forEach(restore)
            owned.clear()
            pending.clear()
            return false
        }
        if (peers.any { !it.concealed && it.pid in refused }) {
            release(restore)
            return false
        }
        var clear = true
        peers.forEach { app ->
            if (!app.concealed) {
                clear = false
                // If the user reveals it during our session, do not keep hiding it.
                if (owned.remove(app.pid)) refused.add(app.pid)
                val requested = pending[app.pid]
                if (requested != null) {
                    if (nowMs - requested >= 1_000L) {
                        // A delayed request may still arrive; restore before abandoning it.
                        restore(app.pid)
                        pending.remove(app.pid)
                        refused.add(app.pid)
                    }
                } else if (app.pid !in refused) {
                    if (hide(app.pid)) pending[app.pid] = nowMs else refused.add(app.pid)
                }
            }
        }
        if (peers.any { !it.concealed && it.pid in refused }) release(restore)
        return clear
    }

    fun release(restore: (Int) -> Unit) {
        (owned + pending.keys).forEach(restore)
        owned.clear()
        pending.clear()
    }
}

internal data class NotchDisplay(
    val x: Double, val y: Double, val width: Double, val height: Double,
    val primaryTop: Double, val cutoutWidth: Double, val topInset: Double
)
