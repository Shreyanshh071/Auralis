package com.auralis.music.util

/** Small persistent string settings. Android backs it with SharedPreferences. */
interface KeyValueStore {
    fun getString(key: String, default: String): String?

    /** Writes all [values] in one edit, without blocking the caller. */
    fun putStrings(values: Map<String, String>)

    fun clear()
}
