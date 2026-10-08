package com.auralis.music.data.network

import com.auralis.music.domain.model.ContentSettings
import java.util.Locale

/**
 * `hl` / `gl` for InnerTube requests that show content to the user (home, search, browse,
 * radio, suggestions), from Settings → Content.
 *
 * Internal requests whose parsing compares English text (Shorts category check, playlist import,
 * library sync) deliberately keep "en"/"US" and don't use this.
 */
object ContentLocale {
    /** Settings → Content as the app currently holds it. Each app points this at its settings store at startup. */
    @Volatile var settings: () -> ContentSettings = { ContentSettings() }

    /** The app's own language tag, or "" when it follows the system. Each app sets this at startup. */
    @Volatile var appLanguageTag: () -> String = { "" }

    fun hl(): String {
        val chosen = settings().contentLanguage
        if (chosen != ContentSettings.SYSTEM) return chosen
        // "System default" follows the app language first (as Metrolist does): pick Hindi as the
        // app language and YouTube answers in Hindi too, names included.
        val appTag = appLanguageTag()
        if (appTag.isNotBlank()) {
            val app = Locale.forLanguageTag(appTag)
            if (app.toLanguageTag() in YouTubeLocales.languageCodes) return app.toLanguageTag()
            if (app.language in YouTubeLocales.languageCodes) return app.language
        }
        val device = Locale.getDefault()
        // YouTube takes plain language codes plus a few regional variants (en-GB, pt-PT, zh-TW…).
        return when {
            device.language.isBlank() -> "en"
            device.country.isNotBlank() && "${device.language}-${device.country}" in YouTubeLocales.languageCodes ->
                "${device.language}-${device.country}"
            device.language in YouTubeLocales.languageCodes -> device.language
            else -> "en"
        }
    }

    fun gl(): String {
        val chosen = settings().contentCountry
        if (chosen != ContentSettings.SYSTEM) return chosen
        // Same order as ViviMusic/Metrolist: the app language's region (e.g. pt-BR → BR), then
        // the phone's. The phone's region comes from the system configuration: with a per-app
        // language like plain "hi", Locale.getDefault() has no country, which used to fall to US.
        val appTag = appLanguageTag()
        val appCountry = if (appTag.isBlank()) "" else Locale.forLanguageTag(appTag).country
        if (appCountry in YouTubeLocales.countryCodes) return appCountry
        for (country in systemLocaleCountries()) {
            if (country in YouTubeLocales.countryCodes) return country
        }
        val device = Locale.getDefault().country
        return if (device in YouTubeLocales.countryCodes) device else "US"
    }
}

/** The system's locale regions in preference order (not the app's own language). */
internal expect fun systemLocaleCountries(): List<String>
