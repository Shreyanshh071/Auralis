package com.auralis.music

import com.auralis.music.data.network.discord.discordArtworkUrl
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class DiscordArtworkTest {
    @Test fun letterboxedYoutubeArtUsesPlayerSourceAndSquareCrop() {
        val url = discordArtworkUrl("https://i.ytimg.com/vi/jNQXAC9IVRw/hqdefault.jpg", "jNQXAC9IVRw")
        assertTrue(URLDecoder.decode(url, "UTF-8").contains("/vi/jNQXAC9IVRw/hq720.jpg"))
        assertTrue(url.contains("&w=600&h=600&fit=cover"))
        assertTrue("Discord accepts image URLs up to 300 characters", url.length <= 300)
        assertFalse(url.contains("hqdefault"))
    }

    @Test fun catalogCoverKeepsThePlayersHighResolutionAlbum() {
        assertEquals("https://lh3.googleusercontent.com/album=w1200-h1200-l90-rj",
            discordArtworkUrl("//lh3.googleusercontent.com/album=w100-h100-l90-rj", "jNQXAC9IVRw"))
        assertEquals("https://i.scdn.co/image/ab67616d0000b273abcdef",
            discordArtworkUrl("https://i.scdn.co/image/ab67616d00004851abcdef", "sp_song"))
    }

    @Test fun missingHdArtworkFallsBackToSquareWithoutLetterboxPadding() {
        val url = discordArtworkUrl("https://i.ytimg.com/vi/jNQXAC9IVRw/hqdefault.jpg", "jNQXAC9IVRw")
        val fallback = URLDecoder.decode(url.substringAfter("&default="), "UTF-8")
        assertTrue(fallback.startsWith("https://wsrv.nl/"))
        assertTrue(URLDecoder.decode(fallback, "UTF-8").contains("/jNQXAC9IVRw/mqdefault.jpg"))
        assertTrue(fallback.contains("&w=600&h=600&fit=cover"))
    }

    @Test fun queryParametersArePreservedInsideProxyUrl() {
        val source = "https://example.com/cover.jpg?album=1&size=800"
        val url = discordArtworkUrl(source, "local_song")
        assertEquals(source, URLDecoder.decode(url.substringAfter("?url=").substringBefore('&'), "UTF-8"))
    }

    @Test fun localAndHiddenArtworkDoNotBecomeUnrelatedYoutubeImages() {
        assertEquals("", discordArtworkUrl("file:///cover.jpg", "jNQXAC9IVRw"))
        assertEquals("", discordArtworkUrl("", "sp_imported_song"))
        assertEquals("", discordArtworkUrl("hidden", "jNQXAC9IVRw"))
        assertTrue(discordArtworkUrl("", "jNQXAC9IVRw").contains("fit=cover"))
    }
}
