package com.auralis.music.windows.update

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where update traffic may go: HTTPS on github.com and GitHub's asset download hosts (release
 * downloads redirect from github.com to one of them). Every redirect hop is checked, not only the
 * first URL. [testOrigin] is the overridden feed's exact scheme, host and port (see [UpdateFeed]).
 */
internal class UpdateHostPolicy(private val testOrigin: HttpUrl? = null) {
    fun allows(url: HttpUrl): Boolean {
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        if (testOrigin != null && url.scheme == testOrigin.scheme && url.host == testOrigin.host && url.port == testOrigin.port) return true
        return url.scheme == "https" && url.port == 443 && url.host in GITHUB_HOSTS
    }

    fun allows(url: String): Boolean = url.toHttpUrlOrNull()?.let(::allows) ?: false

    companion object {
        val GITHUB_HOSTS = setOf(
            "github.com", "api.github.com",
            "objects.githubusercontent.com", "release-assets.githubusercontent.com", "github-releases.githubusercontent.com",
        )
    }
}

/**
 * The release feed. `-Dauralis.updateFeedUrl=http://127.0.0.1:<port>/releases` points it at a local
 * test server, whose origin then also serves the installers. Only HTTPS, or HTTP on this computer.
 */
internal data class UpdateFeed(val url: String, val policy: UpdateHostPolicy, val overridden: Boolean) {
    companion object {
        const val PROPERTY = "auralis.updateFeedUrl"
        const val DEFAULT = "https://api.github.com/repos/Shreyanshh071/Auralis/releases?per_page=100"
        private val LOOPBACK = setOf("127.0.0.1", "localhost", "::1")

        fun fromSystem(value: String? = System.getProperty(PROPERTY)): UpdateFeed {
            val override = value?.trim()?.takeIf { it.isNotEmpty() } ?: return UpdateFeed(DEFAULT, UpdateHostPolicy(), false)
            val url = requireNotNull(override.toHttpUrlOrNull()) { "$PROPERTY is not an http(s) URL." }
            require(url.username.isEmpty() && url.password.isEmpty()) { "$PROPERTY must not carry credentials." }
            require(url.scheme == "https" || url.host in LOOPBACK) { "$PROPERTY must be HTTPS, or HTTP on this computer." }
            return UpdateFeed(url.toString(), UpdateHostPolicy(url), true)
        }
    }
}
