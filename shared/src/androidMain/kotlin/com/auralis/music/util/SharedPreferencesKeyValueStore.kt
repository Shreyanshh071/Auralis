package com.auralis.music.util

import android.content.SharedPreferences

class SharedPreferencesKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String, default: String): String? = prefs.getString(key, default)

    override fun putStrings(values: Map<String, String>) {
        val editor = prefs.edit()
        for ((key, value) in values) editor.putString(key, value)
        editor.apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }
}
