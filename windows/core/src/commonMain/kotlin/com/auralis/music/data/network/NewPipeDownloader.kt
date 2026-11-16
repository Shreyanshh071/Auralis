package com.auralis.music.data.network

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class NewPipeDownloader(
    private val client: OkHttpClient
) : Downloader() {

    /**
     * Cancels the HTTP calls of one extraction. NewPipe is synchronous: coroutine cancellation and
     * thread interruption alone don't stop a blocked socket read, so a canceled extraction kept
     * its request going (and its slot) until the read timed out.
     */
    class CallHandle {
        private val calls = ConcurrentHashMap.newKeySet<okhttp3.Call>()
        @Volatile var canceled = false
            private set
        @Volatile internal var completed = false

        fun cancel() {
            canceled = true
            calls.forEach { it.cancel() }
        }

        internal fun register(call: okhttp3.Call) {
            calls += call
            if (canceled) call.cancel()
        }

        internal fun unregister(call: okhttp3.Call) { calls -= call }
    }

    companion object {
        private val requestDeadlineNs = ThreadLocal<Long>()
        private val requestHandle = ThreadLocal<CallHandle?>()

        // NewPipe is synchronous. Coroutine timeouts cannot stop its blocking HTTP
        // calls; share one deadline across every request in a single extraction.
        fun <T> withRequestBudget(timeoutMs: Long, handle: CallHandle? = null, block: () -> T): T {
            val previous = requestDeadlineNs.get()
            val previousHandle = requestHandle.get()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            requestDeadlineNs.set(previous?.let { minOf(it, deadline) } ?: deadline)
            if (handle != null) requestHandle.set(handle)
            return try { block() } finally {
                if (previous == null) requestDeadlineNs.remove() else requestDeadlineNs.set(previous)
                if (previousHandle == null) requestHandle.remove() else requestHandle.set(previousHandle)
            }
        }

        /**
         * Runs a blocking NewPipe [block] on an IO thread so that canceling the caller cancels its
         * in-flight HTTP calls at once, under a [timeoutMs] deadline, with its requests attributed
         * to [tag] (and charged to the tag's budget).
         */
        suspend fun <T> cancellable(timeoutMs: Long, tag: YouTubeTraffic.Tag? = null, block: () -> T): T = coroutineScope {
            val handle = CallHandle()
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { if (!handle.completed) handle.cancel() }
            }
            try {
                runInterruptible(Dispatchers.IO) {
                    withRequestBudget(timeoutMs, handle) { if (tag != null) YouTubeTraffic.withTag(tag, block) else block() }
                }
            } catch (e: Throwable) {
                // The aborted call's IOException is the cancellation, not a network failure to count.
                if (handle.canceled && e !is kotlinx.coroutines.CancellationException)
                    throw kotlinx.coroutines.CancellationException("Extraction canceled").apply { initCause(e) }
                throw e
            } finally {
                handle.completed = true
                watcher.cancel()
            }
        }

        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        private var cacheDir: java.io.File? = null

        fun init(cacheDirectory: java.io.File) {
            cacheDir = cacheDirectory
        }

        private val cookieJar = SessionCookieJar()

        // In-memory token cache for visitor_id (10-minute TTL) to eliminate redundant round-trips
        private val tokenCache = ConcurrentHashMap<String, Pair<Long, Response>>()
        private const val TOKEN_CACHE_TTL_MS = 600_000L // 10 minutes

        // Minimal valid JSON body for next endpoint to satisfy NewPipe's parser without downloading 450KB of comments
        private const val DUMMY_NEXT_JSON = "{\"responseContext\":{},\"contents\":{\"twoColumnWatchNextResults\":{\"results\":{\"results\":{\"contents\":[]}}}}}"

        /**
         * The anonymous visitor session's generation. Work that started under an older one must not
         * publish results into a newer session's caches.
         */
        @Volatile var sessionGeneration = 0L
            private set

        fun clearTokenCache() {
            tokenCache.clear()
        }

        /**
         * Forgets YouTube's visitor session (its cookies and visitor_id), so the next extraction
         * starts a new one. Only for a classified reason (a session whose media is refused again
         * after a fresh link, say): a reset breaks every other lookup sharing the session.
         */
        fun resetSession(reason: String) {
            cookieJar.clear()
            tokenCache.clear()
            sessionGeneration++
            com.auralis.music.util.Log.i("NewPipeDownloader", "New visitor session (generation $sessionGeneration): $reason")
        }

        val instance: NewPipeDownloader by lazy {
            val builder = OkHttpClient.Builder().proxyAuthenticator(com.auralis.music.data.network.ContentProxy.authenticator)
                .connectionPool(NetworkClientProvider.okHttpClient.connectionPool)
                .dispatcher(NetworkClientProvider.okHttpClient.dispatcher)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .retryOnConnectionFailure(true)
                .addInterceptor(YouTubeTraffic.gate)
                .addNetworkInterceptor(YouTubeTraffic.meter)
                .cookieJar(cookieJar)

            cacheDir?.let { dir ->
                try {
                    val httpCacheDir = java.io.File(dir, "newpipe_http_cache")
                    builder.cache(okhttp3.Cache(httpCacheDir, 50L * 1024L * 1024L))
                } catch (_: Exception) {}
            }

            NewPipeDownloader(builder.build())
        }
    }

    @Throws(IOException::class)
    override fun execute(request: Request): Response {
        val deadline = requestDeadlineNs.get()
        val handle = requestHandle.get()
        val remainingNs = deadline?.minus(System.nanoTime())
        if (handle?.canceled == true) throw java.io.InterruptedIOException("Extraction canceled")
        if (Thread.currentThread().isInterrupted || (remainingNs != null && remainingNs <= 0)) {
            throw java.io.InterruptedIOException("Audio extraction deadline reached")
        }
        val url = request.url()

        // 1. Bypass heavy 'next' endpoint (comments, recommendations) - saves ~1.15s and 450KB payload
        if (url.contains("/youtubei/v1/next")) {
            YouTubeTraffic.noteAvoided(YouTubeTraffic.Endpoint.NEXT)
            return Response(
                200,
                "OK",
                emptyMap(),
                DUMMY_NEXT_JSON,
                url
            )
        }

        // 2. Cache visitor_id token handshakes to eliminate 3x redundant round-trips
        if (url.contains("/youtubei/v1/visitor_id")) {
            val cached = tokenCache[url]
            if (cached != null && System.currentTimeMillis() - cached.first < TOKEN_CACHE_TTL_MS) {
                YouTubeTraffic.noteAvoided(YouTubeTraffic.Endpoint.VISITOR)
                return cached.second
            }
        }

        val httpMethod = request.httpMethod()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val reqBuilder = okhttp3.Request.Builder()
            // Both hosts serve InnerTube. The googleapis route can stall on networks
            // where normal YouTube playback works; use the same host as the player.
            .url(url.toHttpUrl().let { endpoint ->
                if (endpoint.host == "youtubei.googleapis.com" && endpoint.encodedPath.startsWith("/youtubei/v1/"))
                    endpoint.newBuilder().host("www.youtube.com").build()
                else endpoint
            })
            .header("User-Agent", USER_AGENT)

        headers?.forEach { (name, values) ->
            reqBuilder.removeHeader(name)
            values.forEach { value ->
                reqBuilder.addHeader(name, value)
            }
        }

        if (httpMethod.equals("POST", ignoreCase = true)) {
            val body = (dataToSend ?: ByteArray(0)).toRequestBody(null)
            reqBuilder.post(body)
        } else if (httpMethod.equals("HEAD", ignoreCase = true)) {
            reqBuilder.head()
        } else {
            reqBuilder.get()
        }

        val call = client.newCall(reqBuilder.build())
        // Covers DNS, connect, retries, response headers AND the response body.
        if (deadline != null) {
            val callBudgetNs = deadline - System.nanoTime()
            if (callBudgetNs <= 0) throw java.io.InterruptedIOException("Audio extraction deadline reached")
            call.timeout().timeout(callBudgetNs, TimeUnit.NANOSECONDS)
        }
        handle?.register(call)
        try {
            return call.execute().use { okResponse ->
                if (okResponse.code == 429) {
                    // The meter already paused YouTube requests; stop this extraction too.
                    throw RateLimitedException(RetryAfter.parseMs(okResponse.header("Retry-After")))
                }
                val responseBody = okResponse.body?.string() ?: ""
                val responseHeaders = mutableMapOf<String, List<String>>()
                okResponse.headers.names().forEach { name ->
                    responseHeaders[name] = okResponse.headers.values(name)
                }

                val res = Response(
                    okResponse.code,
                    okResponse.message,
                    responseHeaders,
                    responseBody,
                    okResponse.request.url.toString()
                )

                // Save fresh visitor_id response to token cache
                if (url.contains("/youtubei/v1/visitor_id") && okResponse.code == 200) {
                    tokenCache[url] = System.currentTimeMillis() to res
                }

                res
            }
        } catch (e: IOException) {
            if (handle?.canceled == true && e !is java.io.InterruptedIOException) throw java.io.InterruptedIOException("Extraction canceled")
            throw e
        } finally {
            handle?.unregister(call)
        }
    }
}
