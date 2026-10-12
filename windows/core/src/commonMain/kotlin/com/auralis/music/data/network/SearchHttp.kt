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

/** Opening an artist is an explicit foreground action, independent of import/search backoff. */
internal class ArtistBrowseRequest private constructor() : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ArtistBrowseRequest> {
        suspend fun <T> run(block: suspend () -> T): T = withContext(ArtistBrowseRequest()) { block() }
    }
}

/** Bounds the entire request, closes every response, and cancels HTTP when the query changes. */
suspend fun OkHttpClient.searchBody(request: Request, budgetMs: Long): String =
    withTimeout(budgetMs) {
        val youtube = request.url.host.contains("youtube")
        if (youtube && YouTubeBackoff.active && currentCoroutineContext()[ArtistBrowseRequest] == null)
            throw IOException("YouTube is temporarily limiting this network")
        suspendCancellableCoroutine { continuation ->
            val call = newCall(request)
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
                                throw IOException("Search HTTP ${it.code} " + error.replace(Regex("\\s+"), " ").take(200))
                            }
                            it.body?.string() ?: throw IOException("Empty search response")
                        }
                        continuation.resume(body)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            })
        }
    }
