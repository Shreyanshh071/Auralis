package com.auralis.music.data.network

import java.io.File
import kotlin.random.Random

/** Time sources for cooldowns: monotonic for waits in this process, wall time only for persistence. */
interface CooldownClock {
    fun monotonicMs(): Long
    fun wallMs(): Long

    companion object Default : CooldownClock {
        override fun monotonicMs(): Long = System.nanoTime() / 1_000_000L
        override fun wallMs(): Long = System.currentTimeMillis()
    }
}

/**
 * The YouTube control-plane cooldown (player, visitor, search and browse requests; media bytes from
 * already-resolved links are not held). States: closed (requests flow), waiting (until a deadline),
 * and single trial (the deadline passed and exactly one caller may try; everyone else keeps
 * waiting until it reports back or its lease runs out). Many callers can therefore never all
 * retry the moment a pause ends.
 *
 * Causes have their own schedules (all provisional app policy, not known YouTube thresholds):
 *  - rate limited: 5s doubling to 60s, or the server's Retry-After when longer;
 *  - verification challenge: 5 min doubling to 30 min (conservative: requests prolong it);
 *  - transient: after [TRANSIENT_TRIP_COUNT] failures in a row, 5s doubling to 60s.
 *
 * The deadline is persisted (versioned, bounded, without secrets), so a restart keeps waiting; a
 * corrupt file or a wall-clock jump can never lock playback for longer than [MAX_PERSISTED_MS].
 */
class YouTubeCooldown(
    private val clock: CooldownClock = CooldownClock,
    private val store: () -> File? = { null },
    private val random: Random = Random.Default
) {
    enum class Cause { RATE_LIMITED, VERIFICATION, TRANSIENT }

    data class Snapshot(val cause: Cause, val retryInMs: Long, val step: Int, val trialInFlight: Boolean)

    private data class State(val cause: Cause, val untilMono: Long, val untilWall: Long, val step: Int)

    private val lock = Any()
    @Volatile private var state: State? = null
    @Volatile private var trialLeaseUntil = 0L
    @Volatile private var loaded = false
    private var consecutiveTransient = 0

    /** How many times requests were paused; a change during a task means its answers were cut short. */
    @Volatile var trips = 0
        private set

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            load()
            loaded = true
        }
    }

    /** True while waiting, or while another caller's single trial is in flight. */
    val active: Boolean get() {
        ensureLoaded()
        synchronized(lock) {
            val s = state ?: return false
            val now = clock.monotonicMs()
            return now < s.untilMono || now < trialLeaseUntil
        }
    }

    fun snapshot(): Snapshot? {
        ensureLoaded()
        synchronized(lock) {
            val s = state ?: return null
            val now = clock.monotonicMs()
            return Snapshot(s.cause, (s.untilMono - now).coerceAtLeast(0L), s.step, now < trialLeaseUntil)
        }
    }

    /** Milliseconds until the next permitted attempt (0 when a request may go now). */
    fun retryInMs(): Long {
        ensureLoaded()
        synchronized(lock) {
            val s = state ?: return 0L
            val now = clock.monotonicMs()
            return maxOf(s.untilMono - now, trialLeaseUntil - now, 0L)
        }
    }

    /**
     * Whether a request may be sent now. Closed: always. After a pause ended: true for exactly one
     * caller (the trial), which should then call [recordSuccess] or trip again.
     */
    fun permit(): Boolean {
        ensureLoaded()
        synchronized(lock) {
            val s = state ?: return true
            val now = clock.monotonicMs()
            if (now < s.untilMono || now < trialLeaseUntil) return false
            trialLeaseUntil = now + TRIAL_LEASE_MS
            com.auralis.music.util.Log.i(TAG, "Pause (${s.cause}) over; allowing one trial request")
            return true
        }
    }

    /**
     * A YouTube request got a real answer: requests flow again. A verification challenge was given
     * to stream lookups, so only a successful stream lookup ([fromPlayer]) ends it; a search
     * answering meanwhile says nothing about the player.
     */
    fun recordSuccess(fromPlayer: Boolean = true) {
        ensureLoaded()
        val cleared = synchronized(lock) {
            consecutiveTransient = 0
            if (!fromPlayer && state?.cause == Cause.VERIFICATION) {
                trialLeaseUntil = 0L
                return
            }
            val had = state != null
            state = null
            trialLeaseUntil = 0L
            had
        }
        if (cleared) {
            com.auralis.music.util.Log.i(TAG, "YouTube answered again; requests resume")
            persist(null)
        }
    }

    /** A timeout or 5xx; only a run of them pauses requests. */
    fun noteTransient() {
        val trip = synchronized(lock) { ++consecutiveTransient >= TRANSIENT_TRIP_COUNT }
        if (trip) trip(Cause.TRANSIENT)
    }

    fun trip(cause: Cause, retryAfterMs: Long? = null) {
        ensureLoaded()
        val next = synchronized(lock) {
            val now = clock.monotonicMs()
            val previous = state
            val step = if (previous != null && previous.cause == cause) (previous.step + 1).coerceAtMost(10) else 0
            val (base, cap) = when (cause) {
                Cause.RATE_LIMITED, Cause.TRANSIENT -> 5_000L to 60_000L
                Cause.VERIFICATION -> 5 * 60_000L to 30 * 60_000L
            }
            val scheduled = (base shl step).coerceAtMost(cap)
            // Bounded jitter (+/-10%) so separate processes don't retry in lockstep.
            val jittered = scheduled + (scheduled / 10 * (random.nextDouble() * 2 - 1)).toLong()
            val wait = maxOf(jittered, retryAfterMs ?: 0L).coerceIn(1_000L, MAX_PERSISTED_MS)
            // Never shorten a pause already in effect (a slower caller reporting an older failure).
            val untilMono = maxOf(now + wait, previous?.untilMono ?: 0L)
            val s = State(cause, untilMono, clock.wallMs() + (untilMono - now), step)
            state = s
            trialLeaseUntil = 0L
            consecutiveTransient = 0
            trips++
            com.auralis.music.util.Log.w(TAG, "Pausing YouTube requests for ${(untilMono - now) / 1000}s ($cause, step $step${retryAfterMs?.let { ", retry-after ${it / 1000}s" } ?: ""})")
            s
        }
        persist(next)
    }

    /** Suspends until a request may be attempted (it may still need [permit]). */
    suspend fun awaitClear() {
        while (true) {
            val wait = retryInMs()
            if (wait <= 0L) return
            kotlinx.coroutines.delay(wait.coerceIn(250L, 15_000L))
        }
    }

    // ---- persistence -------------------------------------------------------------------------

    private fun load() {
        val file = runCatching { store() }.getOrNull()?.takeIf { it.isFile } ?: return
        val parsed = runCatching {
            val p = java.util.Properties().apply { file.inputStream().use { load(it) } }
            require(p.getProperty("version") == VERSION.toString()) { "version" }
            require(p.getProperty("scope") == SCOPE) { "scope" }
            val cause = Cause.valueOf(p.getProperty("cause"))
            val untilWall = p.getProperty("untilWall").toLong()
            val step = p.getProperty("step").toInt().coerceIn(0, 10)
            Triple(cause, untilWall, step)
        }.getOrElse {
            com.auralis.music.util.Log.w(TAG, "Ignoring unreadable cooldown state (${it.message})")
            runCatching { file.delete() }
            return
        }
        val (cause, untilWall, step) = parsed
        val remaining = untilWall - clock.wallMs()
        if (remaining <= 0L) return
        // A wall clock set back (or a corrupt deadline) can't lock playback for longer than this.
        val bounded = remaining.coerceAtMost(MAX_PERSISTED_MS)
        val now = clock.monotonicMs()
        state = State(cause, now + bounded, clock.wallMs() + bounded, step)
        com.auralis.music.util.Log.i(TAG, "Restored YouTube pause ($cause) for ${bounded / 1000}s more")
    }

    private fun persist(@Suppress("UNUSED_PARAMETER") requested: State?): Unit = synchronized(lock) {
        // A slower caller must not overwrite a newer trip (or delete its persisted pause).
        val s = state
        val file = runCatching { store() }.getOrNull() ?: return
        runCatching {
            if (s == null) { file.delete(); return }
            file.parentFile?.mkdirs()
            val p = java.util.Properties()
            p.setProperty("version", VERSION.toString())
            p.setProperty("scope", SCOPE)
            p.setProperty("cause", s.cause.name)
            p.setProperty("untilWall", s.untilWall.toString())
            p.setProperty("step", s.step.toString())
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.outputStream().use { p.store(it, null) }
            java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { com.auralis.music.util.Log.w(TAG, "Couldn't save cooldown state: ${it.message}") }
    }

    companion object {
        private const val TAG = "YouTubeCooldown"
        const val VERSION = 1
        const val SCOPE = "youtube-control"
        /** How long one trial may run before another caller may try. */
        const val TRIAL_LEASE_MS = 30_000L
        const val TRANSIENT_TRIP_COUNT = 3
        /** No persisted or server-requested pause lasts longer than this. */
        const val MAX_PERSISTED_MS = 2L * 3600_000L
    }
}

/**
 * The app's one YouTube cooldown, plus the block-page check. Older callers use [active], [trip]
 * and [awaitClear]; request paths should use [permit] so only one trial goes out after a pause.
 */
object YouTubeBackoff {
    /** Where the cooldown persists; set by the app at startup (tests leave it unset). */
    @Volatile var stateFile: File? = null

    val cooldown = YouTubeCooldown(store = { stateFile })

    val active: Boolean get() = cooldown.active
    val trips: Int get() = cooldown.trips

    /**
     * Whether [body] (a response or an extractor's error message) is one of YouTube's explicit
     * verification challenges: the unusual-traffic "Sorry..." page, or the "Sign in to confirm
     * that you're not a bot" refusal. This proves a challenge, not its trigger, scope or expiry.
     */
    fun isBlockPage(body: String): Boolean =
        body.contains("<title>Sorry...</title>") || (body.contains("Got HTML document, expected JSON") && !body.contains("visitor_id")) ||
            body.contains("confirm that you're not a bot", ignoreCase = true) || body.contains("confirm you're not a bot", ignoreCase = true)

    /** A verification challenge (the historical meaning of a "block"). */
    fun trip() = cooldown.trip(YouTubeCooldown.Cause.VERIFICATION)

    fun trip(failure: PlaybackFailure) {
        when (failure.category) {
            FailureCategory.RATE_LIMITED -> cooldown.trip(YouTubeCooldown.Cause.RATE_LIMITED, failure.retryAfterMs)
            FailureCategory.VERIFICATION_CHALLENGE -> cooldown.trip(YouTubeCooldown.Cause.VERIFICATION, failure.retryAfterMs)
            FailureCategory.TRANSIENT -> cooldown.noteTransient()
            else -> Unit
        }
    }

    fun permit(): Boolean = cooldown.permit()
    fun recordSuccess(fromPlayer: Boolean = true) = cooldown.recordSuccess(fromPlayer)
    fun retryInMs(): Long = cooldown.retryInMs()

    /** Suspends until any pause is over. */
    suspend fun awaitClear() = cooldown.awaitClear()
}

/**
 * Paces background YouTube searches (playlist matching, Home's album and artist lookups) one at a
 * time, [SPACING_MS] apart, and holds them while YouTube requests are paused. A burst of them at
 * launch (a 250-song match plus Home's lookups) is what got the network blocked, and the block
 * then stopped everything. Searches the listener types are not queued here.
 */
object YouTubeBackgroundGate {
    /** Gap after one search request; a full search (about six requests) asks for more. */
    const val SPACING_MS = 800L
    private val mutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var nextAt = 0L

    suspend fun <T> run(spacingMs: Long = SPACING_MS, block: suspend () -> T): T {
        YouTubeBackoff.awaitClear()
        mutex.lock()
        try {
            YouTubeBackoff.awaitClear()
            val wait = nextAt - System.currentTimeMillis()
            if (wait > 0) kotlinx.coroutines.delay(wait)
            return block()
        } finally {
            nextAt = System.currentTimeMillis() + spacingMs
            mutex.unlock()
        }
    }
}
