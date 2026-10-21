package com.auralis.music.windows.discord

import com.auralis.music.util.KeyValueStore
import com.auralis.music.util.PropertiesFileKeyValueStore
import com.auralis.music.util.desktopAppDataDir
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

internal data class DiscordCardSettings(
    val enabled: Boolean = false, val statusLine: String = "line2", val appName: String = "",
    val line1: String = "title", val line2: String = "artist", val line3: String = "album",
    val cover: Boolean = true, val badge: Boolean = true, val progress: Boolean = true,
    val showPaused: Boolean = false, val hideDetails: Boolean = false,
    val linkLine1: Boolean = false, val linkLine2: Boolean = false, val linkCover: Boolean = false,
    val button1: String = "listen", val button2: String = "app"
) {
    fun json() = JSONObject().put("enabled", enabled).put("statusLine", statusLine).put("appName", appName)
        .put("line1", line1).put("line2", line2).put("line3", line3).put("cover", cover).put("badge", badge)
        .put("progress", progress).put("showPaused", showPaused).put("hideDetails", hideDetails)
        .put("linkLine1", linkLine1).put("linkLine2", linkLine2).put("linkCover", linkCover)
        .put("button1", button1).put("button2", button2)
    companion object {
        fun parse(raw: String): DiscordCardSettings = runCatching {
            val j = JSONObject(raw)
            fun choice(key: String, default: String, options: List<String>) = j.optString(key, default).takeIf { it in options } ?: default
            DiscordCardSettings(j.optBoolean("enabled"), choice("statusLine", "line2", listOf("app","line1","line2")), j.optString("appName").take(128),
                choice("line1", "title", listOf("title","artist","album")), choice("line2", "artist", listOf("title","artist","album","artist_album","off")),
                choice("line3", "album", listOf("title","artist","album","off")), j.optBoolean("cover",true), j.optBoolean("badge",true),
                j.optBoolean("progress",true), j.optBoolean("showPaused"), j.optBoolean("hideDetails"), j.optBoolean("linkLine1"),
                j.optBoolean("linkLine2"), j.optBoolean("linkCover"), choice("button1","listen", listOf("listen","album","artist","app","off")),
                choice("button2","app",listOf("listen","album","artist","app","off")))
        }.getOrDefault(DiscordCardSettings())
    }
}

internal class DiscordCardStore(private val disk: KeyValueStore) {
    private val mutable = MutableStateFlow(DiscordCardSettings.parse(disk.getString("card", "").orEmpty()))
    val settings = mutable.asStateFlow()
    @Synchronized fun update(transform: (DiscordCardSettings) -> DiscordCardSettings) {
        val next = transform(mutable.value)
        disk.putStrings(mapOf("card" to next.json().toString()))
        mutable.value = next
    }
    @Synchronized fun migrate(enabled: Boolean) {
        if (disk.getString("migrated", "false") == "true") return
        if (disk.getString("card", "").isNullOrBlank()) update { it.copy(enabled = enabled) }
        disk.putStrings(mapOf("migrated" to "true"))
    }
    fun reset() = update { DiscordCardSettings(enabled = it.enabled) }
}
internal object DiscordCardPreferences {
    val store by lazy { DiscordCardStore(PropertiesFileKeyValueStore(File(desktopAppDataDir(), "desktop-discord.properties"))) }
}
