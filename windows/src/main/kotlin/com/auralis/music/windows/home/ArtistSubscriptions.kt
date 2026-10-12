package com.auralis.music.windows.home

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.auralis.music.data.network.YouTubeSession
import com.auralis.music.windows.profile.YouTubeMusicClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * YouTube subscriptions for artists, through the signed-in account (Li Music's subscribe): the
 * state is read off each artist page and toggled with YouTube's own subscribe write. Signed out
 * there is no state and nothing to toggle.
 */
internal class ArtistSubscriptions {
    /** Browse id -> the channel the subscribe button acts on, and whether it's subscribed. */
    private val states = mutableStateMapOf<String, Pair<String, Boolean>>()
    private val busyId = mutableStateOf<String?>(null)
    private val mutex = Mutex()
    private var client: YouTubeMusicClient? = null
    private var clientKey: String? = null

    fun subscribed(browseId: String): Boolean? = states[browseId]?.second
    fun busy(browseId: String) = busyId.value == browseId

    private suspend fun api(): YouTubeMusicClient = mutex.withLock {
        val headers = YouTubeSession.authHeaders(YouTubeMusicClient.ORIGIN)
        val key = headers["Cookie"].orEmpty() + "|" + headers["X-Goog-AuthUser"]
        client?.takeIf { clientKey == key } ?: YouTubeMusicClient(headers["Cookie"].orEmpty(), headers["X-Goog-AuthUser"] ?: "0")
            .also { it.resolveScope(); client = it; clientKey = key; states.clear() }
    }

    /** Reads the subscribe state for artists not read yet. Signed out, forgets everything. */
    suspend fun load(browseIds: List<String>) {
        if (!YouTubeSession.isSignedIn) { states.clear(); client = null; clientKey = null; return }
        val api = runCatching { api() }.getOrNull() ?: return
        browseIds.filter { it.startsWith("UC") && it !in states }.forEach { id ->
            try { api.artistSubscription(id)?.let { states[id] = it } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
        }
    }

    /** Flips the subscription; returns the message to show. */
    suspend fun toggle(browseId: String, name: String): String {
        if (!YouTubeSession.isSignedIn) return "Sign in with YouTube to subscribe to artists"
        if (!browseId.startsWith("UC")) return "Couldn't find $name on YouTube Music"
        if (busyId.value != null) return ""
        busyId.value = browseId
        return try {
            val api = api()
            val (channel, current) = states[browseId] ?: api.artistSubscription(browseId) ?: (browseId to false)
            val next = !current
            states[browseId] = channel to next // optimistic, like Li Music
            try {
                api.setSubscribed(channel, next)
                if (next) "Subscribed to $name" else "Unsubscribed from $name"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                states[browseId] = channel to current
                e.message ?: "Couldn't update your subscription. Try again."
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { e.message ?: "Couldn't reach YouTube. Try again." }
        finally { busyId.value = null }
    }
}
