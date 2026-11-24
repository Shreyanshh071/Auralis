package com.auralis.music

import com.auralis.music.data.network.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateReleasePickTest {
    private fun asset(name: String, state: String = "uploaded") =
        """{"name":"$name","state":"$state","size":1000,"browser_download_url":"https://github.com/Shreyanshh071/Auralis/releases/download/x/$name"}"""

    private fun release(tag: String, vararg assets: String, draft: Boolean = false, pre: Boolean = false) =
        """{"tag_name":"$tag","name":"$tag","draft":$draft,"prerelease":$pre,"body":"notes",""" +
            """"html_url":"https://github.com/Shreyanshh071/Auralis/releases/tag/$tag","assets":[${assets.joinToString(",")}]}"""

    private fun pick(vararg releases: String) = UpdateChecker.latestPhoneRelease("[${releases.joinToString(",")}]")

    @Test fun `a newer Windows release isn't offered to phones`() {
        val latest = pick(
            release("windows-v1.2.0", asset("Auralis-1.2.0.msi")),
            release("v1.1.4", asset("Auralis-1.1.4.apk"))
        )
        assertEquals("1.1.4", latest?.version)
        assertEquals("https://github.com/Shreyanshh071/Auralis/releases/download/x/Auralis-1.1.4.apk", latest?.apkUrl)
    }

    @Test fun `macOS releases aren't offered to phones`() {
        assertEquals("1.1.4", pick(release("v1.2.0-macos", asset("Auralis-1.2.0.dmg")), release("v1.1.4", asset("Auralis-1.1.4.apk")))?.version)
    }

    @Test fun `a release whose APK isn't uploaded yet waits`() {
        val latest = pick(
            release("v1.1.5", asset("Auralis-1.1.5.apk", state = "starter")),
            release("v1.1.6"),
            release("v1.1.4", asset("Auralis-1.1.4.apk"))
        )
        assertEquals("1.1.4", latest?.version)
    }

    @Test fun `drafts and prereleases are skipped`() {
        val latest = pick(
            release("v1.2.0", asset("Auralis-1.2.0.apk"), draft = true),
            release("v1.1.9", asset("Auralis-1.1.9.apk"), pre = true),
            release("v1.1.4", asset("Auralis-1.1.4.apk"))
        )
        assertEquals("1.1.4", latest?.version)
    }

    @Test fun `the highest version wins whatever the feed order`() {
        val latest = pick(
            release("v1.1.3.3", asset("Auralis-1.1.3.3.apk")),
            release("v1.1.4", asset("Auralis-1.1.4.apk")),
            release("v1.1.3", asset("Auralis-1.1.3.apk"))
        )
        assertEquals("1.1.4", latest?.version)
    }

    @Test fun `a feed with only desktop releases offers nothing`() {
        assertNull(pick(release("windows-v1.1.4", asset("Auralis-1.1.4.msi")), release("v1.0.1-macos", asset("Auralis.dmg"))))
    }
}
