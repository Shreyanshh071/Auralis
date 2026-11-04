package com.auralis.music.ui

/** Lets a song's menu warm up the artist page without every caller passing a callback down. */
object ArtistPreload {
    var request: ((String) -> Unit)? = null
}
