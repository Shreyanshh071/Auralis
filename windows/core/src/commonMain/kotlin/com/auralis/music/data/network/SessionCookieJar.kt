package com.auralis.music.data.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * The anonymous extraction session's cookies, matched the standard way ([Cookie.matches]: host-only
 * versus domain cookies, path prefix, Secure only over HTTPS) and dropped once expired. The old jar
 * sent every cookie whose host was a suffix of the request's in either direction, so a cookie for
 * `youtube.com` went to `notyoutube.com` and a subdomain's cookie leaked to its parent.
 *
 * Holds only the anonymous playback session: signed-in library credentials never pass through it.
 */
class SessionCookieJar(private val now: () -> Long = System::currentTimeMillis) : CookieJar {
    private val cookies = ConcurrentHashMap<String, Cookie>()

    private fun keyOf(c: Cookie) = "${c.domain}|${c.path}|${c.name}|${c.hostOnly}"

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val time = now()
        for (cookie in cookies) {
            // OkHttp already rejected cookies whose Domain doesn't cover the responding host.
            if (cookie.expiresAt <= time) this.cookies.remove(keyOf(cookie)) else this.cookies[keyOf(cookie)] = cookie
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val time = now()
        val result = ArrayList<Cookie>()
        val iterator = cookies.values.iterator()
        while (iterator.hasNext()) {
            val cookie = iterator.next()
            if (cookie.expiresAt <= time) iterator.remove()
            else if (cookie.matches(url)) result += cookie
        }
        return result
    }

    fun clear() = cookies.clear()

    val size: Int get() = cookies.size
}
