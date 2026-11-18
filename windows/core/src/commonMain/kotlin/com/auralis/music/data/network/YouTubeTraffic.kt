package com.auralis.music.data.network

import okhttp3.Interceptor
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * One playback (or download) intent's allowance: a monotonic deadline, a cap on YouTube
 * control-plane HTTP requests (player, visitor, watch page, player JS, search, browse), and a
 * small number of recovery transitions (a refresh, a re-resolve after a stall, an alternate
 * upload). Shared by everything the intent sets off, so nested retries can't each start over.
 *
 * The numbers are provisional app policy to be tuned from measured request totals; they are not
 * known YouTube thresholds. Media byte ranges are counted but never capped here (a healthy song
 * must not be cut off by a control-plane cap).
 */
class RequestBudget(
    val correlationId: String,
    val reason: String,
    private val deadlineMs: Long = FOREGROUND_DEADLINE_MS,
    private val controlCredits: Int = CONTROL_CREDITS,
    private val recoveryTransitions: Int = RECOVERY_TRANSITIONS,
    private val clock: CooldownClock = CooldownClock
) {
    @Volatile private var deadlineMono = clock.monotonicMs() + deadlineMs
    private val credits = AtomicInteger(controlCredits)
    private val transitions = AtomicInteger(recoveryTransitions)
    val controlRequests = AtomicInteger()
    val mediaRequests = AtomicInteger()

    fun remainingMs(): Long = deadlineMono - clock.monotonicMs()
    val expired: Boolean get() = remainingMs() <= 0L
    val creditsLeft: Int get() = credits.get().coerceAtLeast(0)
    val transitionsLeft: Int get() = transitions.get().coerceAtLeast(0)
    val exhausted: Boolean get() = expired || credits.get() <= 0

    /** End this intent's automatic lookups without imposing a timer on the next Play press. */
    fun stopControlRequests() { credits.set(0) }

    internal fun chargeControl() {
        if (expired) throw BudgetExhaustedException("Request deadline reached for $correlationId")
        while (true) {
            val left = credits.get()
            if (left <= 0) throw BudgetExhaustedException("Request allowance used up for $correlationId")
            if (credits.compareAndSet(left, left - 1)) break
        }
        controlRequests.incrementAndGet()
    }

    /** Takes one recovery transition; false when none are left (the caller must stop, not retry). */
    fun tryTransition(why: String): Boolean {
        val left = transitions.decrementAndGet()
        if (left < 0) {
            transitions.incrementAndGet()
            com.auralis.music.util.Log.w("RequestBudget", "[$correlationId] no recovery left for: $why")
            return false
        }
        com.auralis.music.util.Log.i("RequestBudget", "[$correlationId] recovery ${recoveryTransitions - left}/$recoveryTransitions: $why")
        return true
    }

    /** Meaningful playback progress: the intent earns a fresh allowance for later recovery. */
    fun renewAfterProgress() {
        deadlineMono = clock.monotonicMs() + deadlineMs
        credits.set(controlCredits)
        transitions.set(recoveryTransitions)
    }

    override fun toString() = "[$correlationId $reason: control ${controlRequests.get()}, media ${mediaRequests.get()}, " +
        "credits $creditsLeft, recoveries $transitionsLeft, ${remainingMs().coerceAtLeast(0L)}ms left]"

    companion object {
        const val FOREGROUND_DEADLINE_MS = 30_000L
        const val BACKGROUND_DEADLINE_MS = 15_000L
        /**
         * Control-plane requests per intent. A cold NewPipe extraction (visitor, player for each
         * client, watch page and player JS when signatures need solving) needs several requests.
         * This provisional allowance still needs validation against the traffic diagnostics.
         */
        const val CONTROL_CREDITS = 16
        const val RECOVERY_TRANSITIONS = 2
        private val next = AtomicInteger()

        fun newId(prefix: String = "p"): String = "$prefix${next.incrementAndGet().toString(36)}"
    }
}

/** Carries an intent's [RequestBudget] through coroutines. */
class PlaybackIntentContext(val budget: RequestBudget) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PlaybackIntentContext>
}

/**
 * Counts actual YouTube HTTP traffic: each network attempt (redirects and OkHttp retries
 * included) by endpoint category, status, reason and correlation ID, with elapsed time. An
 * extraction is several requests; counting extraction jobs alone cannot bound traffic.
 *
 * Diagnostics never contain query strings (signed stream parameters, keys), cookies, visitor
 * data or authorization headers: only host, path category, status and timing.
 */
object YouTubeTraffic {
    enum class Endpoint(val controlPlane: Boolean) {
        PLAYER(true), VISITOR(true), NEXT(true), SEARCH(true), BROWSE(true), WATCH_PAGE(true),
        PLAYER_JS(true), OTHER_YOUTUBE(true), MEDIA(false), OTHER(false)
    }

    data class Tag(val correlationId: String, val reason: String, val budget: RequestBudget? = null)

    data class Event(val atWallMs: Long, val correlationId: String, val reason: String, val endpoint: Endpoint,
        val host: String, val status: Int, val elapsedMs: Long, val error: String? = null)

    private val current = ThreadLocal<Tag?>()
    private val counters = ConcurrentHashMap<String, AtomicInteger>()
    private val avoided = ConcurrentHashMap<Endpoint, AtomicInteger>()
    private val events = ArrayDeque<Event>()
    private const val MAX_EVENTS = 400

    /** Dated, size-bounded traffic logs go here when set (the app sets it at startup). */
    @Volatile var diagnosticsDir: File? = null
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "traffic-log").apply { isDaemon = true } }

    fun endpointOf(host: String, path: String): Endpoint {
        val h = host.lowercase()
        if (h == "googlevideo.com" || h.endsWith(".googlevideo.com")) return Endpoint.MEDIA
        val youtube = h == "youtube.com" || h.endsWith(".youtube.com") || h == "youtubei.googleapis.com" || h == "youtube.googleapis.com"
        if (!youtube) return Endpoint.OTHER
        return when {
            path.startsWith("/youtubei/v1/player") -> Endpoint.PLAYER
            path.startsWith("/youtubei/v1/visitor_id") -> Endpoint.VISITOR
            path.startsWith("/youtubei/v1/next") -> Endpoint.NEXT
            path.startsWith("/youtubei/v1/search") || path.startsWith("/results") -> Endpoint.SEARCH
            path.startsWith("/youtubei/v1/browse") -> Endpoint.BROWSE
            path.startsWith("/watch") || path.startsWith("/embed") -> Endpoint.WATCH_PAGE
            path.startsWith("/s/player") || path.endsWith(".js") -> Endpoint.PLAYER_JS
            else -> Endpoint.OTHER_YOUTUBE
        }
    }

    /** Runs [block] with requests on this thread attributed to [tag] (and charged to its budget). */
    fun <T> withTag(tag: Tag, block: () -> T): T {
        val previous = current.get()
        current.set(tag)
        return try { block() } finally { if (previous == null) current.remove() else current.set(previous) }
    }

    fun currentTag(): Tag? = current.get()

    /**
     * Application-level gate, once per logical call: refuses a control-plane request that the
     * intent's budget can no longer pay for, before anything is sent.
     */
    val gate = Interceptor { chain ->
        val request = chain.request()
        val endpoint = endpointOf(request.url.host, request.url.encodedPath)
        val budget = (request.tag(Tag::class.java) ?: current.get())?.budget
        if (endpoint.controlPlane && budget != null) {
            if (budget.expired) throw BudgetExhaustedException("Request deadline reached for ${budget.correlationId}")
            if (budget.creditsLeft <= 0) throw BudgetExhaustedException("Request allowance used up for ${budget.correlationId}")
        }
        chain.proceed(request)
    }

    /** Network-level meter: sees every actual attempt, including redirects and connection retries. */
    val meter = Interceptor { chain ->
        val request = chain.request()
        val endpoint = endpointOf(request.url.host, request.url.encodedPath)
        if (endpoint == Endpoint.OTHER) return@Interceptor chain.proceed(request)
        val tag = request.tag(Tag::class.java) ?: current.get()
        tag?.budget?.let { if (endpoint.controlPlane) it.chargeControl() else it.mediaRequests.incrementAndGet() }
        val started = System.nanoTime()
        try {
            val response = chain.proceed(request)
            record(endpoint, request.url.host, response.code, tag, (System.nanoTime() - started) / 1_000_000L, null)
            if (endpoint.controlPlane && response.code == 429) {
                val retry = RetryAfter.parseMs(response.header("Retry-After"))
                YouTubeBackoff.trip(PlaybackFailure(FailureCategory.RATE_LIMITED, endpoint.name.lowercase(), 429, retry))
            }
            response
        } catch (e: IOException) {
            record(endpoint, request.url.host, -1, tag, (System.nanoTime() - started) / 1_000_000L, e.javaClass.simpleName)
            throw e
        }
    }

    /** A request the downloader answered itself (the skipped "next" call, a cached visitor ID). */
    fun noteAvoided(endpoint: Endpoint) {
        avoided.computeIfAbsent(endpoint) { AtomicInteger() }.incrementAndGet()
    }

    private fun record(endpoint: Endpoint, host: String, status: Int, tag: Tag?, elapsedMs: Long, error: String?) {
        val reason = tag?.reason ?: "untagged"
        counters.computeIfAbsent("$endpoint|$status|$reason") { AtomicInteger() }.incrementAndGet()
        val event = Event(System.currentTimeMillis(), tag?.correlationId ?: "-", reason, endpoint,
            if (endpoint == Endpoint.MEDIA) "googlevideo" else host, status, elapsedMs, error)
        synchronized(events) {
            events.addLast(event)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
        diagnosticsDir?.let { dir -> writer.execute { writeLine(dir, event) } }
    }

    /** Requests by "ENDPOINT|status|reason". */
    fun snapshot(): Map<String, Int> = counters.mapValues { it.value.get() }.toSortedMap()

    fun avoidedSnapshot(): Map<Endpoint, Int> = avoided.mapValues { it.value.get() }

    fun count(endpoint: Endpoint? = null, reason: String? = null): Int = counters.entries.sumOf { (key, value) ->
        val parts = key.split('|')
        if ((endpoint == null || parts[0] == endpoint.name) && (reason == null || parts.getOrNull(2) == reason)) value.get() else 0
    }

    fun controlPlaneCount(): Int = counters.entries.sumOf { (key, value) ->
        if (Endpoint.valueOf(key.substringBefore('|')).controlPlane) value.get() else 0
    }

    fun recentEvents(): List<Event> = synchronized(events) { events.toList() }

    /** Requests one intent caused, by endpoint ("PLAYER=2 MEDIA=5"). */
    fun summaryFor(correlationId: String): String = recentEvents().filter { it.correlationId == correlationId }
        .groupingBy { it.endpoint }.eachCount().entries.joinToString(" ") { "${it.key}=${it.value}" }.ifEmpty { "none" }

    /** For tests and the development probe. */
    fun reset() {
        counters.clear(); avoided.clear()
        synchronized(events) { events.clear() }
    }

    // ---- redaction ---------------------------------------------------------------------------

    private val urlPattern = Regex("""https?://[^\s"'<>]+""")
    private val secretPattern = Regex("""(?i)\b(visitor_?data|visitor_info1_live|__secure-[a-z0-9_-]+|sapisid|sid|hsid|ssid|apisid|authorization|cookie|pot|key)(["']?\s*[=:]\s*["']?)[^\s"'&;,]+""")

    /** Scheme, host and path only: a signed stream URL's query is a credential. */
    fun redactUrl(url: String): String = runCatching {
        val uri = java.net.URI(url)
        "${uri.scheme}://${uri.host}${uri.rawPath.orEmpty()}"
    }.getOrElse { "<url>" }

    /** [text] with URLs reduced to host/path and cookie, visitor or token values removed. */
    fun redact(text: String): String =
        secretPattern.replace(urlPattern.replace(
            Regex("""(?im)\b(authorization|cookie|set-cookie)\s*[:=]\s*[^\r\n]*""")
                .replace(text) { "${it.groupValues[1]}: <redacted>" }
        ) { redactUrl(it.value) }) { "${it.groupValues[1]}${it.groupValues[2]}<redacted>" }

    // ---- dated, bounded diagnostics ----------------------------------------------------------

    private const val MAX_FILE_BYTES = 512L * 1024L
    private const val KEEP_DAYS = 7

    private fun writeLine(dir: File, e: Event) {
        runCatching {
            dir.mkdirs()
            val day = java.time.LocalDate.now()
            val file = File(dir, "youtube-traffic-$day.log")
            if (file.length() > MAX_FILE_BYTES) return
            val time = java.time.Instant.ofEpochMilli(e.atWallMs).toString()
            file.appendText("$time ${e.correlationId} ${e.reason} ${e.endpoint} ${e.host} ${e.status} ${e.elapsedMs}ms${e.error?.let { " $it" } ?: ""}\n")
            if (!file.exists() || file.length() < 200) pruneOld(dir, day)
        }
    }

    private fun pruneOld(dir: File, today: java.time.LocalDate) {
        dir.listFiles { f -> f.name.startsWith("youtube-traffic-") && f.name.endsWith(".log") }?.forEach { f ->
            val date = runCatching { java.time.LocalDate.parse(f.name.removePrefix("youtube-traffic-").removeSuffix(".log")) }.getOrNull()
            if (date == null || date.isBefore(today.minusDays(KEEP_DAYS.toLong()))) f.delete()
        }
    }
}
