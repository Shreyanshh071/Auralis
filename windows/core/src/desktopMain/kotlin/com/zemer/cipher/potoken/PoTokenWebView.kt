package com.zemer.cipher.potoken

import com.zemer.cipher.HeadlessChromium
import com.zemer.cipher.Timber
import com.zemer.cipher.ZemerCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Desktop port of zemer-cipher's PoTokenWebView: BotGuard runs in an unseen [HeadlessChromium] tab
 * at youtube.com's origin (served from memory, no other network access) while the two BotGuard
 * service calls go through OkHttp, exactly as upstream. The name stays for the shared callers.
 */
class PoTokenWebView private constructor(private val page: HeadlessChromium.Page) {
    @Volatile private var closed = false
    private lateinit var expirationInstant: Instant

    val isDead: Boolean get() = closed || page.dead
    val isExpired: Boolean get() = !::expirationInstant.isInitialized || Instant.now().isAfter(expirationInstant)

    private suspend fun initialize() {
        if (ZemerCipher.poTokenApiKey.isBlank()) throw PoTokenException("PoToken API key is not configured for this build")
        val html = PoTokenWebView::class.java.getResourceAsStream("/zemer-cipher/po_token.html")?.bufferedReader()?.use { it.readText() }
            ?: throw PoTokenException("po_token.html missing from the build")
        page.serve(PAGE_URL, html, "text/html; charset=utf-8")
        page.navigate(PAGE_URL, INIT_TIMEOUT_MS)

        val challenge = parseChallengeData(botguardRequest("https://www.youtube.com/api/jnn/v1/Create", "[ \"$REQUEST_KEY\" ]"))
        val botguardResponse = run("""(function(){ var data = $challenge;
            return runBotGuard(data).then(function (r) { window.webPoSignalOutput = r.webPoSignalOutput; return r.botguardResponse; });
        })()""")?.toString() ?: throw PoTokenException("BotGuard returned nothing")

        val (integrityToken, expiresInSeconds) = parseIntegrityTokenData(
            botguardRequest("https://www.youtube.com/api/jnn/v1/GenerateIT", "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]"))
        // Ten minutes of margin, as upstream.
        expirationInstant = Instant.now().plusSeconds(expiresInSeconds).minus(10, ChronoUnit.MINUTES)
        run("""(function(){ window.integrityToken = $integrityToken;
            return createPoTokenMinter(window.webPoSignalOutput, window.integrityToken).then(function(){ return true; });
        })()""")
        Timber.tag(TAG).d("poToken minter created")
    }

    suspend fun generatePoToken(identifier: String): String {
        if (isDead) throw PoTokenException("PoToken tab is dead/closed — instance must be recreated")
        val u8 = run("""(function(){ return obtainPoToken(${stringToU8(identifier)}).then(function(t){ return t.join(','); }); })()""")
            ?.toString() ?: throw PoTokenException("obtainPoToken returned nothing")
        return u8ToBase64(u8)
    }

    private suspend fun run(expression: String): Any? = try {
        page.evaluate(expression, GENERATE_TIMEOUT_MS)
    } catch (e: HeadlessChromium.JsException) {
        throw buildExceptionForJsError(e.message.orEmpty())
    }

    private suspend fun botguardRequest(url: String, data: String): String = withContext(Dispatchers.IO) {
        val request = okhttp3.Request.Builder()
            .post(data.toRequestBody())
            .headers(mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json",
                "Content-Type" to "application/json+protobuf",
                "x-goog-api-key" to ZemerCipher.poTokenApiKey,
                "x-user-agent" to "grpc-web-javascript/0.1",
            ).toHeaders())
            .url(url)
            .build()
        ZemerCipher.httpClient.newCall(request).execute().use { response ->
            val body = if (response.code == 200) response.body?.string() else null
            if (body.isNullOrEmpty()) throw PoTokenException("Invalid botguard response (code=${response.code}, empty body)")
            body
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { page.close() }
    }

    companion object {
        private const val TAG = "PoTokenWebView"
        private const val PAGE_URL = "https://www.youtube.com/auralis-potoken/index.html"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
        private const val INIT_TIMEOUT_MS = 30_000L
        private const val GENERATE_TIMEOUT_MS = 15_000L

        suspend fun getNewPoTokenGenerator(): PoTokenWebView {
            val page = try {
                HeadlessChromium.openPage()
            } catch (e: HeadlessChromium.ChromiumUnavailable) {
                throw PoTokenException(e.message ?: "Chromium unavailable")
            }
            val generator = PoTokenWebView(page)
            try {
                generator.initialize()
            } catch (t: Throwable) {
                generator.close()
                throw t
            }
            return generator
        }
    }
}
