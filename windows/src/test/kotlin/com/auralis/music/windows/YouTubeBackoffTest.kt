package com.auralis.music.windows

import com.auralis.music.data.network.YouTubeBackoff
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YouTubeBackoffTest {
    @Test
    fun recognisesTheNotABotRefusalAsABlock() {
        // NewPipe's SignInConfirmNotBotException message, as logged when YouTube flagged the network.
        assertTrue(YouTubeBackoff.isBlockPage("YouTube probably temporarily blocked anonymous watch access with this IP , " +
            "got error LOGIN_REQUIRED: \"Sign in to confirm that you're not a bot\""))
        assertTrue(YouTubeBackoff.isBlockPage("<html><title>Sorry...</title></html>"))
    }

    @Test
    fun otherFailuresAreNotBlocks() {
        assertFalse(YouTubeBackoff.isBlockPage("Sign in to confirm your age"))
        assertFalse(YouTubeBackoff.isBlockPage("Video unavailable"))
        assertFalse(YouTubeBackoff.isBlockPage(""))
    }
}
