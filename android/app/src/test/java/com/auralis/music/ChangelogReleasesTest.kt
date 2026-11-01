package com.auralis.music

import com.auralis.music.data.network.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Test

class ChangelogReleasesTest {
    private fun release(tag: String, body: String = "notes", draft: Boolean = false, pre: Boolean = false) =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$pre,"published_at":"2026-11-01T07:01:04Z","body":"$body"}"""

    @Test fun `only Android releases are listed`() {
        val json = "[" + listOf(
            release("v1.1.3"),
            release("v1.0.0-preview-macos"),
            release("v1.1.4", draft = true),
            release("v1.1.5", pre = true),
            release("v1.1.1-rebuilt"),
        ).joinToString(",") + "]"
        assertEquals(listOf("1.1.3", "1.1.1"), UpdateChecker.parseReleases(json).map { it.version })
    }

    @Test fun `a four part version keeps its fourth part`() {
        assertEquals("1.1.3.1", UpdateChecker.parseReleases("[${release("v1.1.3.1")}]").single().version)
    }

    @Test fun `the GitHub download line is dropped from the notes`() {
        val json = "[${release("v1.1.3", "## Added\\n\\n- A\\n\\nDownload **Auralis.apk** below.")}]"
        assertEquals("## Added\n\n- A", UpdateChecker.parseReleases(json).single().notes)
    }

    @Test fun `the date is the published day`() {
        assertEquals("2026-11-01", UpdateChecker.parseReleases("[${release("v1.1.3")}]").single().publishedAt)
    }
}
