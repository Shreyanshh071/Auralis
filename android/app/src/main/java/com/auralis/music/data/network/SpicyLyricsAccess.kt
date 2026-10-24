package com.auralis.music.data.network

/** Public application credential; a saved user override is optional. Never accepts server secrets. */
object SpicyLyricsAccess {
    private val nativeKey = Regex("sl_pk_[A-Za-z0-9_-]+")
    private val bundledKey: String get() = com.auralis.music.BuildConfig.SPICY_LYRICS_PUBLISHABLE_KEY
    fun resolve(override: String, bundled: String = bundledKey): String {
        val selected = override.trim().ifBlank { bundled.trim() }
        return selected.takeIf { nativeKey.matches(it) }.orEmpty()
    }
}
