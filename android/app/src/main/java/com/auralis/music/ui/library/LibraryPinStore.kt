package com.auralis.music.ui.library

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Playlists pinned to the Library's collection shelf, oldest pin first. Synced to the account. */
internal object LibraryPinStore {
    private const val PREFS = "library_pins"
    private const val KEY = "pinned_ids"
    private const val KEY_AT = "pinned_at"
    private const val SEPARATOR = "\n"

    private var loaded = false
    val pinned = mutableStateOf<List<String>>(emptyList())
    private val changedAtFlow = MutableStateFlow(0L)
    /** When the pins last changed; a newer change on another device replaces them. */
    val changedAt = changedAtFlow.asStateFlow()

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        pinned.value = prefs.getString(KEY, null).orEmpty().split(SEPARATOR).filter { it.isNotBlank() }
        changedAtFlow.value = prefs.getLong(KEY_AT, 0L)
    }

    private fun save(context: Context, updated: List<String>, at: Long) {
        pinned.value = updated
        changedAtFlow.value = at
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, updated.joinToString(SEPARATOR)).putLong(KEY_AT, at).apply()
    }

    fun toggle(context: Context, playlistId: String) {
        load(context)
        val updated = if (playlistId in pinned.value) pinned.value - playlistId else pinned.value + playlistId
        save(context, updated, System.currentTimeMillis())
    }

    /** Takes the pins another device set. */
    fun replace(context: Context, ids: List<String>, at: Long) {
        load(context)
        if (ids != pinned.value || at != changedAtFlow.value) save(context, ids, at)
    }
}
