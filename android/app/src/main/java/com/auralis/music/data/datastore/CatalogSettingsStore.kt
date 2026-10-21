package com.auralis.music.data.datastore

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CatalogSource(val label: String) { YOUTUBE_MUSIC("YouTube Music"), SPOTIFY("Spotify") }

/** Android-only catalog preference; playback and the Room schema are unaffected. */
object CatalogSettingsStore {
    private var preferences: SharedPreferences? = null
    private val selected = MutableStateFlow(CatalogSource.YOUTUBE_MUSIC)
    val source = selected.asStateFlow()
    private val message = MutableStateFlow<String?>(null)
    val status = message.asStateFlow()
    fun init(context: Context) {
        if (preferences != null) return
        preferences = context.applicationContext.getSharedPreferences("auralis_catalog", Context.MODE_PRIVATE)
        selected.value = CatalogSource.entries.firstOrNull { it.name == preferences?.getString("source", null) }
            ?: CatalogSource.YOUTUBE_MUSIC
    }
    fun select(source: CatalogSource) {
        preferences?.edit()?.putString("source", source.name)?.apply()
        selected.value = source
        message.value = null
    }
    fun report(status: String?) { message.value = status }
}
