package com.auralis.music.data.network

import com.auralis.music.data.datastore.ContentSettingsStore
import com.auralis.music.domain.model.ContentSettings
import com.auralis.music.domain.model.ProxyType
import okhttp3.Credentials
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Settings → Content → Proxy.
 *
 * Auralis builds about twenty separate OkHttp clients, and ExoPlayer and NewPipe open their own
 * connections, so the proxy is installed process-wide rather than per client: every OkHttp
 * client without an explicit proxy asks [ProxySelector.getDefault] for each new connection, and
 * HttpURLConnection does the same. Read live from [ContentSettingsStore], so toggling applies to
 * the next connection (pooled connections finish on their old route).
 *
 * Passwords: SOCKS and HttpURLConnection consult [Authenticator]; OkHttp does not for HTTP
 * proxies, so each client also carries [authenticator] (added at its builder).
 */
object ContentProxy {
    private val systemSelector: ProxySelector? = ProxySelector.getDefault()
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> =
                configuredProxy(ContentSettingsStore.value)?.let { listOf(it) }
                    ?: systemSelector?.select(uri)
                    ?: listOf(Proxy.NO_PROXY)

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                systemSelector?.connectFailed(uri, sa, ioe)
            }
        })
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                val s = ContentSettingsStore.value
                val proxy = configuredProxy(s) ?: return null
                if (!s.hasProxyAuth) return null
                val host = (proxy.address() as? InetSocketAddress)?.hostString
                // Only ever hand the proxy's credentials to the proxy, never to a site's auth prompt.
                if (requestorType != RequestorType.PROXY && !requestingHost.equals(host, ignoreCase = true)) return null
                return PasswordAuthentication(s.proxyUsername, s.proxyPassword.toCharArray())
            }
        })
    }

    /** Answers an HTTP proxy's 407 with the saved credentials (once; a second 407 means they're wrong). */
    val authenticator = okhttp3.Authenticator { _, response ->
        val s = ContentSettingsStore.value
        when {
            !s.proxyEnabled || s.proxyType != ProxyType.HTTP || !s.hasProxyAuth -> null
            response.request.header("Proxy-Authorization") != null -> null
            else -> response.request.newBuilder()
                .header("Proxy-Authorization", Credentials.basic(s.proxyUsername, s.proxyPassword))
                .build()
        }
    }

    /** The proxy the settings describe, or null when it's off or "host:port" doesn't parse. */
    fun configuredProxy(s: ContentSettings): Proxy? {
        if (!s.proxyEnabled) return null
        val address = parseHostPort(s.proxyUrl) ?: return null
        val type = if (s.proxyType == ProxyType.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP
        return Proxy(type, address)
    }

    /** "host:port" (scheme optional) → unresolved address, so DNS happens at connect time. */
    fun parseHostPort(raw: String): InetSocketAddress? {
        val trimmed = raw.trim().substringAfter("://").trimEnd('/')
        val colon = trimmed.lastIndexOf(':')
        if (colon <= 0) return null
        val host = trimmed.substring(0, colon).removePrefix("[").removeSuffix("]")
        val port = trimmed.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (host.isBlank()) return null
        return InetSocketAddress.createUnresolved(host, port)
    }
}
