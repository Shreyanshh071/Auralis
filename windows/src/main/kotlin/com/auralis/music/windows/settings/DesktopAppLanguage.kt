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
import com.auralis.music.util.KeyValueStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import java.util.Locale

internal class DesktopLanguageController(
    private val store: KeyValueStore,
    private val systemDefaultLocale: Locale,
    private val installLocale: (Locale) -> Unit
) : AppLanguageControl {
    private var chosen by mutableStateOf("")

    fun install() {
        chosen = (store.getString("tag", "") ?: "").takeIf { it.isBlank() || it in available() }.orEmpty()
        val effectiveLocale = if (chosen.isBlank()) systemDefaultLocale else Locale.forLanguageTag(chosen)
        installLocale(effectiveLocale)
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
        installLocale(effectiveLocale)
    }
}

object DesktopAppLanguage : AppLanguageControl {
    private val systemDefaultLocale = Locale.getDefault()
    private val controller by lazy {
        DesktopLanguageController(PropertiesFileKeyValueStore(java.io.File(desktopAppDataDir(), "desktop-language.properties"), synchronous = true),
            systemDefaultLocale) { locale ->
            Locale.setDefault(locale)
            DesktopStrings.install(locale)
            AlbumMetadataResolver.clearCache()
            ArtworkResolver.clearCache()
            ContentSettingsSource.notifyLanguageChanged()
        }
    }
    fun install() { controller.install(); ContentLocale.appLanguageTag = { controller.currentTag() } }
    override fun currentTag() = controller.currentTag()
    override fun currentLabel() = controller.currentLabel()
    override fun available() = controller.available()
    override fun apply(tag: String) = controller.apply(tag)
}
