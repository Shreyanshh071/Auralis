package com.auralis.music.data.repository

import com.auralis.music.domain.model.Track

// "(Hyper Mix)", "[Remix By Dj Shiva]", "(Club Mix)", "- DJ Khushi Remix": a remix/mix tag in
// brackets or after a dash.
private val REMIX_TAG = Regex("""(?i)[\(\[][^\)\]]*\b(?:re-?mix|mix|dj)\b[^\)\]]*[\)\]]|\s-\s[^-]*\b(?:re-?mix|mix)\b.*$""")
private val NON_WORD = Regex("""[^\p{L}\p{Nd}]+""")

private fun baseSongKey(title: String): String =
    REMIX_TAG.replace(title, " ").replace(Regex("""[\(\[][^\)\]]*[\)\]]"""), " ")
        .lowercase().replace(NON_WORD, " ").trim()

/**
 * Drops remixes of songs the album already has. Seen live: YouTube Music's own "Housefull 2"
 * album lists four DJ remixes ("Anarkali Disco Chali (Hyper Mix)[Remix By Dj Shiva]", "Right Now
 * Now (Remix By Dj Khushi)", ...) after the four film songs. A remix whose original isn't on the
 * album (a remix single, a remix-only release) is kept, since then it *is* the album's song.
 */
internal fun withoutRemixesOfAlbumSongs(tracks: List<Track>): List<Track> {
    val originals = tracks.filterNot { REMIX_TAG.containsMatchIn(it.title) }
        .map { baseSongKey(it.title) }
        .filter { it.isNotBlank() }
        .toSet()
    if (originals.isEmpty()) return tracks
    return tracks.filterNot { REMIX_TAG.containsMatchIn(it.title) && baseSongKey(it.title) in originals }
}
