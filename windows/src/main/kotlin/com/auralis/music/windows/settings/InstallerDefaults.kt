package com.auralis.music.windows.settings

import androidx.datastore.preferences.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Properties

/** A reviewable settings-only snapshot, applied once to a genuinely empty profile. */
internal object InstallerDefaults {
    val stores = setOf("auralis_settings", "auralis_appearance_settings", "auralis_content", "auralis_privacy",
        "auralis_storage", "auralis_updater", "auralis_ai_translation", "discord_rpc_preferences")
    val propertyKeys = mapOf(
        "desktop-preferences.properties" to setOf("settings"),
        "desktop-system.properties" to setOf("closeToTray", "songNotifications"),
        "desktop-home.properties" to setOf("recentsGrid"),
        "desktop-sidebars.properties" to setOf("left", "right", "leftCollapsed", "rightCollapsed"),
        "desktop-language.properties" to setOf("tag"),
        "desktop-discord.properties" to setOf("card", "migrated"),
        "playback-volume.properties" to setOf("volume", "volume_before_mute"),
        "library-flags.properties" to setOf("spicy_player_defaults")
    )
    fun safeKey(key: String): Boolean = !Regex("token|password|secret|cookie|api.?key|proxy|username|discriminator|avatar|is_logged_in|auth|directory|path|folder", RegexOption.IGNORE_CASE).containsMatchIn(key)

    fun safeJson(value: JSONObject): JSONObject = JSONObject().also { clean ->
        value.keys().forEach { key -> if (safeKey(key)) {
            val item = value.get(key)
            if (item !is String || (!item.contains(Regex("[A-Za-z]:[\\\\/]")) && !item.startsWith("file:")))
                clean.put(key, if (item is JSONObject) safeJson(item) else item)
        } }
    }

    suspend fun snapshot(source: File): JSONObject {
        val result = JSONObject().put("version", 1).put("source", "normal-installed-profile")
        val settings = JSONObject()
        for (name in stores) {
            val input = File(source, "settings/$name.preferences_pb")
            if (!input.isFile) continue
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { input })
                val entries = JSONObject()
                store.data.first().asMap().forEach { (key, value) ->
                    if (safeKey(key.name)) {
                        val type = when (value) {
                            is Boolean -> "boolean"; is Int -> "int"; is Long -> "long"
                            is Float -> "float"; is Double -> "double"; is Set<*> -> "set"; else -> "string"
                        }
                        if (value !is String || (!value.contains(Regex("[A-Za-z]:[\\\\/]")) && !value.startsWith("file:")))
                            entries.put(key.name, JSONObject().put("type", type).put("value", if (value is Set<*>) JSONArray(value) else value))
                    }
                }
                settings.put(name, entries)
            } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
        }
        val properties = JSONObject()
        for ((name, allowed) in propertyKeys) {
            val file = File(source, name)
            if (!file.isFile) continue
            val values = Properties().apply { file.inputStream().use(::load) }
            val entries = JSONObject()
            for (key in allowed) values.getProperty(key)?.let { raw ->
                entries.put(key, if (key in setOf("settings", "card")) safeJson(JSONObject(raw)).toString() else raw)
            }
            properties.put(name, entries)
        }
        return result.put("stores", settings).put("properties", properties)
    }

    suspend fun install(dataDir: File, defaults: JSONObject? = null): Boolean {
        // Called before logging, locks or DataStore creation. Upgrades never reseed preferences.
        if (dataDir.listFiles()?.isNotEmpty() == true) return false
        val manifest = defaults ?: InstallerDefaults::class.java.getResourceAsStream("/installer-defaults.json")
            ?.bufferedReader()?.use { JSONObject(it.readText()) } ?: return false
        require(manifest.getInt("version") == 1)
        dataDir.mkdirs()
        val settings = manifest.optJSONObject("stores") ?: JSONObject()
        for (name in settings.keys()) {
            require(name in stores) { "Unrecognised default settings store" }
            val values = settings.getJSONObject(name)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val store = PreferenceDataStoreFactory.create(scope = scope,
                    produceFile = { File(dataDir, "settings/$name.preferences_pb") })
                store.edit { prefs -> values.keys().forEach { key ->
                    require(safeKey(key)) { "Account credentials cannot be installer defaults" }
                    val entry = values.getJSONObject(key)
                    when (entry.getString("type")) {
                        "boolean" -> prefs[booleanPreferencesKey(key)] = entry.getBoolean("value")
                        "int" -> prefs[intPreferencesKey(key)] = entry.getInt("value")
                        "long" -> prefs[longPreferencesKey(key)] = entry.getLong("value")
                        "float" -> prefs[floatPreferencesKey(key)] = entry.getDouble("value").toFloat()
                        "double" -> prefs[doublePreferencesKey(key)] = entry.getDouble("value")
                        "set" -> { val a = entry.getJSONArray("value"); prefs[stringSetPreferencesKey(key)] = (0 until a.length()).map { a.getString(it) }.toSet() }
                        "string" -> prefs[stringPreferencesKey(key)] = entry.getString("value")
                        else -> error("Unknown default preference type")
                    }
                } }
            } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
        }
        val properties = manifest.optJSONObject("properties") ?: JSONObject()
        for (name in properties.keys()) {
            val allowed = propertyKeys[name] ?: error("Unrecognised default properties file")
            val entries = properties.getJSONObject(name)
            val values = Properties()
            entries.keys().forEach { key -> require(key in allowed); values.setProperty(key, entries.getString(key)) }
            File(dataDir, name).outputStream().use { values.store(it, "Auralis release defaults") }
        }
        File(dataDir, "installer-defaults-version").writeText("1\n")
        return true
    }
}
