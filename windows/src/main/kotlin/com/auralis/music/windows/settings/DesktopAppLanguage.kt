package com.auralis.music.windows.settings

import com.auralis.music.ui.settings.AppLanguageControl
import com.auralis.music.util.PropertiesFileKeyValueStore
import com.auralis.music.util.desktopAppDataDir
import java.util.Locale

object DesktopAppLanguage : AppLanguageControl {
    private val store by lazy { PropertiesFileKeyValueStore(java.io.File(desktopAppDataDir(), "desktop-language.properties")) }
    @Volatile private var chosen = ""
    fun install() {
        chosen = store.getString("tag", "") ?: ""
        com.auralis.music.ui.i18n.DesktopStrings.install(if (chosen.isBlank()) Locale.getDefault() else Locale.forLanguageTag(chosen))
        com.auralis.music.data.network.ContentLocale.appLanguageTag = { chosen }
    }
    override fun currentTag() = chosen
    override fun currentLabel() = if (chosen.isBlank()) "System default" else Locale.forLanguageTag(chosen).getDisplayName(Locale.forLanguageTag(chosen))
    override fun available(): List<String> = javaClass.classLoader.getResourceAsStream("desktop-languages.txt")?.bufferedReader()?.use { it.readLines().filter(String::isNotBlank) }.orEmpty()
    override fun apply(tag: String) {
        require(tag.isBlank() || tag in available())
        chosen = tag; store.putStrings(mapOf("tag" to tag))
        com.auralis.music.ui.i18n.DesktopStrings.install(if (tag.isBlank()) Locale.getDefault() else Locale.forLanguageTag(tag))
    }
}
