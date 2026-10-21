package com.auralis.music.windows.settings

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*

class WindowsReleasesTest {
    private fun release(tag: String, asset: String = "Auralis-1.0.0.msi", preview: Boolean = false, draft: Boolean = false) = JSONObject()
        .put("tag_name",tag).put("prerelease",preview).put("draft",draft)
        .put("html_url", "${DesktopReleaseCheck.REPOSITORY}/releases/tag/$tag")
        .put("assets",JSONArray().put(JSONObject().put("name",asset).put("state","uploaded").put("size",12345)))

    @Test fun androidReleasesNeverCountAsWindowsUpdates() {
        val phone = JSONArray().put(release("v1.1.1-rebuilt","Auralis-v1.1.1-universal.apk"))
            .put(release("v99.0.0","Auralis-universal.apk"))
        assertNull(WindowsReleases.latest(phone,true))
        assertNull(DesktopReleaseCheck.result(phone,true,"1.0.0").second)
        val mixed = phone.put(release("windows-v1.0.1"))
        assertEquals("1.0.1",assertNotNull(WindowsReleases.latest(mixed,false)).displayVersion)
    }
    @Test fun aWindowsTagAlsoRequiresAnUploadedWindowsInstaller() {
        for (asset in listOf("Auralis.apk","Auralis.dmg","Auralis.AppImage","SHA256SUMS.txt","dependency.exe"))
            assertNull(WindowsReleases.latest(JSONArray().put(release("windows-v5.0.0",asset)),true),asset)
        val empty = release("windows-v5.0.0")
        empty.getJSONArray("assets").getJSONObject(0).put("size",0)
        assertNull(WindowsReleases.latest(JSONArray().put(empty),true))
        empty.getJSONArray("assets").getJSONObject(0).put("size",1).put("state","open")
        assertNull(WindowsReleases.latest(JSONArray().put(empty),true))
        for (asset in listOf("Auralis-1.0.0.msi","AuralisSetup.exe"))
            assertNotNull(WindowsReleases.latest(JSONArray().put(release("windows-v1.0.0",asset)),false))
    }
    @Test fun previewPreferenceAndDraftsApplyToTheWindowsChannelOnly() {
        val feed = JSONArray().put(release("windows-v1.2.0-beta.2",preview=true))
            .put(release("windows-v1.1.0")).put(release("windows-v2.0.0",draft=true))
            .put(release("windows-v9.0.0-beta.1",preview=false))
        assertEquals("1.1.0",WindowsReleases.latest(feed,false)?.displayVersion)
        assertEquals("9.0.0-beta.1",WindowsReleases.latest(feed,true)?.displayVersion)
    }
    @Test fun versionsAreOrderedNumericallyRegardlessOfPublicationOrder() {
        val feed = JSONArray().put(release("windows-v1.9.0")).put(release("windows-v1.10.0"))
            .put(release("windows-v1.2.0")).put(release("windows-v1.10.0-beta.10",preview=true))
        assertEquals("1.10.0",WindowsReleases.latest(feed,true)?.displayVersion)
        val beta = JSONArray().put(release("windows-v1.0.0-beta.2",preview=true))
            .put(release("windows-v1.0.0-beta.10",preview=true))
        assertEquals("1.0.0-beta.10",WindowsReleases.latest(beta,true)?.displayVersion)
    }
    @Test fun equalAndOlderReleasesDoNotOfferAnUpdateOrInstallerLink() {
        val feed = JSONArray().put(release("windows-v1.0.0"))
        assertNull(DesktopReleaseCheck.result(feed,false,"1.0.0").second)
        assertNull(DesktopReleaseCheck.result(feed,false,"1.1.0").second)
        assertEquals("${DesktopReleaseCheck.REPOSITORY}/releases/tag/windows-v1.0.0",
            DesktopReleaseCheck.result(feed,false,"0.9.0").second)
    }
    @Test fun malformedTagsAndForeignReleaseLinksAreIgnored() {
        val feed = JSONArray().put(release("windows-vnot-a-version"))
            .put(release("windows-v01.0.0"))
            .put(release("windows-v2.0.0").put("html_url","https://example.com/phone-update"))
            .put(release("android-v8.0.0"))
        assertNull(WindowsReleases.latest(feed,true))
    }
    @Test fun bundledWindowsVersionIsAvailableOutsideTheInstalledLauncher() {
        val properties = java.util.Properties()
        assertNotNull(javaClass.classLoader.getResourceAsStream("windows-version.properties")).use(properties::load)
        assertNotNull(WindowsReleases.Version.parse(properties.getProperty("version")))
    }
}
