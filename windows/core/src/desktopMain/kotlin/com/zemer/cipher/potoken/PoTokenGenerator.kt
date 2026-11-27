package com.zemer.cipher.potoken

import com.zemer.cipher.Timber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Desktop port of zemer-cipher's PoTokenGenerator: the same session/video token pairing, minted by
 * [PoTokenWebView] in an unseen Chromium tab. Suspending instead of runBlocking (no main thread).
 */
class PoTokenGenerator {
    private val TAG = "PoTokenGenerator"

    private val lock = Mutex()
    private var sessionId: String? = null
    // poToken bound to the session (visitorData), minted once and reused across videos.
    private var sessionPot: String? = null
    private var generator: PoTokenWebView? = null

    /** Null when tokens can't be minted now (no browser, BotGuard refused, too slow). */
    suspend fun getWebClientPoToken(videoId: String, sessionId: String): PoTokenResult? = try {
        withTimeout(POTOKEN_TIMEOUT_MS) { tokens(videoId, sessionId, forceRecreate = false) }
    } catch (e: CancellationException) {
        if (e !is TimeoutCancellationException) throw e
        Timber.tag(TAG).w("poToken generation timed out after ${POTOKEN_TIMEOUT_MS}ms")
        reset()
        null
    } catch (e: Exception) {
        Timber.tag(TAG).w("poToken generation failed: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
        reset()
        null
    }

    private suspend fun reset() = lock.withLock {
        runCatching { generator?.close() }
        generator = null
        sessionPot = null
        sessionId = null
    }

    private suspend fun tokens(videoId: String, sessionId: String, forceRecreate: Boolean): PoTokenResult {
        val (current, pot, recreated) = lock.withLock {
            val existing = generator
            val recreate = forceRecreate || existing == null || existing.isExpired || existing.isDead || this.sessionId != sessionId
            if (recreate) {
                runCatching { existing?.close() }
                generator = null
                sessionPot = null
                this.sessionId = null
                val fresh = PoTokenWebView.getNewPoTokenGenerator()
                // The session token (bound to visitorData) is minted once, before any video token.
                val freshPot = try {
                    fresh.generatePoToken(sessionId)
                } catch (t: Throwable) {
                    runCatching { fresh.close() }
                    throw t
                }
                generator = fresh
                sessionPot = freshPot
                this.sessionId = sessionId
            }
            Triple(generator!!, sessionPot!!, recreate)
        }
        val videoPot = try {
            current.generatePoToken(videoId)
        } catch (t: Throwable) {
            if (t is CancellationException || recreated) throw t
            Timber.tag(TAG).w("Video poToken failed, recreating the minter: ${t.message?.take(200)}")
            return tokens(videoId, sessionId, forceRecreate = true)
        }
        // Verified upstream against the live CDN: googlevideo serves only the first 1 MiB unless the
        // URL's pot= is bound to the VIDEO ID, so the per-video token goes on the stream URL and the
        // session token in the /player request.
        return PoTokenResult(playerRequestPoToken = pot, streamingDataPoToken = videoPot)
    }

    private companion object {
        // Cold start = Chromium launch + BotGuard round trips; warm mints are well under a second.
        const val POTOKEN_TIMEOUT_MS = 25_000L
    }
}
