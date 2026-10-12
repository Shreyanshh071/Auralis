package com.auralis.music.windows.wrapped


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
    // Resource ids, not text: this list lives for the whole process; the text must follow the app language.
    private class Entry(val range: LongRange, val tease: String, val reveal: String)

    // "**" marks bold spans; %d is the minute count.
    private val entries = listOf(
        Entry(0L..999L, "wrapped_tease_1", "wrapped_reveal_1"),
        Entry(0L..999L, "wrapped_tease_2", "wrapped_reveal_2"),
        Entry(0L..999L, "wrapped_tease_3", "wrapped_reveal_3"),
        Entry(0L..999L, "wrapped_tease_4", "wrapped_reveal_4"),
        Entry(1000L..4999L, "wrapped_tease_5", "wrapped_reveal_5"),
        Entry(1000L..4999L, "wrapped_tease_6", "wrapped_reveal_6"),
        Entry(1000L..4999L, "wrapped_tease_7", "wrapped_reveal_7"),
        Entry(1000L..4999L, "wrapped_tease_8", "wrapped_reveal_8"),
        Entry(5000L..14999L, "wrapped_tease_9", "wrapped_reveal_9"),
        Entry(5000L..14999L, "wrapped_tease_10", "wrapped_reveal_10"),
        Entry(5000L..14999L, "wrapped_tease_11", "wrapped_reveal_11"),
        Entry(5000L..14999L, "wrapped_tease_12", "wrapped_reveal_12"),
        Entry(15000L..39999L, "wrapped_tease_13", "wrapped_reveal_13"),
        Entry(15000L..39999L, "wrapped_tease_14", "wrapped_reveal_14"),
        Entry(15000L..39999L, "wrapped_tease_15", "wrapped_reveal_15"),
        Entry(15000L..39999L, "wrapped_tease_16", "wrapped_reveal_16"),
        Entry(40000L..Long.MAX_VALUE, "wrapped_tease_17", "wrapped_reveal_17"),
        Entry(40000L..Long.MAX_VALUE, "wrapped_tease_18", "wrapped_reveal_18"),
        Entry(40000L..Long.MAX_VALUE, "wrapped_tease_19", "wrapped_reveal_19"),
        Entry(40000L..Long.MAX_VALUE, "wrapped_tease_20", "wrapped_reveal_20")
    )

    fun pick(minutes: Long): MinutesMessage {
        val entry = entries.filter { minutes in it.range }.randomOrNull()
            ?: Entry(0L..Long.MAX_VALUE, "looks_like_we_lost_count", "but_you_definitely_listened_to_d_minutes")
        return MinutesMessage(str(entry.tease), str(entry.reveal).format(minutes))
    }
}
