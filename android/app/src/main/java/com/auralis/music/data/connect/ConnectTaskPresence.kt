package com.auralis.music.data.connect

/** Connect is available through the visible activity or active media playback, never idle keepalive. */
internal class ConnectTaskPresence {
    var taskPresent = false
        private set
    var visible = false
        private set
    var serviceRunning = false
        private set

    val available: Boolean get() = taskPresent && (visible || serviceRunning)

    fun foreground(visible: Boolean) {
        this.visible = visible
        if (visible) taskPresent = true
    }

    /** Whether playback currently needs the media service's foreground lifetime. */
    fun service(running: Boolean) { serviceRunning = running }

    fun removeTask() {
        taskPresent = false
        visible = false
    }
}
