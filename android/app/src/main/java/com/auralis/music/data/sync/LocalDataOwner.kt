package com.auralis.music.data.sync

import android.content.Context

/**
 * Which account the library and listening data on this phone belong to.
 *
 * Signing in to a second account used to merge its cloud library into the first account's local
 * one and then back the mix up to the second account (stats too), so accounts leaked into each
 * other. Every upload now checks the owner, and a different account signing in first clears the
 * previous owner's data from the phone (it stays in that account's cloud backup).
 *
 * Blank means the data was made while signed out; the first account to sign in adopts it.
 */
object LocalDataOwner {
    private const val PREFS = "auralis_local_owner"
    private const val KEY_UID = "owner_uid"

    @Volatile private var cached: String? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val uid: String
        get() = cached ?: prefs()?.getString(KEY_UID, "").orEmpty().also { cached = it }

    fun set(uid: String) {
        cached = uid
        prefs()?.edit()?.putString(KEY_UID, uid)?.apply()
    }

    /** True when this phone's data may be uploaded to (or merged with) [accountUid]'s cloud. */
    fun belongsTo(accountUid: String): Boolean = uid.isBlank() || uid == accountUid
}
