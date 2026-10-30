package com.auralis.music.ui.library

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** Playlists pinned to the Library's collection shelf, oldest pin first. Kept on this device. */
internal object LibraryPinStore {
    private const val PREFS = "library_pins"
    private const val KEY = "pinned_ids"
    private const val SEPARATOR = "\n"

    private var loaded = false
    val pinned = mutableStateOf<List<String>>(emptyList())

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        pinned.value = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null).orEmpty().split(SEPARATOR).filter { it.isNotBlank() }
    }

    fun toggle(context: Context, playlistId: String) {
        load(context)
        val updated = if (playlistId in pinned.value) pinned.value - playlistId else pinned.value + playlistId
        pinned.value = updated
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, updated.joinToString(SEPARATOR)).apply()
    }
}
