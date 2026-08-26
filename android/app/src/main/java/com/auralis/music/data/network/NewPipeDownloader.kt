package com.auralis.music.data.network

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class NewPipeDownloader private constructor(
    private val client: OkHttpClient
) : Downloader() {

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        private var cacheDir: java.io.File? = null

        fun init(cacheDirectory: java.io.File) {
            cacheDir = cacheDirectory
        }

        val instance: NewPipeDownloader by lazy {
            val builder = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)

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
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val reqBuilder = okhttp3.Request.Builder()
            .url(url)
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

        val okResponse = client.newCall(reqBuilder.build()).execute()
        val responseBody = okResponse.body?.string() ?: ""
        val responseHeaders = mutableMapOf<String, List<String>>()
        okResponse.headers.names().forEach { name ->
            responseHeaders[name] = okResponse.headers.values(name)
        }

        return Response(
            okResponse.code,
            okResponse.message,
            responseHeaders,
            responseBody,
            okResponse.request.url.toString()
        )
    }
}
