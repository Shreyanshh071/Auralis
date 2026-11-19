package com.auralis.music.data.sync

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import kotlin.test.*

class LibraryBootstrapTest {
    private class Store : LibraryCloudStore {
        val data = mutableMapOf<String, String>()
        override fun read(uid: String, name: String) = data["$uid/$name"]
        override fun write(uid: String, name: String, value: String?) {
            if (value == null) data.remove("$uid/$name") else data["$uid/$name"] = value
        }
    }

    @Test fun `pending import never routes to Firestore and recent legacy cache cannot postpone bootstrap`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var ready = false
        var calls = 0
        server.createContext("/") { exchange ->
            assertEquals("/v1/library/bootstrap", exchange.requestURI.path)
            assertEquals("Bearer fixture", exchange.requestHeaders.getFirst("Authorization"))
            calls++
            val text = if (ready) """{"state":"cloudflare","epoch":1}""" else """{"state":"migrating","epoch":0,"retryAfterMs":60000}"""
            val bytes = text.toByteArray()
            exchange.sendResponseHeaders(if (ready) 200 else 202, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = Store()
        val now = 100000L
        store.write("test", "library-authority", AuthorityAnswer(LibraryAuthority.LEGACY, 0, now).encode())
        val client = LibraryCloudClient("http://127.0.0.1:${server.address.port}", "pc", store, OkHttpClient(), scope,
            now = { now }, token = { _, _ -> "fixture" })
        try {
            assertEquals(LibraryRoute.Wait(true), client.route("test"))
            assertEquals(1, calls)
            ready = true
            assertEquals(LibraryRoute.Cloudflare(1), client.route("test"))
            assertEquals(2, calls)
            assertEquals(LibraryRoute.Cloudflare(1), client.route("test"))
            assertEquals(2, calls, "healthy cloud accounts do not repeatedly bootstrap")
        } finally { scope.cancel(); server.stop(0) }
    }
}
