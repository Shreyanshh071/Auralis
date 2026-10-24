package com.auralis.music.data.connect

/** An ended remote session may be dismissed locally without deleting the account's session. */
internal class ConnectRemotePresentation {
    private var dismissed: Pair<String, String>? = null

    fun present(remote: ConnectRemoteView?): ConnectRemoteView? {
        if (remote == null || remote.output.active) dismissed = null
        return remote?.takeUnless { !it.output.active && key(it) == dismissed }
    }

    fun dismiss(remote: ConnectRemoteView?): Boolean {
        if (remote == null || remote.output.active) return false
        dismissed = key(remote)
        return true
    }

    private fun key(remote: ConnectRemoteView) = remote.output.deviceId to remote.track.id
}
