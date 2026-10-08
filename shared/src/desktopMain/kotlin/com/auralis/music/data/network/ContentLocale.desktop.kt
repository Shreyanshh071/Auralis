package com.auralis.music.data.network

import java.util.Locale

// The JVM picks its default locale up from the OS display language and region.
internal actual fun systemLocaleCountries(): List<String> = listOf(Locale.getDefault().country)
