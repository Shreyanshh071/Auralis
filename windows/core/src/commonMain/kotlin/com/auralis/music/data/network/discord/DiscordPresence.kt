package com.auralis.music.data.network.discord

import com.auralis.music.domain.model.DiscordRpcSettings
import com.auralis.music.domain.model.Track
import kotlin.math.abs

/** One Rich Presence update with every field already chosen; an empty string leaves that field out. */
data class DiscordPresence(
    val name: String,
    val details: String,
    val state: String,
    val largeImage: String,
    val largeText: String,
    val smallImage: String,
    val smallText: String,
    val activityType: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val showWhenPaused: Boolean
)

/** What Discord shows for the playing song, from the user's Discord settings (shared by phone and desktop). */
object DiscordPresenceBuilder {
    const val AURALIS_LOGO_URL = "https://auralis-self-nu.vercel.app/logo.png"

    /** The presence for [t] with the user's Discord settings [s]; [artistPhoto] gives a known artist photo URL. */
    fun build(
        t: Track,
        s: DiscordRpcSettings,
        playing: Boolean,
        positionMs: Long,
        durationMs: Long,
        artistPhoto: (String) -> String?
    ): DiscordPresence {
        val album = t.album.orEmpty()
        fun text(choice: String): String = when (choice) {
            "Artist name" -> t.artist
            "Album name" -> album
            "Song title" -> t.title
            "Auralis" -> "Auralis"
            else -> ""
        }
        val artwork = t.thumbnail.trim().let { thumbnail ->
            when {
                thumbnail.startsWith("https://") -> thumbnail
                thumbnail.startsWith("http://") -> thumbnail.replaceFirst("http://", "https://")
                thumbnail.startsWith("//") -> "https:$thumbnail"
                t.id.isNotBlank() -> "https://i.ytimg.com/vi/${t.id}/hqdefault.jpg"
                else -> ""
            }
        }
        val largeImage = when (s.largeImage) {
            "Album artwork" -> artwork
            "App icon" -> AURALIS_LOGO_URL
            else -> ""
        }
        val largeText = when (s.largeText) {
            "Album name" -> album.ifBlank { t.title }
            "Song title" -> t.title
            "Auralis" -> "Auralis"
            else -> ""
        }
        val (smallImage, smallText) = when (s.smallImage) {
            "Artist artwork" -> artistPhoto(t.artist).orEmpty() to t.artist
            "Play state" -> AURALIS_LOGO_URL to if (playing) "Playing" else "Paused"
            "App logo" -> AURALIS_LOGO_URL to "Auralis"
            else -> "" to ""
        }
        val details = text(s.activityDetails).let { if (!playing && it.isNotBlank()) "$it [Paused]" else it }
        return DiscordPresence(
            name = s.activityName.ifBlank { "Auralis" },
            details = details,
            state = text(s.activityState),
            largeImage = largeImage,
            largeText = if (largeImage.isBlank()) "" else largeText,
            smallImage = smallImage,
            smallText = if (smallImage.isBlank()) "" else smallText,
            activityType = s.activityType,
            isPlaying = playing,
            positionMs = positionMs,
            durationMs = durationMs,
            showWhenPaused = s.showRpcWhenPaused
        )
    }

    /** The player's length [durationMs], unless it is clearly still the previous song's; then the song's own. */
    fun songLengthMs(t: Track, durationMs: Long): Long {
        val metadataMs = t.duration * 1000L
        return when {
            durationMs <= 0L -> metadataMs
            metadataMs > 0L && abs(durationMs - metadataMs) > 10_000L -> metadataMs
            else -> durationMs
        }
    }
}
