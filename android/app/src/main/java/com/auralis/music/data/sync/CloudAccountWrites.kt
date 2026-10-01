package com.auralis.music.data.sync

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes uploads with deletion. Pending deletion survives app/process restarts. */
class AccountWriteBarrier(
    private val blocked: (String) -> Boolean,
    private val persistBlock: (String) -> Unit
) {
    private val mutex = Mutex()

    suspend fun <T> write(uid: String, work: suspend () -> T): T? = mutex.withLock {
        if (blocked(uid)) null else work()
    }

    suspend fun pauseAndDrain(uid: String) {
        persistBlock(uid)
        mutex.withLock { /* Every upload that already started has finished. */ }
    }
}

object CloudAccountWrites {
    @Volatile private var prefs: SharedPreferences? = null
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("auralis_account_deletion", Context.MODE_PRIVATE)
    }
    private fun storage() = checkNotNull(prefs) { "CloudAccountWrites not initialized" }
    fun isBlocked(uid: String): Boolean = storage().getBoolean("pending_$uid", false)
    val barrier = AccountWriteBarrier(::isBlocked) { uid ->
        check(storage().edit().putBoolean("pending_$uid", true).commit()) { "Could not save deletion progress" }
    }
}
