package com.auralis.music.windows.update

import com.auralis.music.windows.settings.DesktopReleaseCheck
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*

class DesktopReleasesTest {
    private val hash = "ab".repeat(32)

    private fun asset(name: String, size: Long = 12345, state: String = "uploaded", digest: String? = "sha256:$hash") = JSONObject()
        .put("name", name).put("state", state).put("size", size)
        .put("browser_download_url", "${DesktopReleaseCheck.REPOSITORY}/releases/download/x/$name")
        .apply { if (digest != null) put("digest", digest) }

    private fun release(tag: String, vararg assets: JSONObject = arrayOf(asset("Auralis-1.0.0.msi")), preview: Boolean = false, draft: Boolean = false) = JSONObject()
        .put("tag_name", tag).put("prerelease", preview).put("draft", draft).put("body", "## Fixes\n- **Faster** start")
        .put("html_url", "${DesktopReleaseCheck.REPOSITORY}/releases/tag/$tag")
        .put("assets", JSONArray().apply { assets.forEach { put(it) } })

    private fun windows(feed: JSONArray, previews: Boolean = false) = DesktopReleases.latest(feed, ReleasePlatform.WINDOWS, previews)
    private fun mac(feed: JSONArray, previews: Boolean = false) = DesktopReleases.latest(feed, ReleasePlatform.MACOS, previews)

    @Test fun androidReleasesNeverCountAsDesktopUpdates() {
        val phone = JSONArray().put(release("v1.1.1-rebuilt", asset("Auralis-v1.1.1-universal.apk")))
            .put(release("v99.0.0", asset("Auralis-universal.apk"), asset("Auralis-99.0.0.msi"), asset("Auralis-99.0.0.dmg")))
        assertNull(windows(phone, true))
        assertNull(mac(phone, true))
        val mixed = phone.put(release("windows-v1.0.1"))
        assertEquals("1.0.1", assertNotNull(windows(mixed)).displayVersion)
    }

    @Test fun eachOsSeesOnlyItsOwnTagNamespaceAndInstaller() {
        val feed = JSONArray()
            .put(release("windows-v2.0.0", asset("Auralis-2.0.0.msi"), asset("Auralis-2.0.0.dmg")))
            .put(release("macos-v1.5.0", asset("Auralis-1.5.0.dmg"), asset("Auralis-1.5.0.msi")))
        assertEquals("2.0.0", windows(feed)?.displayVersion)
        assertEquals("Auralis-2.0.0.msi", windows(feed)?.installer?.name)
        assertEquals("1.5.0", mac(feed)?.displayVersion)
        assertEquals("Auralis-1.5.0.dmg", mac(feed)?.installer?.name)
        // A Mac tag with only Windows installers, or the reverse, offers nothing.
        assertNull(mac(JSONArray().put(release("macos-v3.0.0", asset("Auralis-3.0.0.msi"), asset("Auralis-3.0.0.exe")))))
        assertNull(windows(JSONArray().put(release("windows-v3.0.0", asset("Auralis-3.0.0.dmg"), asset("Auralis-3.0.0-mac.zip")))))
        assertEquals("Auralis-4.0.0-macos.zip", mac(JSONArray().put(release("macos-v4.0.0", asset("Auralis-4.0.0-macos.zip"))))?.installer?.name)
        assertEquals(ReleasePlatform.MACOS, ReleasePlatform.current("Mac OS X"))
        assertEquals(ReleasePlatform.WINDOWS, ReleasePlatform.current("Windows 11"))
    }

    @Test fun windowsTakesTheMsiOnlyNeverTheExeInstaller() {
        val both = JSONArray().put(release("windows-v5.0.0", asset("AuralisSetup-5.0.0.exe"), asset("Auralis-5.0.0.msi")))
        assertEquals("Auralis-5.0.0.msi", windows(both)?.installer?.name)
        assertNull(windows(JSONArray().put(release("windows-v5.0.0", asset("Auralis-5.0.0.exe")))))
        for (name in listOf("Auralis.apk", "Auralis.dmg", "Auralis.AppImage", "SHA256SUMS.txt", "dependency.msi", "Auralis-5.0.0.msi.sha256"))
            assertNull(windows(JSONArray().put(release("windows-v5.0.0", asset(name)))), name)
        assertNull(windows(JSONArray().put(release("windows-v5.0.0", asset("Auralis-5.0.0.msi", size = 0)))))
        assertNull(windows(JSONArray().put(release("windows-v5.0.0", asset("Auralis-5.0.0.msi", state = "open")))))
    }

    @Test fun theAssetDigestOrItsChecksumAssetIsRecorded() {
        val withDigest = assertNotNull(windows(JSONArray().put(release("windows-v1.2.0", asset("Auralis-1.2.0.msi", digest = "sha256:${hash.uppercase()}")))))
        assertEquals(hash, withDigest.installer.sha256)
        assertNull(withDigest.checksum)
        val fallback = assertNotNull(windows(JSONArray().put(release("windows-v1.2.0",
            asset("Auralis-1.2.0.msi", digest = null), asset("Auralis-1.2.0.msi.sha256", size = 100, digest = null)))))
        assertNull(fallback.installer.sha256)
        assertEquals("Auralis-1.2.0.msi.sha256", fallback.checksum?.name)
        assertEquals(null, DesktopReleases.sha256FromDigest("sha512:$hash"))
        assertEquals(null, DesktopReleases.sha256FromDigest("sha256:1234"))
        assertEquals(null, DesktopReleases.sha256FromDigest(null))
    }

    @Test fun checksumFilesAreReadOnlyForTheirOwnInstaller() {
        assertEquals(hash, DesktopReleases.sha256FromChecksumFile("$hash\n", "Auralis-1.2.0.msi"))
        assertEquals(hash, DesktopReleases.sha256FromChecksumFile("﻿${hash.uppercase()}  Auralis-1.2.0.msi\r\n", "Auralis-1.2.0.msi"))
        assertEquals(hash, DesktopReleases.sha256FromChecksumFile("$hash *Auralis-1.2.0.msi", "Auralis-1.2.0.msi"))
        val other = "cd".repeat(32)
        assertEquals(other, DesktopReleases.sha256FromChecksumFile("$hash  Auralis-1.2.0.exe\n$other  Auralis-1.2.0.msi", "Auralis-1.2.0.msi"))
        assertNull(DesktopReleases.sha256FromChecksumFile("$hash  Auralis-1.1.0.msi", "Auralis-1.2.0.msi"))
        assertNull(DesktopReleases.sha256FromChecksumFile("not a hash", "Auralis-1.2.0.msi"))
    }

    @Test fun previewPreferenceAndDraftsApplyToTheChannel() {
        val feed = JSONArray().put(release("windows-v1.2.0-beta.2", preview = true))
            .put(release("windows-v1.1.0")).put(release("windows-v2.0.0", draft = true))
            .put(release("windows-v9.0.0-beta.1", preview = false))
        assertEquals("1.1.0", windows(feed)?.displayVersion)
        assertEquals("9.0.0-beta.1", windows(feed, true)?.displayVersion)
    }

    @Test fun versionsAreOrderedNumericallyRegardlessOfPublicationOrder() {
        val feed = JSONArray().put(release("windows-v1.9.0")).put(release("windows-v1.10.0"))
            .put(release("windows-v1.2.0")).put(release("windows-v1.10.0-beta.10", preview = true))
        assertEquals("1.10.0", windows(feed, true)?.displayVersion)
        val beta = JSONArray().put(release("windows-v1.0.0-beta.2", preview = true))
            .put(release("windows-v1.0.0-beta.10", preview = true))
        assertEquals("1.0.0-beta.10", windows(beta, true)?.displayVersion)
    }

    @Test fun theInstalledVersionAndOlderOnesAreNeverOffered() {
        val release = windows(JSONArray().put(release("windows-v1.0.0")))
        assertNull(DesktopReleases.newerThan(release, "1.0.0"))
        assertNull(DesktopReleases.newerThan(release, "1.1.0"))
        assertEquals("${DesktopReleaseCheck.REPOSITORY}/releases/tag/windows-v1.0.0", DesktopReleases.newerThan(release, "0.9.0")?.pageUrl)
        assertNull(DesktopReleases.newerThan(null, "0.9.0"))
    }

    @Test fun malformedTagsAndForeignReleaseLinksAreIgnored() {
        val feed = JSONArray().put(release("windows-vnot-a-version"))
            .put(release("windows-v01.0.0"))
            .put(release("windows-v2.0.0").put("html_url", "https://example.com/phone-update"))
            .put(release("android-v8.0.0"))
        assertNull(windows(feed, true))
    }

    @Test fun releaseNotesBecomePlainText() {
        val release = assertNotNull(windows(JSONArray().put(release("windows-v1.0.0"))))
        assertEquals("Fixes\n• Faster start", DesktopReleases.plainNotes(release.notes))
        assertEquals("See notes", DesktopReleases.plainNotes("See [notes](https://github.com/x)"))
    }

    @Test fun bundledDesktopVersionIsAvailableOutsideTheInstalledLauncher() {
        val properties = java.util.Properties()
        assertNotNull(javaClass.classLoader.getResourceAsStream("windows-version.properties")).use(properties::load)
        assertNotNull(Version.parse(properties.getProperty("version")))
    }
}
