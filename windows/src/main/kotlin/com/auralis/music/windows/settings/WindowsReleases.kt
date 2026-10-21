package com.auralis.music.windows.settings

import org.json.JSONArray
import org.json.JSONObject

/** A separate, explicit Windows channel in the shared repository's release feed. */
internal object WindowsReleases {
    data class Version(val major: Int, val minor: Int, val patch: Int, val preview: List<String> = emptyList()) : Comparable<Version> {
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
                return Version(numbers[0],numbers[1],numbers[2],preview)
            }
        }
    }
    data class Release(val version: Version, val displayVersion: String, val url: String)

    fun latest(releases: JSONArray, includePreviews: Boolean): Release? = (0 until releases.length())
        .mapNotNull { index -> parse(releases.optJSONObject(index) ?: return@mapNotNull null, includePreviews) }
        .maxByOrNull { it.version }

    private fun parse(release: JSONObject, previews: Boolean): Release? {
        if (release.optBoolean("draft")) return null
        // A phone release may have the same numeric version. Only this tag namespace belongs to Windows.
        val tag = release.optString("tag_name")
        if (!tag.startsWith("windows-v", ignoreCase = true)) return null
        val number = tag.substring("windows-v".length)
        val version = Version.parse(number) ?: return null
        if (!previews && (release.optBoolean("prerelease") || version.preview.isNotEmpty())) return null
        val assets = release.optJSONArray("assets") ?: return null
        val installer = (0 until assets.length()).mapNotNull(assets::optJSONObject).any { asset ->
            val name = asset.optString("name").lowercase()
            name.startsWith("auralis") && (name.endsWith(".msi") || name.endsWith(".exe")) &&
                asset.optString("state") == "uploaded" && asset.optLong("size") > 0
        }
        if (!installer) return null
        val url = release.optString("html_url")
        if (!url.startsWith(DesktopReleaseCheck.REPOSITORY + "/releases/tag/")) return null
        return Release(version, number, url)
    }
}
