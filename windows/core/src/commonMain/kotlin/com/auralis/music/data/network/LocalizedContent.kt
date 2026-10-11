package com.auralis.music.data.network

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Marks a coroutine whose InnerTube results are shown to the user (search screen, artist pages,
 * home, explore, radio / up next): its requests use the content language from Settings →
 * Content (YouTube then localizes labels, counts, descriptions and some names).
 *
 * Unmarked requests keep `hl=en`. Those are Auralis's own matching lookups — Spotify → YouTube
 * resolution, artwork repair, album/lyrics matching, playlist import — which compare English
 * names and counts and would silently stop matching on localized text.
 */
class LocalizedContent private constructor() : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LocalizedContent> {
        private val element = LocalizedContent()

        /** Runs [block] with its InnerTube requests in the content language. */
        suspend fun <T> run(block: suspend () -> T): T = withContext(element) { block() }

        suspend fun isActive(): Boolean = currentCoroutineContext()[Key] != null
    }
}
