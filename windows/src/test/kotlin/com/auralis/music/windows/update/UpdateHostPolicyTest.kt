package com.auralis.music.windows.update

import kotlin.test.*

class UpdateHostPolicyTest {
    private val github = UpdateHostPolicy()

    @Test fun onlyHttpsOnGitHubAndItsAssetHostsIsAllowed() {
        for (url in listOf(
            "https://api.github.com/repos/Shreyanshh071/Auralis/releases?per_page=100",
            "https://github.com/Shreyanshh071/Auralis/releases/download/windows-v1.2.0/Auralis-1.2.0.msi",
            "https://objects.githubusercontent.com/github-production-release-asset-2e65be/1?X-Amz-Signature=x",
            "https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sp=r&sig=x",
            "https://GitHub.com:443/Shreyanshh071/Auralis",
        )) assertTrue(github.allows(url), url)
        for (url in listOf(
            "http://github.com/Shreyanshh071/Auralis/releases/download/windows-v1.2.0/Auralis-1.2.0.msi",
            "https://github.com:8443/x.msi",
            "https://github.com.evil.example/x.msi",
            "https://evilgithub.com/x.msi",
            "https://raw.githubusercontent.com/x/y/main/Auralis.msi",
            "https://user:pass@github.com/x.msi",
            "https://gist.github.com/x",
            "ftp://github.com/x.msi",
            "file:///C:/Users/x/Auralis.msi",
            "http://127.0.0.1:8080/Auralis.msi",
            "not a url",
        )) assertFalse(github.allows(url), url)
    }

    @Test fun theFeedOverrideAllowsOnlyItsOwnOriginBesideGitHub() {
        val feed = UpdateFeed.fromSystem("http://127.0.0.1:8765/releases")
        assertTrue(feed.overridden)
        assertEquals("http://127.0.0.1:8765/releases", feed.url)
        assertTrue(feed.policy.allows("http://127.0.0.1:8765/assets/Auralis-1.2.0.msi"))
        assertTrue(feed.policy.allows("https://github.com/x.msi"))
        assertFalse(feed.policy.allows("http://127.0.0.1:8766/assets/Auralis-1.2.0.msi"))
        assertFalse(feed.policy.allows("http://localhost:8765/assets/Auralis-1.2.0.msi"))
        assertFalse(feed.policy.allows("https://127.0.0.1:8765/assets/Auralis-1.2.0.msi"))
        assertTrue(UpdateFeed.fromSystem("https://updates.example.test/feed").policy.allows("https://updates.example.test/a.msi"))
    }

    @Test fun anOverrideMustBeHttpsOrOnThisComputer() {
        assertFalse(UpdateFeed.fromSystem(null).overridden)
        assertEquals(UpdateFeed.DEFAULT, UpdateFeed.fromSystem("  ").url)
        assertFailsWith<IllegalArgumentException> { UpdateFeed.fromSystem("http://updates.example.test/feed") }
        assertFailsWith<IllegalArgumentException> { UpdateFeed.fromSystem("file:///C:/feed.json") }
        assertFailsWith<IllegalArgumentException> { UpdateFeed.fromSystem("http://user:pw@127.0.0.1:1/feed") }
        assertTrue(UpdateFeed.fromSystem("http://[::1]:9000/feed").policy.allows("http://[::1]:9000/a.msi"))
    }

    @Test fun nextFeedPageComesFromTheLinkHeader() {
        val link = "<https://api.github.com/repositories/1/releases?per_page=100&page=2>; rel=\"next\", " +
            "<https://api.github.com/repositories/1/releases?per_page=100&page=4>; rel=\"last\""
        assertEquals("https://api.github.com/repositories/1/releases?per_page=100&page=2", UpdateDownloader.nextPage(link))
        assertNull(UpdateDownloader.nextPage("<https://api.github.com/x?page=1>; rel=\"prev\""))
        assertNull(UpdateDownloader.nextPage(null))
    }
}
