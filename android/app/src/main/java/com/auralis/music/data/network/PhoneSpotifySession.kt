package com.auralis.music.data.network

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.webkit.CookieManager

/** [SpotifySession] on the phone: the same SharedPreferences file and keys as before, and the sign-in web view's cookies. */
object PhoneSpotifySession {
    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(SpotifySession.PREFS, Context.MODE_PRIVATE)
        SpotifySession.webCookies = { url -> CookieManager.getInstance().getCookie(url).orEmpty() }
        SpotifySession.clearWebCookies = ::clearCookies
        SpotifySession.init(PreferencesStore(prefs))
        SpotifyPlatform.clientId = { com.auralis.music.BuildConfig.SPOTIFY_CLIENT_ID }
        SpotifyPlatform.clientSecret = { com.auralis.music.BuildConfig.SPOTIFY_CLIENT_SECRET }
        SpotifyPlatform.isPlaybackResolving = { AudioStreamResolver.isPlaybackResolving }
        SpotifyPlatform.rememberMatchedVideoId = { spotifyId, youtubeId, durationSec ->
            AudioStreamResolver.rememberMatchedVideoId(spotifyId, youtubeId, durationSec)
        }
    }

    private class PreferencesStore(private val prefs: SharedPreferences) : SpotifySession.Store {
        override fun getString(key: String): String = prefs.getString(key, "").orEmpty()
        override fun getLong(key: String): Long = prefs.getLong(key, 0L)
        override fun put(values: Map<String, Any>) {
            val edit = prefs.edit()
            values.forEach { (key, value) ->
                when (value) {
                    is Long -> edit.putLong(key, value)
                    else -> edit.putString(key, value.toString())
                }
            }
            edit.apply()
        }
        override fun clear() = prefs.edit().clear().apply()
    }

    private fun clearCookies() {
        val manager = CookieManager.getInstance()
        for (site in listOf("https://open.spotify.com", "https://accounts.spotify.com", "https://spotify.com")) {
            val host = Uri.parse(site).host.orEmpty()
            val baseDomain = host.substringAfter('.', host)
            manager.getCookie(site).orEmpty().split(";")
                .mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotBlank) }
                .forEach { name ->
                    for (domain in listOf(host, ".$baseDomain")) {
                        manager.setCookie(site, "$name=; Domain=$domain; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
                    }
                    manager.setCookie(site, "$name=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
                }
        }
        manager.flush()
    }
}
