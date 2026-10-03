package com.auralis.music.ui.screens.wrapped

import com.auralis.music.domain.model.AlbumStat
import com.auralis.music.domain.model.ArtistStat
import com.auralis.music.domain.model.SongStat

data class WrappedState(
    val year: Int = 0,
    val isLoading: Boolean = true,
    val totalMinutes: Long = 0,
    val uniqueSongCount: Int = 0,
    val uniqueAlbumCount: Int = 0,
    val uniqueArtistCount: Int = 0,
    val topSongs: List<SongStat> = emptyList(),
    val topAlbums: List<AlbumStat> = emptyList(),
    val topArtists: List<ArtistStat> = emptyList()
) {
    val hasData: Boolean get() = topSongs.isNotEmpty()
}

/** A teaser line shown before the minutes reveal, and the line shown under the number. */
data class MinutesMessage(val tease: String, val reveal: String)

object WrappedMessages {
    private class Entry(val range: LongRange, val tease: String, val reveal: String)

    // "**" marks bold spans; %d is the minute count.
    private val entries = listOf(
        Entry(0L..999L, "I really hope you are not disappointed...", "That's **%d minutes**. Just warming up?"),
        Entry(0L..999L, "Testing the waters, are we?", "**%d minutes** is a quick dip in the musical ocean."),
        Entry(0L..999L, "Busy schedule this year?", "**%d minutes** is short, sweet, and to the point."),
        Entry(0L..999L, "Silence is golden, they say...", "But you preferred **%d minutes** of noise."),

        Entry(1000L..4999L, "It seems like you found Auralis recently...", "And you dedicated **%d minutes** to the tunes."),
        Entry(1000L..4999L, "You have a life outside of music.", "**%d minutes** is a healthy balance. We respect that."),
        Entry(1000L..4999L, "Not too quiet, not too loud.", "Just the right amount of vibes for **%d minutes**."),
        Entry(1000L..4999L, "A casual stop on your journey.", "Thanks for dropping by for **%d minutes**."),

        Entry(5000L..14999L, "Music is definitely your thing.", "**%d minutes** is a solid soundtrack for your year."),
        Entry(5000L..14999L, "We saw you here quite a bit.", "Always setting the mood for **%d minutes**."),
        Entry(5000L..14999L, "Your commute must be fun.", "**%d minutes** of melodies."),
        Entry(5000L..14999L, "Consistent. Reliable. Rhythmic.", "You know what you like, for **%d minutes**."),

        Entry(15000L..39999L, "Do you ever take your headphones off?", "**%d minutes** suggests music is your oxygen."),
        Entry(15000L..39999L, "Your battery is begging for mercy.", "But your ears absolutely love those **%d minutes**."),
        Entry(15000L..39999L, "Main Character Energy detected.", "Your life was a movie for **%d minutes**."),
        Entry(15000L..39999L, "Walking, working, sleeping...", "There was always a song playing during those **%d minutes**."),

        Entry(40000L..Long.MAX_VALUE, "Are you... okay?", "You literally lived here for **%d minutes**."),
        Entry(40000L..Long.MAX_VALUE, "We are worried about your eardrums.", "Top 1%% behavior. **%d minutes** is legendary."),
        Entry(40000L..Long.MAX_VALUE, "Silence scares you, doesn't it?", "A wall of sound, all year long, for **%d minutes**."),
        Entry(40000L..Long.MAX_VALUE, "Certified Stress Tester.", "You made the extractors work overtime for **%d minutes**.")
    )

    fun pick(minutes: Long): MinutesMessage {
        val entry = entries.filter { minutes in it.range }.randomOrNull()
            ?: Entry(0L..Long.MAX_VALUE, "Looks like we lost count!", "But you definitely listened to **%d minutes** of music.")
        return MinutesMessage(entry.tease, entry.reveal.format(minutes))
    }
}
