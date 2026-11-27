package com.auralis.music.desktop.playback

import com.auralis.music.data.network.FailureCategory
import com.auralis.music.data.network.FailureClassifier
import com.auralis.music.data.network.NewPipeDownloader
import com.auralis.music.data.network.PlaybackFailure
import com.auralis.music.data.network.PlaybackIntentContext
import com.auralis.music.data.network.RecordingMatches
import com.auralis.music.data.network.RequestBudget
import com.auralis.music.data.network.YouTubeBackoff
import com.auralis.music.data.network.YouTubeCooldown
import com.auralis.music.data.network.YouTubeTraffic
import com.auralis.music.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Finds the audio stream for a YouTube video with NewPipeExtractor, the same extractor the
 * phone's AudioStreamResolver uses first, and picks what the phone picks on its default quality:
 * the original-language track at the highest bitrate.
 *
 * Traffic policy (plan.md Phases 1-3):
 *  - one extraction at a time, foreground first ([PriorityGate]); no speculative duplicate hedges;
 *  - identical requests share one extraction, canceled when no caller still wants it;
 *  - nothing is sent while the YouTube cooldown waits, and only one trial goes out when it ends;
 *  - each lookup draws on its intent's [RequestBudget] (deadline, request credits, recoveries);
 *  - links are cached per video, quality/client profile and network route; a refresh never joins
 *    older work, and older work can't overwrite a refreshed link.
 */
object DesktopStreamResolver {
    @Volatile var enabledClients: Set<String> = setOf("VISIONOS")
        set(value) {
            if (value.isEmpty() || value == field) return
            field = value
            org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor.setFetchIosClient("IOS" in value)
        }
    @Volatile var quality = com.auralis.music.domain.model.AudioQuality.HIGH
    private const val TAG = "DesktopStreamResolver"
    private const val EXTRACTION_BUDGET_MS = 15_000L
    /** Pause before the one in-lookup retry after a transient failure. */
    private const val RETRY_DELAY_MS = 1_500L
    /** Candidate streams checked per extraction (each check is one small media request). */
    private const val MAX_PROBES = 3
    // libVLC sends this user agent too (VlcAudioPlayer).
    private const val PROBE_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    /** The quality and client set a link was resolved for; links from another profile aren't reused. */
    val profileKey: String get() = "${quality.name}:${enabledClients.sorted().joinToString("+")}"

    // ---- media edge hosts that refused us --------------------------------------------------

    /** Media edge hosts (one googlevideo node each) that refused a link recently, skipped for a while. */
    private val failedHosts = ConcurrentHashMap<String, Long>()
    private const val HOST_BLOCK_MS = 5 * 60 * 1000L

    /**
     * The edge host behind [url] refused it without expiry evidence; prefer other hosts for a while.
     * This is one node, never the whole CDN, and never a reason to stop playback of other songs.
     */
    fun markHostFailed(url: String) {
        val host = hostOf(url) ?: return
        failedHosts[host] = System.currentTimeMillis()
        Log.w(TAG, "Preferring other media hosts than $host for 5 min after a refusal")
    }

    private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host }.getOrNull()

    private fun hostBlocked(url: String): Boolean {
        val host = hostOf(url) ?: return false
        val since = failedHosts[host] ?: return false
        if (System.currentTimeMillis() - since > HOST_BLOCK_MS) {
            failedHosts.remove(host)
            return false
        }
        return true
    }

    // ---- caches ------------------------------------------------------------------------------

    private val links = StreamLinkCache({
        runCatching { java.io.File(com.auralis.music.util.desktopAppDataDir(), "stream-links.properties") }.getOrNull()
    })

    /** Per-video refresh generation: a refresh bumps it so older lookups can't publish over it. */
    private val epochs = ConcurrentHashMap<String, AtomicLong>()
    private fun epochOf(target: String) = epochs.computeIfAbsent(target) { AtomicLong() }

    /** The link already known for [videoId], without asking YouTube; null when there's none. */
    fun cachedUrl(videoId: String): String? = cachedResource(videoId)?.url

    fun cachedResource(videoId: String): PlaybackResource? {
        val target = RecordingMatches.getMatchedVideoId(videoId) ?: videoId
        return links.get(target, profileKey, RouteScope.current())?.takeIf { !hostBlocked(it.url) }
    }

    /** Videos YouTube refuses to anonymous viewers (age/account restricted), so they aren't retried. */
    private val restricted = ConcurrentHashMap<String, Long>()
    private const val RESTRICTED_MS = 6 * 3600_000L

    private fun isRestricted(target: String): Boolean {
        val at = restricted[target] ?: return false
        if (System.currentTimeMillis() - at > RESTRICTED_MS) { restricted.remove(target); return false }
        return true
    }

    /** Companion video per video (a link, or "" for none) for this run; filled by audio lookups too. */
    private data class VideoEntry(val url: String, val expiresAtMs: Long, val route: String, val session: Long)
    private val videoCache = ConcurrentHashMap<String, VideoEntry>()

    @Volatile private var initialized = false

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                NewPipe.init(NewPipeDownloader.instance)
                initialized = true
            }
        }
    }

    internal fun initializeExtractor() = ensureInitialized()

    /** Starts NewPipe (its first extraction is slow) without waiting for it. No network request. */
    fun warmUp() {
        Thread({ runCatching { ensureInitialized() } }, "newpipe-warmup").apply { isDaemon = true }.start()
    }

    // ---- coordination ------------------------------------------------------------------------

    private val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val gate = PriorityGate(permits = 1, maxWaiting = 8)
    private val audioFlights = SharedFlights<String, ResolutionResult>(workers, gate)
    private val videoFlights = SharedFlights<String, String?>(workers, gate)
    /** Refreshes of one video run one at a time; a second finds the first's fresh link. */
    private val refreshLocks = ConcurrentHashMap<String, Any>()

    private suspend fun budgetFor(priority: RequestPriority, reason: String): RequestBudget =
        currentCoroutineContext()[PlaybackIntentContext]?.budget
            ?: RequestBudget(RequestBudget.newId(if (priority == RequestPriority.FOREGROUND) "f" else "b"), reason,
                deadlineMs = if (priority == RequestPriority.FOREGROUND) RequestBudget.FOREGROUND_DEADLINE_MS else RequestBudget.BACKGROUND_DEADLINE_MS)

    private fun cooldownFailure(operation: String): ResolutionResult.Deferred? {
        val snapshot = YouTubeBackoff.cooldown.snapshot() ?: return null
        val wait = YouTubeBackoff.retryInMs()
        if (wait <= 0L) return null
        val category = when (snapshot.cause) {
            YouTubeCooldown.Cause.RATE_LIMITED -> FailureCategory.RATE_LIMITED
            YouTubeCooldown.Cause.VERIFICATION -> FailureCategory.VERIFICATION_CHALLENGE
            YouTubeCooldown.Cause.TRANSIENT -> FailureCategory.TRANSIENT
        }
        return ResolutionResult.Deferred(wait, PlaybackFailure(category, operation, retryAfterMs = wait, detail = "cooldown"))
    }

    // ---- audio -------------------------------------------------------------------------------

    /** The stream URL, or null; [resolveResult] says why. */
    suspend fun resolve(videoId: String, priority: RequestPriority = RequestPriority.FOREGROUND): String? =
        resolveResult(videoId, priority).url

    suspend fun resolveResult(videoId: String, priority: RequestPriority = RequestPriority.FOREGROUND,
        refresh: Boolean = false): ResolutionResult {
        val target = RecordingMatches.getMatchedVideoId(videoId) ?: videoId
        if (!DesktopPlaybackMatch.isVideoId(target)) return ResolutionResult.Failed(PlaybackFailure(FailureCategory.CONTENT_UNAVAILABLE, "resolve", detail = "not a YouTube video"))
        val profile = profileKey
        val route = RouteScope.current()
        if (!refresh) links.get(target, profile, route)?.takeIf { !hostBlocked(it.url) }?.let { return ResolutionResult.Resolved(it) }
        if (isRestricted(target)) return signedIn(target, profile, route)
            ?: ResolutionResult.Failed(PlaybackFailure(FailureCategory.RESTRICTED, "resolve", detail = "known restriction"))
        cooldownFailure("resolve")?.let { return it }
        val budget = budgetFor(priority, "resolve")
        if (budget.exhausted) return ResolutionResult.Failed(PlaybackFailure(FailureCategory.BUDGET_EXHAUSTED, "resolve", detail = budget.toString()))
        val epoch = epochOf(target).get()
        val key = "$target|$profile|$route|$epoch"
        val result = audioFlights.await(key, priority) { ticket ->
            withContext(PlaybackIntentContext(budget)) { lookUp(target, profile, route, epoch, ticket, budget) }
        }
        if (result is ResolutionResult.Failed && result.failure.category in SIGN_IN_HELPS)
            signedIn(target, profile, route)?.let { return it }
        return result
    }

    /** Refusals that the user's own YouTube sign-in gets past (age or account checks). */
    private val SIGN_IN_HELPS = setOf(FailureCategory.RESTRICTED, FailureCategory.LOGIN_REQUIRED)

    /**
     * The video through the user's YouTube sign-in ([DesktopSignedInStream]), checked like any other
     * stream before it's handed to the player; null when signed out or YouTube still refuses.
     */
    private suspend fun signedIn(target: String, profile: String, route: String): ResolutionResult? {
        if (!DesktopSignedInStream.usable) return null
        val stream = DesktopSignedInStream.resolve(target) ?: return null
        val status = probe(stream.url)
        if (!DesktopStreamProbe.playable(status)) {
            Log.w(TAG, "Signed-in stream for $target failed its audio check: ${FailureClassifier.classifyMedia(status, stream.url)}")
            DesktopSignedInStream.onRejected()
            return null
        }
        val resource = PlaybackResource(target, stream.url, FailureClassifier.expiresAtMs(stream.url) ?: (System.currentTimeMillis() + 3600_000L),
            stream.itag, stream.mimeType ?: PlaybackResource.mimeOf(stream.url), PlaybackResource.clenOf(stream.url), stream.client)
            .copy(profile = profile, route = route, sessionGeneration = NewPipeDownloader.sessionGeneration)
        links.put(resource)
        return ResolutionResult.Resolved(resource)
    }

    private suspend fun lookUp(target: String, profile: String, route: String, epoch: Long,
        ticket: PriorityGate.Ticket, budget: RequestBudget): ResolutionResult {
        val started = System.currentTimeMillis()
        val session = NewPipeDownloader.sessionGeneration
        val tested = mutableSetOf<String>()
        var last: PlaybackFailure? = null
        var attempt = 0
        while (true) {
            attempt++
            val extracted: Extraction = try {
                gate.withPermit(ticket) {
                    // Checked inside the gate: the cooldown may have started while this waited.
                    cooldownFailure("extract")?.let { return@withPermit Extraction.Skipped(it) }
                    if (!YouTubeBackoff.permit()) return@withPermit Extraction.Skipped(cooldownFailure("extract")
                        ?: ResolutionResult.Deferred(1_000L, PlaybackFailure(FailureCategory.RATE_LIMITED, "extract", detail = "trial in flight")))
                    extract(target, budget)
                }
            } catch (e: GateFullException) {
                return ResolutionResult.Deferred(2_000L, PlaybackFailure(FailureCategory.BUDGET_EXHAUSTED, "extract", detail = "queue full"))
            }
            val streams = when (extracted) {
                is Extraction.Skipped -> return extracted.result
                is Extraction.Failure -> { last = extracted.failure; null }
                is Extraction.Streams -> extracted.audio
            }
            if (streams != null) {
                val outcome = probeCandidates(target, streams, tested, budget)
                if (outcome is ResolutionResult.Resolved) {
                    if (NewPipeDownloader.sessionGeneration != session)
                        return ResolutionResult.Failed(PlaybackFailure(FailureCategory.TRANSIENT, "resolve", detail = "session changed"))
                    val resource = outcome.resource.copy(profile = profile, route = route, sessionGeneration = session)
                    // A refresh started meanwhile: its link wins, this one isn't published.
                    if (epochOf(target).get() == epoch) links.put(resource)
                    Log.d(TAG, "Resolved $target in ${System.currentTimeMillis() - started}ms (attempt $attempt, itag ${resource.itag} ${resource.mimeType}, client ${resource.client}) ${budget}")
                    return ResolutionResult.Resolved(resource)
                }
                last = (outcome as? ResolutionResult.Failed)?.failure ?: last
            }
            val failure = last ?: PlaybackFailure(FailureCategory.UNKNOWN, "extract")
            // One more extraction only for failures a retry can plausibly fix, and only with allowance left.
            val retryable = failure.category == FailureCategory.TRANSIENT || failure.category == FailureCategory.MEDIA_FORBIDDEN
            if (attempt >= 2 || !retryable || budget.exhausted || budget.remainingMs() < RETRY_DELAY_MS + 2_000L ||
                !budget.tryTransition("retry lookup of $target after ${failure.category}")) {
                Log.w(TAG, "No playable stream for $target after ${System.currentTimeMillis() - started}ms: $failure ${budget}")
                return ResolutionResult.Failed(failure)
            }
            delay(RETRY_DELAY_MS)
        }
    }

    private sealed interface Extraction {
        class Streams(val audio: List<AudioStream>) : Extraction
        class Failure(val failure: PlaybackFailure) : Extraction
        class Skipped(val result: ResolutionResult) : Extraction
    }

    /** One extraction: the player response's audio streams (and its video, kept for the sidebar). */
    private suspend fun extract(target: String, budget: RequestBudget): Extraction {
        ensureInitialized()
        val tag = YouTubeTraffic.Tag(budget.correlationId, budget.reason, budget)
        val timeout = minOf(EXTRACTION_BUDGET_MS, budget.remainingMs()).coerceAtLeast(1L)
        return try {
            val audio = NewPipeDownloader.cancellable(timeout, tag) {
                val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$target")
                extractor.fetchPage()
                val streams = extractor.audioStreams ?: emptyList()
                // The same response carries the video streams: remember them so the sidebar's
                // companion video needn't extract this song again.
                runCatching { rememberVideo(target, extractor) }
                streams
            }
            YouTubeBackoff.recordSuccess()
            if (audio.isEmpty()) Extraction.Failure(PlaybackFailure(FailureCategory.CONTENT_UNAVAILABLE, "extract", detail = "no audio streams"))
            else Extraction.Streams(audio)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable, not Exception: a missing class (NoClassDefFoundError) must not kill playback.
            val failure = FailureClassifier.classify(e, "extract")
            if (failure.category == FailureCategory.VERIFICATION_CHALLENGE) budget.stopControlRequests()
            when (failure.category) {
                FailureCategory.RATE_LIMITED, FailureCategory.VERIFICATION_CHALLENGE, FailureCategory.TRANSIENT -> YouTubeBackoff.trip(failure)
                FailureCategory.RESTRICTED -> restricted[target] = System.currentTimeMillis()
                else -> Unit
            }
            Log.w(TAG, "Couldn't resolve $target: $failure (${YouTubeTraffic.redact(e.message.orEmpty()).take(200)})")
            Extraction.Failure(failure)
        }
    }

    /** Checks the best candidates' first bytes; the first that is really audio wins. */
    private fun probeCandidates(target: String, streams: List<AudioStream>, tested: MutableSet<String>, budget: RequestBudget): ResolutionResult {
        var failure: PlaybackFailure? = null
        val tag = YouTubeTraffic.Tag(budget.correlationId, budget.reason, budget)
        for (stream in rankStreams(streams, tested).take(MAX_PROBES)) {
            val url = stream.content ?: continue
            if (!tested.add(url)) continue
            val status = YouTubeTraffic.withTag(tag) { probe(url) }
            if (DesktopStreamProbe.playable(status)) {
                return ResolutionResult.Resolved(PlaybackResource(target, url, FailureClassifier.expiresAtMs(url) ?: (System.currentTimeMillis() + 3600_000L),
                    stream.itag.takeIf { it > 0 }, stream.format?.mimeType ?: PlaybackResource.mimeOf(url), PlaybackResource.clenOf(url), PlaybackResource.clientOf(url)))
            }
            val classified = FailureClassifier.classifyMedia(status, url)
            failure = classified
            if (classified.category == FailureCategory.MEDIA_FORBIDDEN) markHostFailed(url)
            Log.w(TAG, "Stream for $target failed its audio check: $classified (host ${hostOf(url)}); trying another")
            if (classified.category == FailureCategory.RATE_LIMITED) break
        }
        return ResolutionResult.Failed(failure ?: PlaybackFailure(FailureCategory.CONTENT_UNAVAILABLE, "probe", detail = "no usable stream"))
    }

    /** HTTP status for the first piece of [url]; -1 when the check itself couldn't run. */
    private fun probe(url: String): Int = DesktopStreamProbe.check(
        probeClient, url, PROBE_USER_AGENT, LocalStreamRelay::offerFirstBlock)

    private val probeClient by lazy {
        com.auralis.music.data.network.NetworkClientProvider.okHttpClient.newBuilder()
            .connectTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .callTimeout(4000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
    }

    // ---- refresh -----------------------------------------------------------------------------

    /**
     * A new stream URL for [videoId] after [failedUrl] stopped working (called from the relay's
     * threads, so blocking). Concurrent refreshes of one song share the first one's result; a
     * refresh never joins an older lookup. A media refusal is not proof that the visitor session
     * is invalid; refreshing a link leaves that shared session intact.
     */
    fun refreshBlocking(videoId: String, failedUrl: String? = null, failure: PlaybackFailure? = null,
        budget: RequestBudget? = null): String? {
        val target = RecordingMatches.getMatchedVideoId(videoId) ?: videoId
        synchronized(refreshLocks.computeIfAbsent(target) { Any() }) {
            // Another caller already refreshed past the failed link.
            links.get(target, profileKey, RouteScope.current())?.takeIf { failedUrl != null && it.url != failedUrl }?.let { return it.url }
            failedUrl?.let { links.removeIfUrl(target, it) } ?: links.remove(target)
            epochOf(target).incrementAndGet()
            // Repeated media 403s do not establish that the shared visitor session is bad.
            // Do not disrupt other lookups by resetting it automatically.
            val context: kotlin.coroutines.CoroutineContext = budget?.let { PlaybackIntentContext(it) } ?: kotlin.coroutines.EmptyCoroutineContext
            return runBlocking(context) { resolveResult(videoId, RequestPriority.FOREGROUND, refresh = true).url }
        }
    }

    /** Forgets every looked-up stream (Settings' clear cache). */
    fun clearCache() {
        links.clear()
        videoCache.clear()
        restricted.clear()
        epochs.values.forEach { it.incrementAndGet() }
    }

    fun invalidate(videoId: String) {
        val target = RecordingMatches.getMatchedVideoId(videoId) ?: videoId
        links.remove(target)
        epochOf(target).incrementAndGet()
    }

    // ---- companion video ----------------------------------------------------------------------

    private fun rememberVideo(videoId: String, extractor: org.schabi.newpipe.extractor.stream.StreamExtractor) {
        // YouTube's auto-generated "Artist - Topic" uploads are one still picture of some
        // release's cover (often another album's): not a video, and it covered the real cover.
        val topic = runCatching { extractor.uploaderName }.getOrNull().orEmpty().endsWith(" - Topic")
        val url = if (topic) null else extractor.videoStreams
            .filter { (it.resolution.substringBefore('p').toIntOrNull() ?: 9999) <= 720 }
            .maxByOrNull { it.resolution.substringBefore('p').toIntOrNull() ?: 0 }?.content
        videoCache[videoId] = VideoEntry(url.orEmpty(), url?.let(FailureClassifier::expiresAtMs) ?: (System.currentTimeMillis() + 6 * 3600_000L),
            RouteScope.current(), NewPipeDownloader.sessionGeneration)
    }

    private fun cachedVideo(videoId: String): VideoEntry? =
        videoCache[videoId]?.takeIf { System.currentTimeMillis() < it.expiresAtMs - 60_000L &&
            it.route == RouteScope.current() && it.session == NewPipeDownloader.sessionGeneration }

    /**
     * Silent companion video for the exact recording. Usually known already from the song's audio
     * lookup; otherwise one background extraction, shared, behind foreground audio, and never
     * while YouTube requests are paused. Audio playback never depends on it.
     */
    suspend fun resolveVideo(videoId: String): String? {
        if (!DesktopPlaybackMatch.isVideoId(videoId)) return null
        cachedVideo(videoId)?.let { return it.url.ifEmpty { null } }
        if (cooldownFailure("video") != null) return null
        val budget = budgetFor(RequestPriority.BACKGROUND, "video")
        return videoFlights.await("video|$videoId", RequestPriority.BACKGROUND) { ticket ->
            try {
                gate.withPermit(ticket) {
                    cachedVideo(videoId)?.let { return@withPermit it.url.ifEmpty { null } }
                    if (cooldownFailure("video") != null || !YouTubeBackoff.permit()) return@withPermit null
                    ensureInitialized()
                    val tag = YouTubeTraffic.Tag(budget.correlationId, budget.reason, budget)
                    try {
                        NewPipeDownloader.cancellable(minOf(EXTRACTION_BUDGET_MS, budget.remainingMs()).coerceAtLeast(1L), tag) {
                            val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
                            extractor.fetchPage()
                            rememberVideo(videoId, extractor)
                        }
                        YouTubeBackoff.recordSuccess()
                        cachedVideo(videoId)?.url?.ifEmpty { null }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // A failed lookup isn't remembered (it may pass later), but it is classified.
                        val failure = FailureClassifier.classify(e, "video")
                        if (failure.category == FailureCategory.RATE_LIMITED || failure.category == FailureCategory.VERIFICATION_CHALLENGE ||
                            failure.category == FailureCategory.TRANSIENT) YouTubeBackoff.trip(failure)
                        Log.w(TAG, "No companion video for $videoId: $failure")
                        null
                    }
                }
            } catch (_: GateFullException) { null }
        }
    }

    /**
     * The smallest combined MP4 (usually itag 18: 360p with AAC audio) for an upload YouTube serves
     * with no audio-only stream, as it does some kids' Topic uploads. A player that reads MP4
     * (the Discord bot's) plays its sound track. Null when there's none or YouTube is paused.
     */
    suspend fun resolveMuxedAudio(videoId: String): String? {
        if (!DesktopPlaybackMatch.isVideoId(videoId) || cooldownFailure("muxed") != null) return null
        ensureInitialized()
        val budget = budgetFor(RequestPriority.FOREGROUND, "muxed")
        val tag = YouTubeTraffic.Tag(budget.correlationId, budget.reason, budget)
        return try {
            NewPipeDownloader.cancellable(minOf(EXTRACTION_BUDGET_MS, budget.remainingMs()).coerceAtLeast(1L), tag) {
                val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
                extractor.fetchPage()
                extractor.videoStreams
                    .filter { it.format?.mimeType == "video/mp4" && !it.content.isNullOrBlank() }
                    .minByOrNull { it.resolution.substringBefore('p').toIntOrNull() ?: 9999 }?.content
            }.also { if (it != null) YouTubeBackoff.recordSuccess() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "No combined stream for $videoId: ${FailureClassifier.classify(e, "muxed")}")
            null
        }
    }

    // ---- ranking -----------------------------------------------------------------------------

    private fun rankStreams(streams: List<AudioStream>, excludedUrls: Set<String> = emptySet()): List<AudioStream> {
        val usable = streams.filter { stream ->
            val client = PlaybackResource.clientOf(stream.content.orEmpty())
            !stream.content.isNullOrBlank() && !excludedUrls.contains(stream.content) && (client !in setOf("VISIONOS","IOS") || client in enabledClients)
        }
        val unblocked = usable.filter { !hostBlocked(it.content) }
        val pool = unblocked.ifEmpty { usable }

        val original = pool.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
        val candidates = original.ifEmpty { pool }

        return candidates.sortedWith(
            compareByDescending<AudioStream> { !hostBlocked(it.content) }
                .thenByDescending { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
                .thenByDescending {
                    if (quality == com.auralis.music.domain.model.AudioQuality.AUTO || quality == com.auralis.music.domain.model.AudioQuality.HIGH) {
                        kbps(it)
                    } else {
                        -kotlin.math.abs(kbps(it) - quality.targetBitrate / 1000)
                    }
                }
        )
    }

    private fun kbps(stream: AudioStream): Int = stream.averageBitrate.takeIf { it > 0 } ?: (stream.bitrate / 1000)
}
