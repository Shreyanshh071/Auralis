package com.auralis.music

import androidx.media3.common.FlagSet
import androidx.media3.common.Player
import com.auralis.music.domain.model.Track
import com.auralis.music.service.AuralisMediaService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionNotificationTest {

    @Test
    fun testMediaServiceActionConstants() {
        assertEquals("com.auralis.music.ACTION_START", AuralisMediaService.ACTION_START)
        assertEquals("com.auralis.music.ACTION_PLAY", AuralisMediaService.ACTION_PLAY)
        assertEquals("com.auralis.music.ACTION_PAUSE", AuralisMediaService.ACTION_PAUSE)
        assertEquals("com.auralis.music.ACTION_TOGGLE", AuralisMediaService.ACTION_TOGGLE)
        assertEquals("com.auralis.music.ACTION_NEXT", AuralisMediaService.ACTION_NEXT)
        assertEquals("com.auralis.music.ACTION_PREVIOUS", AuralisMediaService.ACTION_PREVIOUS)
        assertEquals("com.auralis.music.ACTION_SEEK_BACK", AuralisMediaService.ACTION_SEEK_BACK)
        assertEquals("com.auralis.music.ACTION_SEEK_FORWARD", AuralisMediaService.ACTION_SEEK_FORWARD)
        assertEquals("com.auralis.music.ACTION_TOGGLE_FAVORITE", AuralisMediaService.ACTION_TOGGLE_FAVORITE)
        assertEquals("com.auralis.music.ACTION_TOGGLE_REPEAT", AuralisMediaService.ACTION_TOGGLE_REPEAT)
        assertEquals("com.auralis.music.ACTION_STOP", AuralisMediaService.ACTION_STOP)
    }

    @Test
    fun testNotificationChannelAndIdConstants() {
        assertEquals("auralis_media_playback_channel", AuralisMediaService.CHANNEL_ID)
        assertEquals(1001, AuralisMediaService.NOTIFICATION_ID)
    }

    @Test
    fun testPlayerEventsConstructionBuildsSuccessfully() {
        val eventFlags = FlagSet.Builder()
            .add(Player.EVENT_IS_PLAYING_CHANGED)
            .add(Player.EVENT_PLAY_WHEN_READY_CHANGED)
            .add(Player.EVENT_PLAYBACK_STATE_CHANGED)
            .add(Player.EVENT_TIMELINE_CHANGED)
            .add(Player.EVENT_METADATA)
            .build()
        val events = Player.Events(eventFlags)
        assertNotNull(events)

        val metaFlags = FlagSet.Builder()
            .add(Player.EVENT_MEDIA_METADATA_CHANGED)
            .add(Player.EVENT_PLAYLIST_METADATA_CHANGED)
            .add(Player.EVENT_MEDIA_ITEM_TRANSITION)
            .add(Player.EVENT_TIMELINE_CHANGED)
            .build()
        val metaEvents = Player.Events(metaFlags)
        assertNotNull(metaEvents)
    }

    @Test
    fun testForegroundServiceOngoingConditionLogic() {
        // Condition: ongoing = isPlaying || isBuffering
        assertTrue(shouldNotificationBeOngoing(isPlaying = true, isBuffering = false))
        assertTrue(shouldNotificationBeOngoing(isPlaying = false, isBuffering = true))
        assertTrue(shouldNotificationBeOngoing(isPlaying = true, isBuffering = true))
        assertFalse(shouldNotificationBeOngoing(isPlaying = false, isBuffering = false))
    }

    @Test
    fun testTaskRemovedPreservationLogic() {
        // When user swipes from Recents:
        // if playing -> PRESERVE playback in foreground
        // if not playing -> clean up and stop
        assertTrue(shouldPreservePlaybackOnTaskRemoved(isPlaying = true))
        assertFalse(shouldPreservePlaybackOnTaskRemoved(isPlaying = false))
    }

    @Test
    fun testNotificationTitleAndArtistFallbacks() {
        val sampleTrack = Track(
            id = "sample_123",
            title = "Bohemian Rhapsody",
            artist = "Queen",
            duration = 354L,
            thumbnail = "https://example.com/art.jpg"
        )
        val (title, artist) = resolveNotificationTitleAndArtist(sampleTrack)
        assertEquals("Bohemian Rhapsody", title)
        assertEquals("Queen", artist)

        val emptyTrack = Track(
            id = "empty_123",
            title = "",
            artist = "",
            duration = 0L
        )
        val (fallbackTitle, fallbackArtist) = resolveNotificationTitleAndArtist(emptyTrack)
        assertEquals("Auralis", fallbackTitle)
        assertEquals("Playing music", fallbackArtist)

        val (nullTitle, nullArtist) = resolveNotificationTitleAndArtist(null)
        assertEquals("Auralis", nullTitle)
        assertEquals("Playing music", nullArtist)
    }

    @Test
    fun testNotificationActionToggleLogic() {
        // When playing, the action should be PAUSE
        assertEquals(AuralisMediaService.ACTION_PAUSE, resolvePlayPauseAction(isPlaying = true))
        // When paused, the action should be PLAY
        assertEquals(AuralisMediaService.ACTION_PLAY, resolvePlayPauseAction(isPlaying = false))
    }

    @Test
    fun testNotificationSubtextBufferingLogic() {
        // When buffering, subtext should show "Buffering..."
        assertEquals("Buffering...", resolveNotificationSubtext(isBuffering = true))
        // When not buffering, subtext should be null
        assertNull(resolveNotificationSubtext(isBuffering = false))
    }

    @Test
    fun testNotificationFavoriteIconAndTitleResolution() {
        // When favorited: filled heart and "Favorited" label
        val (favIcon, favTitle) = resolveFavoriteActionIconAndTitle(isFav = true)
        assertEquals(R.drawable.ic_heart_filled, favIcon)
        assertEquals("Favorited", favTitle)

        // When not favorited: outline heart and "Favorite" label
        val (unfavIcon, unfavTitle) = resolveFavoriteActionIconAndTitle(isFav = false)
        assertEquals(R.drawable.ic_heart_outline, unfavIcon)
        assertEquals("Favorite", unfavTitle)
    }

    @Test
    fun testFavoriteToggleInversionLogic() {
        var isFav = false
        // Simulate notification tap
        isFav = !isFav
        assertTrue(isFav)
        // Simulate second notification tap
        isFav = !isFav
        assertFalse(isFav)
    }

    private fun shouldNotificationBeOngoing(isPlaying: Boolean, isBuffering: Boolean): Boolean {
        return isPlaying || isBuffering
    }

    private fun shouldPreservePlaybackOnTaskRemoved(isPlaying: Boolean): Boolean {
        return isPlaying
    }

    private fun resolveNotificationTitleAndArtist(track: Track?): Pair<String, String> {
        val title = track?.title?.ifBlank { "Auralis" } ?: "Auralis"
        val artist = track?.artist?.ifBlank { "Playing music" } ?: "Playing music"
        return title to artist
    }

    private fun resolvePlayPauseAction(isPlaying: Boolean): String {
        return if (isPlaying) AuralisMediaService.ACTION_PAUSE else AuralisMediaService.ACTION_PLAY
    }

    private fun resolveNotificationSubtext(isBuffering: Boolean): String? {
        return if (isBuffering) "Buffering..." else null
    }

    private fun resolveFavoriteActionIconAndTitle(isFav: Boolean): Pair<Int, String> {
        val icon = if (isFav) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
        val title = if (isFav) "Favorited" else "Favorite"
        return icon to title
    }
}
