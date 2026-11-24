package com.auralis.music.windows.update

import com.auralis.music.windows.settings.DesktopReleaseCheck
import org.json.JSONArray
import org.json.JSONObject

/**
 * Each desktop OS reads its own tag namespace of the shared release feed: `windows-v<x.y.z>` with an
 * `Auralis*.msi`, `macos-v<x.y.z>` with an `Auralis*.dmg` (or a zipped app). A phone release may carry
 * the same numbers, and a Mac must never be pointed at a Windows installer, or the other way round.
 */
internal enum class ReleasePlatform(val tagPrefix: String, val label: String) {
    WINDOWS("windows-v", "Windows"),
    MACOS("macos-v", "macOS");

    /**
     * Windows takes the MSI only: the EXE installer lacks the gaming-overlay step that only the MSI
     * tasks add (scripts/add-msi-overlay-step.ps1). The Mac prefers a disk image over a zipped app.
     */
    fun installerRank(name: String): Int? {
        val lower = name.lowercase()
        if (!lower.startsWith("auralis")) return null
        return when (this) {
            WINDOWS -> if (lower.endsWith(".msi")) 0 else null
            MACOS -> when {
                lower.endsWith(".dmg") -> 0
                lower.endsWith(".zip") -> 1
                else -> null
            }
        }
    }

    companion object {
        fun current(osName: String = System.getProperty("os.name").orEmpty()): ReleasePlatform =
            if (osName.lowercase().contains("mac")) MACOS else WINDOWS
    }
}

internal data class Version(val major: Int, val minor: Int, val patch: Int, val preview: List<String> = emptyList()) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch).takeIf { it != 0 }?.let { return it }
        if (preview.isEmpty() || other.preview.isEmpty()) return when {
            preview.isEmpty() && other.preview.isEmpty() -> 0
            preview.isEmpty() -> 1
            else -> -1
        }
        for (i in 0 until minOf(preview.size, other.preview.size)) {
            val a = preview[i]; val b = other.preview[i]
            val an = a.toBigIntegerOrNull(); val bn = b.toBigIntegerOrNull()
            val order = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (order != 0) return order
        }
        return preview.size.compareTo(other.preview.size)
    }

    companion object {
        private val pattern = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z.-]+)?$")
        fun parse(value: String): Version? {
            val match = pattern.matchEntire(value) ?: return null
            val numbers = (1..3).map { match.groupValues[it].toIntOrNull() ?: return null }
            val preview = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (preview.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) return null
            return Version(numbers[0], numbers[1], numbers[2], preview)
        }
    }
}

/** [sha256] is lowercase hex from the API's `digest` field, when GitHub supplied one. */
internal data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String?)

internal data class DesktopRelease(
    val platform: ReleasePlatform,
    val version: Version,
    val displayVersion: String,
    val pageUrl: String,
    val notes: String,
    val installer: ReleaseAsset,
    /** `<installer name>.sha256`, read only when the installer asset has no `digest`. */
    val checksum: ReleaseAsset?,
)

internal object DesktopReleases {
    private val sha256Hex = Regex("^[0-9a-f]{64}$")

    fun latest(
        releases: JSONArray, platform: ReleasePlatform, includePreviews: Boolean,
        repository: String = DesktopReleaseCheck.REPOSITORY,
    ): DesktopRelease? = (0 until releases.length())
        .mapNotNull { index -> parse(releases.optJSONObject(index) ?: return@mapNotNull null, platform, includePreviews, repository) }
        .maxByOrNull { it.version }

    /** A newer release than [installed], never the installed version itself (a same-version MSI fails with 1603). */
    fun newerThan(release: DesktopRelease?, installed: String): DesktopRelease? {
        val current = Version.parse(installed) ?: return release
        return release?.takeIf { it.version > current }
    }

    private fun parse(release: JSONObject, platform: ReleasePlatform, previews: Boolean, repository: String): DesktopRelease? {
        if (release.optBoolean("draft")) return null
        val tag = release.optString("tag_name")
        if (!tag.startsWith(platform.tagPrefix, ignoreCase = true)) return null
        val number = tag.substring(platform.tagPrefix.length)
        val version = Version.parse(number) ?: return null
        if (!previews && (release.optBoolean("prerelease") || version.preview.isNotEmpty())) return null
        val page = release.optString("html_url")
        if (!page.startsWith("$repository/releases/tag/")) return null
        val assets = release.optJSONArray("assets") ?: return null
        val uploaded = (0 until assets.length()).mapNotNull(assets::optJSONObject).mapNotNull(::asset)
        val installer = uploaded.mapNotNull { asset -> platform.installerRank(asset.name)?.let { it to asset } }
            .minByOrNull { it.first }?.second ?: return null
        val checksum = uploaded.firstOrNull { it.name.equals("${installer.name}.sha256", ignoreCase = true) }
        return DesktopRelease(platform, version, number, page, release.optString("body").take(20_000), installer, checksum)
    }

    private fun asset(asset: JSONObject): ReleaseAsset? {
        if (asset.optString("state") != "uploaded") return null
        val size = asset.optLong("size")
        val url = asset.optString("browser_download_url")
        val name = asset.optString("name")
        if (size <= 0 || url.isBlank() || name.isBlank()) return null
        return ReleaseAsset(name, url, size, sha256FromDigest(asset.optString("digest").takeIf { it.isNotBlank() }))
    }

    /** GitHub writes `"digest": "sha256:<hex>"` on each asset; any other algorithm is ignored. */
    fun sha256FromDigest(digest: String?): String? {
        val value = digest?.trim() ?: return null
        if (!value.startsWith("sha256:", ignoreCase = true)) return null
        return value.substring("sha256:".length).lowercase().takeIf(sha256Hex::matches)
    }

    /**
     * A `.sha256` file is `<hex>` or `<hex>  <file name>` (sha256sum / Get-FileHash output). When it
     * lists several files, only the line naming [fileName] counts.
     */
    fun sha256FromChecksumFile(text: String, fileName: String): String? {
        val lines = text.lineSequence().map { it.trim().removePrefix("﻿") }.filter { it.isNotEmpty() }.toList()
        val parsed = lines.mapNotNull { line ->
            val parts = line.split(Regex("\\s+"), limit = 2)
            val hash = parts[0].lowercase().takeIf(sha256Hex::matches) ?: return@mapNotNull null
            hash to parts.getOrNull(1)?.trim()?.removePrefix("*")
        }
        if (parsed.size == 1 && parsed[0].second == null) return parsed[0].first
        return parsed.singleOrNull { it.second.equals(fileName, ignoreCase = true) }?.first
    }

    /** Release notes as plain text: GitHub's Markdown without heading and emphasis marks. */
    fun plainNotes(markdown: String): String = markdown.replace("\r\n", "\n").lineSequence()
        .map { line ->
            line.trimEnd()
                .replace(Regex("^#{1,6}\\s+"), "")
                .replace(Regex("^\\s*[-*+]\\s+"), "• ")
                .replace(Regex("\\*\\*|__|`"), "")
                .replace(Regex("\\[([^]]+)]\\((https?://[^)]+)\\)"), "$1")
        }
        .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
}
