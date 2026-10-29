package com.auralis.music.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.first

/** Reads real disk preferences, never a UI's temporary first-frame defaults. */
class DataStoreAccountPreferences(private val appearance: DataStore<Preferences>,
    private val playback: DataStore<Preferences>,
    private val resolved: (suspend () -> Map<String, String>)? = null) : AccountPreferencesLocal {
    override suspend fun read(): Map<String, String> {
        // Platform stores resolve legacy keys and platform defaults into the same values the UI uses.
        resolved?.let { return it() }
        val values = AccountPreferenceSchema.defaultValues.toMutableMap()
        for ((prefix, store) in listOf("a" to appearance, "p" to playback)) {
            store.data.first().asMap().forEach { (key, value) ->
                val name = "$prefix.${key.name}"
                if (AccountPreferenceSchema.valid(name, value.toString())) values[name] = value.toString()
            }
        }
        return values
    }
    override suspend fun enabled(): Boolean = playback.data.first()[booleanPreferencesKey("cloud_sync_enabled")] ?: true
    override suspend fun apply(values: Map<String, String>) {
        for ((prefix, store) in listOf("a" to appearance, "p" to playback)) {
            val edits = values.filter { it.key.startsWith("$prefix.") && AccountPreferenceSchema.valid(it.key, it.value) }
            if (edits.isEmpty()) continue
            store.edit { prefs -> edits.forEach { (name, value) ->
                val key = name.substringAfter('.')
                when (AccountPreferenceSchema.defaults[name]) {
                    is Boolean -> prefs[booleanPreferencesKey(key)] = value.toBooleanStrict()
                    is Int -> prefs[intPreferencesKey(key)] = value.toInt()
                    is Float -> prefs[floatPreferencesKey(key)] = value.toFloat()
                    is String -> prefs[stringPreferencesKey(key)] = value
                }
            }
                edits["a.mini_player_design"]?.let { prefs[booleanPreferencesKey("new_mini_player_design")] = it != "Classic mini player" }
                edits["a.player_design"]?.let { prefs[booleanPreferencesKey("new_player_design")] = it != "Old player" }
            }
        }
    }
}

class DataStorePreferencesJournal(private val store: DataStore<Preferences>) : AccountPreferencesJournal {
    private val key = stringPreferencesKey("account_preferences_journal")
    override suspend fun read(): String? = store.data.first()[key]
    override suspend fun write(value: String) { store.edit { it[key] = value } }
}
