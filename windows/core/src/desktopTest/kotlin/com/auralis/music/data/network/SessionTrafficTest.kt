package com.auralis.music.data.network

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SessionCookieJarTest {
    // OkHttp turns Max-Age into an expiry from the real clock, so the jar starts from it too.
    private var now = System.currentTimeMillis()
    private val jar = SessionCookieJar { now }
    private fun save(url: String, header: String) {
        val u = url.toHttpUrl()
        jar.saveFromResponse(u, listOfNotNull(Cookie.parse(u, header)))
    }
    private fun names(url: String) = jar.loadForRequest(url.toHttpUrl()).map { it.name }.toSet()

    @Test fun suffixLookalikeHostsGetNothing() {
        save("https://www.youtube.com/", "VISITOR=1; Domain=youtube.com; Path=/")
        assertEquals(setOf("VISITOR"), names("https://music.youtube.com/youtubei/v1/player"))
        assertTrue(names("https://notyoutube.com/").isEmpty(), "a suffix match is not a domain match")
        assertTrue(names("https://youtube.com.evil.example/").isEmpty())
    }

    @Test fun hostOnlyCookiesStayOnTheirHost() {
        save("https://music.youtube.com/", "HOSTONLY=1; Path=/")
        assertEquals(setOf("HOSTONLY"), names("https://music.youtube.com/"))
        assertTrue(names("https://www.youtube.com/").isEmpty(), "a subdomain's cookie never leaks to its siblings")
        assertTrue(names("https://youtube.com/").isEmpty(), "or to its parent")
    }

    @Test fun aDomainCookieReplacesTheSameHostOnlyCookie() {
        save("https://youtube.com/", "SESSION=old; Path=/")
        save("https://youtube.com/", "SESSION=new; Domain=youtube.com; Path=/")
        assertEquals(listOf("new"), jar.loadForRequest("https://youtube.com/".toHttpUrl()).map { it.value })
    }

    @Test fun expiredAndInsecureTransportCookiesAreWithheld() {
        save("https://www.youtube.com/", "SECURE=1; Domain=youtube.com; Path=/; Secure")
        save("https://www.youtube.com/", "SHORT=1; Domain=youtube.com; Path=/; Max-Age=60")
        assertEquals(setOf("SECURE", "SHORT"), names("https://www.youtube.com/"))
        assertEquals(setOf("SHORT"), names("http://www.youtube.com/"), "Secure cookies need HTTPS")
        now += 61_000L
        assertEquals(setOf("SECURE"), names("https://www.youtube.com/"))
        save("https://www.youtube.com/", "SECURE=; Domain=youtube.com; Path=/; Max-Age=0")
        assertTrue(names("https://www.youtube.com/").isEmpty(), "a deletion removes the cookie")
    }
}

class YouTubeTrafficTest {
    private lateinit var server: HttpServer
    private val port get() = server.address.port
    private val hang = CountDownLatch(1)

    /** Sends www.youtube.com to the local fixture, so categories are real but nothing leaves the PC. */
    private val client by lazy {
        OkHttpClient.Builder()
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname.endsWith("youtube.com") || hostname.endsWith("googlevideo.com")) listOf(InetAddress.getLoopbackAddress())
                    else okhttp3.Dns.SYSTEM.lookup(hostname)
            })
            .addInterceptor(YouTubeTraffic.gate)
            .addNetworkInterceptor(YouTubeTraffic.meter)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @BeforeTest fun start() {
        YouTubeTraffic.reset()
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.createContext("/") { ex ->
            when (ex.requestURI.path) {
                "/watch" -> { ex.responseHeaders.add("Location", "/youtubei/v1/player"); ex.sendResponseHeaders(302, -1) }
                "/limited" -> { ex.responseHeaders.add("Retry-After", "42"); ex.sendResponseHeaders(429, -1) }
                "/hang" -> { hang.await(20, TimeUnit.SECONDS); ex.sendResponseHeaders(200, -1) }
                else -> ex.sendResponseHeaders(200, -1)
            }
            ex.close()
        }
        server.start()
    }

    @AfterTest fun stop() {
        hang.countDown()
        server.stop(0)
        YouTubeBackoff.recordSuccess()
    }

    private fun get(path: String, host: String = "www.youtube.com") =
        client.newCall(Request.Builder().url("http://$host:$port$path").build()).execute().use { it.code }

    @Test fun everyActualRequestIsCountedByEndpointIncludingRedirects() {
        val budget = RequestBudget("t1", "play")
        YouTubeTraffic.withTag(YouTubeTraffic.Tag("t1", "play", budget)) {
            assertEquals(200, get("/watch"))
            get("/videoplayback?expire=1&sig=secret", host = "rr1---sn-x.googlevideo.com")
        }
        get("/youtubei/v1/search")
        assertEquals(1, YouTubeTraffic.count(YouTubeTraffic.Endpoint.WATCH_PAGE, "play"))
        assertEquals(1, YouTubeTraffic.count(YouTubeTraffic.Endpoint.PLAYER, "play"), "the redirect's second request counts too")
        assertEquals(1, YouTubeTraffic.count(YouTubeTraffic.Endpoint.MEDIA))
        assertEquals(1, YouTubeTraffic.count(YouTubeTraffic.Endpoint.SEARCH, "untagged"))
        assertEquals(2, budget.controlRequests.get())
        assertEquals(1, budget.mediaRequests.get())
        assertEquals("WATCH_PAGE=1 PLAYER=1 MEDIA=1", YouTubeTraffic.summaryFor("t1"))
        assertTrue(YouTubeTraffic.recentEvents().none { it.host.contains("sig") || it.host.contains("rr1") }, "media hosts and queries aren't logged")
    }

    @Test fun anExhaustedAllowanceSendsNothingMore() {
        val budget = RequestBudget("t2", "play", controlCredits = 1)
        YouTubeTraffic.withTag(YouTubeTraffic.Tag("t2", "play", budget)) {
            get("/youtubei/v1/player")
            assertFailsWith<BudgetExhaustedException> { get("/youtubei/v1/player") }
            // Media for an already-playing song is never capped by the control-plane allowance.
            get("/videoplayback", host = "rr1.googlevideo.com")
        }
        assertEquals(1, YouTubeTraffic.count(YouTubeTraffic.Endpoint.PLAYER))
    }

    @Test fun redirectCannotSpendBeyondTheAllowance() {
        val budget = RequestBudget("redirect", "play", controlCredits = 1)
        YouTubeTraffic.withTag(YouTubeTraffic.Tag("redirect", "play", budget)) {
            assertFailsWith<BudgetExhaustedException> { get("/watch") }
        }
        assertEquals(1, budget.controlRequests.get())
        assertEquals(0, YouTubeTraffic.count(YouTubeTraffic.Endpoint.PLAYER))
    }

    @Test fun asynchronousSearchCarriesTheIntentBudget() = runBlocking {
        val budget = RequestBudget("search", "match", controlCredits = 1)
        withContext(PlaybackIntentContext(budget)) {
            client.searchBody(Request.Builder().url("http://www.youtube.com:$port/youtubei/v1/search").build(), 2000)
            assertFailsWith<BudgetExhaustedException> {
                client.searchBody(Request.Builder().url("http://www.youtube.com:$port/youtubei/v1/search").build(), 2000)
            }
        }
        assertEquals(1, budget.controlRequests.get())
    }

    @Test fun authorizationHeadersNeverLeaveBearerCredentialsBehind() {
        val redacted = YouTubeTraffic.redact("Authorization: Bearer superSecret\nCookie: SID=secret; extra=value")
        assertFalse(redacted.contains("superSecret"))
        assertFalse(redacted.contains("value"))
    }

    @Test fun http429PausesYouTubeRequestsForRetryAfter() {
        assertEquals(429, get("/limited"))
        assertTrue(YouTubeBackoff.active)
        assertTrue(YouTubeBackoff.retryInMs() in 40_000L..42_000L)
        assertEquals(YouTubeCooldown.Cause.RATE_LIMITED, YouTubeBackoff.cooldown.snapshot()?.cause)
    }

    @Test fun cancelingAnExtractionCancelsItsBlockedHttpCallAtOnce() = runBlocking {
        val downloader = NewPipeDownloader(client)
        val started = System.nanoTime()
        val job = launch(Dispatchers.Default) {
            NewPipeDownloader.cancellable(30_000L) {
                downloader.execute(org.schabi.newpipe.extractor.downloader.Request.newBuilder().get("http://www.youtube.com:$port/hang").build())
            }
        }
        delay(300)
        withTimeout(2_000) { job.cancelAndJoin() }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsed < 2_500, "a canceled extraction waited ${elapsed}ms for its socket read")
    }

    @Test fun redactionKeepsHostAndPathOnly() {
        val text = YouTubeTraffic.redact("failed https://rr1.googlevideo.com/videoplayback?sig=abc&pot=xyz visitorData=CgtABC cookie: SID=123")
        assertFalse(text.contains("sig=abc"))
        assertFalse(text.contains("CgtABC"))
        assertFalse(text.contains("SID=123"))
        assertTrue(text.contains("https://rr1.googlevideo.com/videoplayback"))
    }
}
