package com.auralis.music.data.connect

/** Activity visibility and task lifetime are different: Home does not close a task. */
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

    fun service(running: Boolean) { serviceRunning = running }

    fun removeTask() {
        taskPresent = false
        visible = false
    }
}
