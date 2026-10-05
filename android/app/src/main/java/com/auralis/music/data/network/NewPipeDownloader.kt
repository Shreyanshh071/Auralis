package com.auralis.music.data.network

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class NewPipeDownloader internal constructor(
    private val client: OkHttpClient
) : Downloader() {

    companion object {
        private val requestDeadlineNs = ThreadLocal<Long>()

        // NewPipe is synchronous. Coroutine timeouts cannot stop its blocking HTTP
        // calls; share one deadline across every request in a single extraction.
        internal fun <T> withRequestBudget(timeoutMs: Long, block: () -> T): T {
            val previous = requestDeadlineNs.get()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            requestDeadlineNs.set(previous?.let { minOf(it, deadline) } ?: deadline)
            return try { block() } finally {
                if (previous == null) requestDeadlineNs.remove() else requestDeadlineNs.set(previous)
            }
        }

        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        private var cacheDir: java.io.File? = null

        fun init(cacheDirectory: java.io.File) {
            cacheDir = cacheDirectory
        }

        private val cookieMap = ConcurrentHashMap<String, ConcurrentHashMap<String, okhttp3.Cookie>>()

        // In-memory token cache for visitor_id (10-minute TTL) to eliminate redundant round-trips
        private val tokenCache = ConcurrentHashMap<String, Pair<Long, Response>>()
        private const val TOKEN_CACHE_TTL_MS = 600_000L // 10 minutes

        // Minimal valid JSON body for next endpoint to satisfy NewPipe's parser without downloading 450KB of comments
        private const val DUMMY_NEXT_JSON = "{\"responseContext\":{},\"contents\":{\"twoColumnWatchNextResults\":{\"results\":{\"results\":{\"contents\":[]}}}}}"

        fun clearTokenCache() {
            tokenCache.clear()
        }

        val instance: NewPipeDownloader by lazy {
            val builder = OkHttpClient.Builder().proxyAuthenticator(com.auralis.music.data.network.ContentProxy.authenticator)
                .connectionPool(NetworkClientProvider.okHttpClient.connectionPool)
                .dispatcher(NetworkClientProvider.okHttpClient.dispatcher)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .retryOnConnectionFailure(true)
                .cookieJar(object : okhttp3.CookieJar {
                    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                        val hostMap = cookieMap.computeIfAbsent(url.host) { ConcurrentHashMap() }
                        cookies.forEach { hostMap[it.name] = it }
                    }

                    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
                        val list = mutableListOf<okhttp3.Cookie>()
                        cookieMap.forEach { (host, cookies) ->
                            if (url.host.endsWith(host) || host.endsWith(url.host)) {
                                list.addAll(cookies.values)
                            }
                        }
                        return list
                    }
                })

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
        val remainingNs = deadline?.minus(System.nanoTime())
        if (Thread.currentThread().isInterrupted || (remainingNs != null && remainingNs <= 0)) {
            throw java.io.InterruptedIOException("Audio extraction deadline reached")
        }
        val url = request.url()

        // 1. Bypass heavy 'next' endpoint (comments, recommendations) - saves ~1.15s and 450KB payload
        if (url.contains("/youtubei/v1/next")) {
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
        return call.execute().use { okResponse ->
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
    }
}
