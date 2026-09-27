// Ported from Metrolist (github.com/MetrolistGroup/Metrolist), which adapted it from NewPipe
// (github.com/TeamNewPipe/NewPipe). GPL-3.0, like Auralis.
package com.auralis.music.data.network.potoken

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Mints YouTube proof-of-origin ("PO") tokens by running YouTube's own BotGuard script in a
 * hidden, network-blocked WebView ([PoTokenWebView]). YouTube's web clients only hand over a
 * downloadable stream with them:
 * - the **streaming** token, bound to the session (the signed-in account's Data Sync ID, or the
 *   visitor ID), goes on the stream URL as `pot=`;
 * - the **player** token, bound to the video ID, goes in the player request.
 */
object PoTokenGenerator {
    private const val TAG = "PoTokenGenerator"

    // Cold start (WebView + BotGuard + first mint) is a few seconds; this is the whole budget.
    private const val POTOKEN_TIMEOUT_MS = 20_000L

    private var appContext: Context? = null
    private val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }
    @Volatile private var webViewBadImpl = false

    private val lock = Mutex()
    private var sessionId: String? = null
    private var streamingPot: String? = null
    private var generator: PoTokenWebView? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Tokens for [videoId] under [sessionId], or null when the WebView can't produce them. */
    suspend fun getTokens(videoId: String, sessionId: String): PoTokenResult? {
        val context = appContext ?: return null
        if (!webViewSupported || webViewBadImpl) return null
        return try {
            withTimeout(POTOKEN_TIMEOUT_MS) { mint(context, videoId, sessionId, forceRecreate = false) }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "PO token generation timed out after ${POTOKEN_TIMEOUT_MS}ms")
            clear()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: BadWebViewException) {
            Log.e(TAG, "This WebView can't run BotGuard: ${e.message}")
            webViewBadImpl = true
            null
        } catch (e: Exception) {
            Log.e(TAG, "PO token generation failed: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    private suspend fun clear() {
        lock.withLock {
            runCatching { withContext(Dispatchers.Main) { generator?.close() } }
            generator = null
            streamingPot = null
            sessionId = null
        }
    }

    private suspend fun mint(context: Context, videoId: String, session: String, forceRecreate: Boolean): PoTokenResult {
        val (current, sessionPot, recreated) = lock.withLock {
            val existing = generator
            val recreate = forceRecreate || existing == null || existing.isExpired || existing.isDead || sessionId != session
            if (recreate) {
                withContext(Dispatchers.Main) { generator?.close() }
                // Cleared first so a failure below can't pair the new session with a stale token.
                generator = null
                streamingPot = null
                sessionId = null

                val fresh = PoTokenWebView.getNewPoTokenGenerator(context)
                // The session-bound token has to be minted once, before any per-video token.
                val pot = try {
                    fresh.generatePoToken(session)
                } catch (t: Throwable) {
                    runCatching { fresh.close() }
                    throw t
                }
                generator = fresh
                streamingPot = pot
                sessionId = session
            }
            Triple(generator!!, streamingPot!!, recreate)
        }

        val videoPot = try {
            current.generatePoToken(videoId)
        } catch (t: Throwable) {
            // A WebView that just got recreated failing again is not going to recover.
            if (recreated) throw t
            Log.w(TAG, "PO token mint failed; recreating the WebView")
            return mint(context, videoId, session, forceRecreate = true)
        }
        return PoTokenResult(playerRequestPoToken = videoPot, streamingDataPoToken = sessionPot)
    }
}
