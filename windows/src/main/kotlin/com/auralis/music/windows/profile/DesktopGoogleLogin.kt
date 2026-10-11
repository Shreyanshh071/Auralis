package com.auralis.music.windows.profile

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.json.JSONObject
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.net.HttpURLConnection

/** System browser + PKCE + one-use loopback callback. No Google password reaches Auralis. */
internal object DesktopGoogleLogin {
    val available: Boolean get() = javaClass.classLoader.getResource("google-desktop.json") != null

    suspend fun idToken(openBrowser: (URI) -> Unit = { Desktop.getDesktop().browse(it) }): String = withContext(Dispatchers.IO) {
        val config = javaClass.classLoader.getResourceAsStream("google-desktop.json")?.bufferedReader()?.use { JSONObject(it.readText()).getJSONObject("installed") }
            ?: error("Desktop Google sign-in is not configured in this build")
        val verifier = randomToken()
        val state = randomToken()
        val callback = CompletableDeferred<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val redirect = "http://127.0.0.1:${server.address.port}/"
        server.createContext("/") { exchange ->
            val parameters = parseQuery(exchange.requestURI.rawQuery.orEmpty())
            val valid = exchange.requestMethod == "GET" && exchange.requestURI.path == "/" && parameters["state"] == state
            val code = parameters["code"]
            val success = valid && !code.isNullOrBlank() && !callback.isCompleted
            val page = (if (success) "Sign-in received. You can close this tab and return to Auralis." else "Sign-in could not be completed. Return to Auralis and try again.").toByteArray()
            exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.sendResponseHeaders(if (valid) 200 else 400, page.size.toLong())
            exchange.responseBody.use { it.write(page) }
            if (success) callback.complete(code!!)
            else if (valid && parameters.containsKey("error")) callback.completeExceptionally(IllegalStateException("Google sign-in was cancelled or denied"))
        }
        try {
            server.start()
            val parameters = mapOf("client_id" to config.getString("client_id"), "redirect_uri" to redirect,
                "response_type" to "code", "scope" to "openid email profile", "state" to state,
                "code_challenge" to challenge(verifier), "code_challenge_method" to "S256", "prompt" to "select_account")
            openBrowser(URI("https://accounts.google.com/o/oauth2/v2/auth?${form(parameters)}"))
            val code = withTimeout(180_000) { callback.await() }
            val token = exchangeCode(mapOf("code" to code, "client_id" to config.getString("client_id"),
                "client_secret" to config.optString("client_secret"), "redirect_uri" to redirect,
                "grant_type" to "authorization_code", "code_verifier" to verifier))
            token.getString("id_token")
        } finally { server.stop(0) }
    }

    internal fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    internal fun parseQuery(query: String): Map<String, String> = query.split('&').filter { it.contains('=') }.associate {
        URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
    }
    internal fun form(parameters: Map<String, String>): String = parameters.entries.joinToString("&") { (key, value) -> "${URLEncoder.encode(key, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}" }
    private fun randomToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    private fun exchangeCode(parameters: Map<String, String>): JSONObject {
        val connection = URI("https://oauth2.googleapis.com/token").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = 5000; connection.readTimeout = 10_000
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.doOutput = true
            connection.outputStream.use { it.write(form(parameters).toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode in 200..299) { "Google couldn't complete sign-in. Please try again." }
            return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally { connection.disconnect() }
    }
}
