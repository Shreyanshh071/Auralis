package com.auralis.music.data.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Bounds the entire request, closes every response, and cancels HTTP when the query changes. */
internal suspend fun OkHttpClient.searchBody(request: Request, budgetMs: Long): String =
    withTimeout(budgetMs) {
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
                            if (!it.isSuccessful) throw IOException("Search HTTP ${it.code}")
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
