package com.auralis.music.data.sync

/** An account clear must be acknowledged before removing this device's local copy. */
object ListeningStatsClear {
    suspend fun run(allDevices: Boolean, clearAccount: suspend () -> Unit, clearLocal: suspend () -> Unit) {
        if (allDevices) clearAccount()
        clearLocal()
    }
}
