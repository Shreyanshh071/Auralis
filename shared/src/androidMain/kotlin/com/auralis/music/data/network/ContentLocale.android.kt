package com.auralis.music.data.network

// The system configuration, not Locale.getDefault(): with a per-app language like plain "hi"
// the default locale has no country.
internal actual fun systemLocaleCountries(): List<String> {
    val system = android.content.res.Resources.getSystem().configuration.locales
    return List(system.size()) { i -> system[i].country }
}
