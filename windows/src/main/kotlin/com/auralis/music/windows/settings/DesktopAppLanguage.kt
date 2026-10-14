package com.auralis.music.windows.settings

import com.auralis.music.data.network.AlbumMetadataResolver
import com.auralis.music.data.network.ArtworkResolver
import com.auralis.music.data.network.ContentLocale
import com.auralis.music.data.network.ContentSettingsSource
import com.auralis.music.ui.i18n.DesktopStrings
import com.auralis.music.ui.i18n.Strings
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.settings.AppLanguageControl
import com.auralis.music.util.PropertiesFileKeyValueStore
import com.auralis.music.util.desktopAppDataDir
import java.util.Locale

object DesktopAppLanguage : AppLanguageControl {
    private val store by lazy { PropertiesFileKeyValueStore(java.io.File(desktopAppDataDir(), "desktop-language.properties")) }
    @Volatile private var chosen = ""
    private val systemDefaultLocale: Locale = Locale.getDefault()

    fun install() {
        chosen = store.getString("tag", "") ?: ""
        val effectiveLocale = if (chosen.isBlank()) systemDefaultLocale else Locale.forLanguageTag(chosen)
        Locale.setDefault(effectiveLocale)
        DesktopStrings.install(effectiveLocale)
        ContentLocale.appLanguageTag = { chosen }
    }

    override fun currentTag() = chosen

    override fun currentLabel() = if (chosen.isBlank()) {
        str(Strings.system_default)
    } else {
        val loc = Locale.forLanguageTag(chosen)
        loc.getDisplayName(loc).replaceFirstChar { it.titlecase(loc) }
    }

    override fun available(): List<String> = javaClass.classLoader.getResourceAsStream("desktop-languages.txt")?.bufferedReader()?.use { it.readLines().filter(String::isNotBlank) }.orEmpty()

    override fun apply(tag: String) {
        require(tag.isBlank() || tag in available())
        chosen = tag
        store.putStrings(mapOf("tag" to tag))
        val effectiveLocale = if (tag.isBlank()) systemDefaultLocale else Locale.forLanguageTag(tag)
        Locale.setDefault(effectiveLocale)
        DesktopStrings.install(effectiveLocale)
        AlbumMetadataResolver.clearCache()
        ArtworkResolver.clearCache()
        ContentSettingsSource.notifyLanguageChanged()
    }
}
