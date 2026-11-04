package com.auralis.music.data.download

import com.auralis.music.domain.model.Track

/** Presentation-only marker: unlike an empty URL, this must never trigger artwork discovery. */
internal object DownloadArtworkPolicy {
    const val HIDDEN = "auralis://download-artwork-disabled"
    fun display(track: Track, included: Boolean?): Track =
        if (included == false) track.copy(thumbnail = HIDDEN) else track
}
