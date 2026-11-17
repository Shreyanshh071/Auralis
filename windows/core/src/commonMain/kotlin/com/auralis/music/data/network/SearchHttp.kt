package com.auralis.music.data.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Interactive search is independent of optional background catalogue refreshes. */
class UserSearchRequest private constructor() : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<UserSearchRequest> {
        suspend fun <T> run(block: suspend () -> T): T = withContext(UserSearchRequest()) { block() }
    }
}

/** Opening an artist is an explicit foreground action, independent of import/search backoff. */
internal class ArtistBrowseRequest private constructor() : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ArtistBrowseRequest> {
        suspend fun <T> run(block: suspend () -> T): T = withContext(ArtistBrowseRequest()) { block() }
    }
}

/** Opening an album is a user action; background search cooldown must not hide its catalogue. */
internal class AlbumBrowseRequest private constructor() : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AlbumBrowseRequest> {
        suspend fun <T> run(block: suspend () -> T): T = withContext(AlbumBrowseRequest()) { block() }
    }
}

/** Bounds the entire request, closes every response, and cancels HTTP when the query changes. */
suspend fun OkHttpClient.searchBody(request: Request, budgetMs: Long): String =
    withTimeout(budgetMs) {
        val youtube = request.url.host.contains("youtube")
        val foreground = currentCoroutineContext()[ArtistBrowseRequest] != null ||
            currentCoroutineContext()[AlbumBrowseRequest] != null || currentCoroutineContext()[UserSearchRequest] != null
        // Background work waits out a pause, and only one request goes out as its trial; the
        // listener's own searches and pages still go (they are single requests they asked for).
        if (youtube && !foreground && !YouTubeBackoff.permit())
            throw IOException("YouTube is temporarily limiting this network")
        val intent = currentCoroutineContext()[PlaybackIntentContext]?.budget
        val taggedRequest = intent?.let {
            request.newBuilder().tag(YouTubeTraffic.Tag::class.java,
                YouTubeTraffic.Tag(it.correlationId, it.reason, it)).build()
        } ?: request
        suspendCancellableCoroutine { continuation ->
            val call = newCall(taggedRequest)
            call.timeout().timeout(budgetMs, TimeUnit.MILLISECONDS)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.use {
                            // The start of the error body says why (YouTube's 403s carry a reason).
                            if (!it.isSuccessful) {
                                val error = it.body?.string().orEmpty()
                                if (youtube && it.code == 403 && YouTubeBackoff.isBlockPage(error)) YouTubeBackoff.trip()
                                if (youtube && it.code >= 500) YouTubeBackoff.trip(PlaybackFailure(FailureCategory.TRANSIENT, "search", it.code))
                                throw IOException("Search HTTP ${it.code} " + YouTubeTraffic.redact(error.replace(Regex("\\s+"), " ").take(200)))
                            }
                            (it.body?.string() ?: throw IOException("Empty search response"))
                                .also { if (youtube) YouTubeBackoff.recordSuccess(fromPlayer = false) }
                        }
                        continuation.resume(body)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            })
        }
    }
