package com.auralis.music.ui.screens.wrapped

import com.auralis.music.R
import com.auralis.music.ui.i18n.str

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
    private class Entry(val range: LongRange, @androidx.annotation.StringRes val tease: Int, @androidx.annotation.StringRes val reveal: Int)

    // "**" marks bold spans; %d is the minute count.
    private val entries = listOf(
        Entry(0L..999L, R.string.wrapped_tease_1, R.string.wrapped_reveal_1),
        Entry(0L..999L, R.string.wrapped_tease_2, R.string.wrapped_reveal_2),
        Entry(0L..999L, R.string.wrapped_tease_3, R.string.wrapped_reveal_3),
        Entry(0L..999L, R.string.wrapped_tease_4, R.string.wrapped_reveal_4),
        Entry(1000L..4999L, R.string.wrapped_tease_5, R.string.wrapped_reveal_5),
        Entry(1000L..4999L, R.string.wrapped_tease_6, R.string.wrapped_reveal_6),
        Entry(1000L..4999L, R.string.wrapped_tease_7, R.string.wrapped_reveal_7),
        Entry(1000L..4999L, R.string.wrapped_tease_8, R.string.wrapped_reveal_8),
        Entry(5000L..14999L, R.string.wrapped_tease_9, R.string.wrapped_reveal_9),
        Entry(5000L..14999L, R.string.wrapped_tease_10, R.string.wrapped_reveal_10),
        Entry(5000L..14999L, R.string.wrapped_tease_11, R.string.wrapped_reveal_11),
        Entry(5000L..14999L, R.string.wrapped_tease_12, R.string.wrapped_reveal_12),
        Entry(15000L..39999L, R.string.wrapped_tease_13, R.string.wrapped_reveal_13),
        Entry(15000L..39999L, R.string.wrapped_tease_14, R.string.wrapped_reveal_14),
        Entry(15000L..39999L, R.string.wrapped_tease_15, R.string.wrapped_reveal_15),
        Entry(15000L..39999L, R.string.wrapped_tease_16, R.string.wrapped_reveal_16),
        Entry(40000L..Long.MAX_VALUE, R.string.wrapped_tease_17, R.string.wrapped_reveal_17),
        Entry(40000L..Long.MAX_VALUE, R.string.wrapped_tease_18, R.string.wrapped_reveal_18),
        Entry(40000L..Long.MAX_VALUE, R.string.wrapped_tease_19, R.string.wrapped_reveal_19),
        Entry(40000L..Long.MAX_VALUE, R.string.wrapped_tease_20, R.string.wrapped_reveal_20)
    )

    fun pick(minutes: Long): MinutesMessage {
        val entry = entries.filter { minutes in it.range }.randomOrNull()
            ?: Entry(0L..Long.MAX_VALUE, R.string.looks_like_we_lost_count, R.string.but_you_definitely_listened_to_d_minutes)
        return MinutesMessage(str(entry.tease), str(entry.reveal).format(minutes))
    }
}
