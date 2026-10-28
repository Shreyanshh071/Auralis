package com.auralis.music.domain.onboarding

import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recommendations.NewUserSeedProvider

/** First launch: tour → sign-in → artist picker → the app. */
object OnboardingFlow {
    enum class Step { TOUR, SIGN_IN, PICK_ARTISTS, SETTLING, APP }

    /** Fewest picks that unlock Continue. */
    const val MIN_PICKS = 3

    /** Related artists that pop in next to a pick. */
    const val RELATED_PER_PICK = 3

    /**
     * @param hasListeningData null while sign-in or account restore is still settling.
     * True means this account already has listening history, including restored cloud listens.
     */
    fun step(
        tourSeen: Boolean,
        signedIn: Boolean,
        pickerDone: Boolean,
        hasListeningData: Boolean?
    ): Step = when {
        !signedIn -> Step.SIGN_IN
        pickerDone -> Step.APP
        hasListeningData == null -> Step.SETTLING
        hasListeningData -> Step.APP
        !tourSeen -> Step.TOUR
        else -> Step.PICK_ARTISTS
    }

    /**
     * Inserts [related] right after [picked] in [shown], skipping artists already there,
     * so the bubbles grow around the tap.
     */
    fun insertRelated(shown: List<Artist>, picked: Artist, related: List<Artist>): List<Artist> {
        val index = shown.indexOfFirst { sameArtist(it, picked) }
        if (index < 0) return shown
        val fresh = related
            .filter { r -> r.name.isNotBlank() && shown.none { sameArtist(it, r) } }
            .distinctBy { key(it) }
            .take(RELATED_PER_PICK)
        if (fresh.isEmpty()) return shown
        return shown.take(index + 1) + fresh + shown.drop(index + 1)
    }

    /** Picks in tap order, without the same artist twice. */
    fun togglePick(picks: List<Artist>, artist: Artist): List<Artist> =
        if (picks.any { sameArtist(it, artist) }) picks.filterNot { sameArtist(it, artist) }
        else picks + artist

    /**
     * The new-user song pool: each picked artist's top songs, round-robin so one artist
     * never fills a row, duplicates removed.
     */
    fun seedTracks(topSongsByArtist: List<Pair<Artist, List<Track>>>, perArtist: Int = 4, maxTotal: Int = 27): List<Track> {
        val groups = LinkedHashMap<String, List<Track>>()
        for ((artist, songs) in topSongsByArtist) {
            if (songs.isNotEmpty()) groups[key(artist)] = songs.take(perArtist)
        }
        return NewUserSeedProvider.interleaveTracks(groups, maxTotal = maxTotal)
    }

    fun sameArtist(a: Artist, b: Artist): Boolean =
        (a.id.isNotBlank() && a.id == b.id) || key(a) == key(b)

    private fun key(a: Artist): String = a.name.trim().lowercase()

    /** Keep regional discovery and international suggestions visible together. */
    fun suggestions(regional: List<Artist>, international: List<Artist>): List<Artist> {
        val result = mutableListOf<Artist>()
        for (index in 0 until maxOf(regional.size, international.size)) {
            listOfNotNull(regional.getOrNull(index), international.getOrNull(index)).forEach { artist ->
                if (result.none { sameArtist(it, artist) }) result.add(artist)
            }
        }
        return result
    }
}
