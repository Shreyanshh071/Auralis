package com.auralis.music.windows.dev

import com.auralis.music.windows.settings.DesktopReleaseCheck
import com.auralis.music.windows.update.DesktopReleases
import com.auralis.music.windows.update.ReleasePlatform
import com.auralis.music.windows.update.UpdateDownloader
import com.auralis.music.windows.update.UpdateFeed

/** Read-only check of the real shared release feed (or -Dauralis.updateFeedUrl) as the updater reads it; downloads nothing. */
fun main() {
    val feed = UpdateFeed.fromSystem()
    val downloader = UpdateDownloader(com.auralis.music.data.network.NetworkClientProvider.okHttpClient, feed.policy,
        kotlin.io.path.createTempDirectory("auralis-release-check").toFile())
    val releases = downloader.fetchFeed(feed.url)
    println("Desktop version: ${DesktopReleaseCheck.version}")
    for (platform in ReleasePlatform.entries) {
        val latest = DesktopReleases.latest(releases, platform, includePreviews = false)
        check(latest == null || latest.pageUrl.contains("/releases/tag/${platform.tagPrefix}", ignoreCase = true))
        println("${platform.label}: ${latest?.let { "${it.displayVersion} · ${it.installer.name} · SHA-256 ${it.installer.sha256 ?: "from ${it.checksum?.name ?: "nowhere"}"}" } ?: "no release"}")
        println("${platform.label} newer than installed: ${DesktopReleases.newerThan(latest, DesktopReleaseCheck.version)?.pageUrl ?: "none"}")
    }
}
