package com.auralis.music.data.network

import java.io.IOException

/**
 * Why a YouTube lookup or stream read failed. Each category gets its own response (see
 * plan.md, Phase 2): a rate limit waits out Retry-After, a bot challenge stops blind retries, an
 * expired link is refreshed without blaming anything, a player error never re-extracts, and so on.
 * Collapsing them (as "the network is blocked", or as a null) is what made one failure turn into
 * retries through a whole queue.
 */
enum class FailureCategory {
    /** HTTP 429. */
    RATE_LIMITED,
    /** An explicit "confirm you're not a bot" response or the unusual-traffic page. */
    VERIFICATION_CHALLENGE,
    /** A generic LOGIN_REQUIRED without the bot wording: not proof of an IP block or an age gate. */
    LOGIN_REQUIRED,
    /** Confirmed age, account, payment or region restriction. */
    RESTRICTED,
    /** A stream link whose `expire` time passed. */
    URL_EXPIRED,
    /** A media 403 without expiry evidence. */
    MEDIA_FORBIDDEN,
    /** Timeouts and 5xx responses. */
    TRANSIENT,
    /** Offline, DNS or socket failure. */
    NETWORK_UNAVAILABLE,
    /** libVLC/decoder/output failure. */
    PLAYER,
    /** Removed, private or otherwise unavailable media. */
    CONTENT_UNAVAILABLE,
    /** This intent used up its request or recovery allowance. */
    BUDGET_EXHAUSTED,
    CANCELED,
    UNKNOWN;

    /** Whether every YouTube request from this network would fail alike (so skipping on is pointless). */
    val networkWide: Boolean get() = this == RATE_LIMITED || this == VERIFICATION_CHALLENGE || this == NETWORK_UNAVAILABLE

    /** Whether another upload of the same recording may play when this one doesn't. */
    val alternateMayHelp: Boolean get() = this == RESTRICTED || this == CONTENT_UNAVAILABLE || this == LOGIN_REQUIRED || this == UNKNOWN
}

data class PlaybackFailure(
    val category: FailureCategory,
    /** What was being done ("extract", "media", "search"...). */
    val operation: String = "",
    val httpCode: Int? = null,
    /** Server-requested wait, when it sent one. */
    val retryAfterMs: Long? = null,
    /** Short, secret-free description for diagnostics. */
    val detail: String = ""
) {
    override fun toString() = buildString {
        append(category)
        if (operation.isNotEmpty()) append(" during ").append(operation)
        httpCode?.let { append(" (HTTP ").append(it).append(')') }
        retryAfterMs?.let { append(" retry-after=").append(it).append("ms") }
        if (detail.isNotEmpty()) append(": ").append(detail)
    }
}

/** A YouTube control-plane request was answered 429. Thrown by the downloader so extraction stops. */
class RateLimitedException(val retryAfterMs: Long?, message: String = "YouTube rate limited this network (HTTP 429)") : IOException(message)

/** The current intent may not send another control-plane request. */
class BudgetExhaustedException(message: String) : IOException(message)

/** A request was refused locally because a cooldown is in effect. */
class CooldownActiveException(val cause0: FailureCategory, val retryInMs: Long) :
    IOException("YouTube requests are paused ($cause0) for ${retryInMs / 1000}s")

object FailureClassifier {
    private val restrictedNames = setOf(
        "AgeRestrictedContentException", "PaidContentException", "GeographicRestrictionException",
        "YoutubeMusicPremiumContentException", "SoundCloudGoPlusContentException"
    )
    private val unavailableNames = setOf(
        "PrivateContentException", "AccountTerminatedException", "ContentNotAvailableException",
        "ContentNotSupportedException", "StreamNotAvailableException"
    )

    /** The category for an extractor/network exception, walking its causes and class hierarchy. */
    fun classify(error: Throwable, operation: String = ""): PlaybackFailure {
        var e: Throwable? = error
        var depth = 0
        var fallback: PlaybackFailure? = null
        while (e != null && depth++ < 8) {
            val found = classifyOne(e, operation)
            if (found != null) {
                // A specific answer from a cause beats a generic one (an IOException wrapping a 429).
                if (found.category != FailureCategory.UNKNOWN && found.category != FailureCategory.TRANSIENT) return found
                if (fallback == null) fallback = found
            }
            e = e.cause?.takeIf { it !== e }
        }
        return fallback ?: PlaybackFailure(FailureCategory.UNKNOWN, operation, detail = error.javaClass.simpleName)
    }

    private fun names(e: Throwable): List<String> = generateSequence<Class<*>>(e.javaClass) { it.superclass }.map { it.simpleName }.toList()

    private fun classifyOne(e: Throwable, operation: String): PlaybackFailure? {
        val names = names(e)
        val message = e.message.orEmpty()
        val simple = e.javaClass.simpleName
        return when {
            e is kotlinx.coroutines.CancellationException -> PlaybackFailure(FailureCategory.CANCELED, operation)
            e is RateLimitedException -> PlaybackFailure(FailureCategory.RATE_LIMITED, operation, 429, e.retryAfterMs)
            e is BudgetExhaustedException -> PlaybackFailure(FailureCategory.BUDGET_EXHAUSTED, operation, detail = message.take(120))
            e is CooldownActiveException -> PlaybackFailure(e.cause0, operation, retryAfterMs = e.retryInMs, detail = "cooldown")
            simple == "SignInConfirmNotBotException" || YouTubeBackoff.isBlockPage(message) ->
                PlaybackFailure(FailureCategory.VERIFICATION_CHALLENGE, operation, detail = simple)
            names.any { it in restrictedNames } -> PlaybackFailure(FailureCategory.RESTRICTED, operation, detail = simple)
            names.any { it in unavailableNames } -> PlaybackFailure(FailureCategory.CONTENT_UNAVAILABLE, operation, detail = simple)
            message.contains("LOGIN_REQUIRED") -> PlaybackFailure(FailureCategory.LOGIN_REQUIRED, operation, detail = simple)
            e is java.net.UnknownHostException || e is java.net.NoRouteToHostException || e is java.net.ConnectException ||
                (e is java.net.SocketException && message.contains("unreachable", ignoreCase = true)) ->
                PlaybackFailure(FailureCategory.NETWORK_UNAVAILABLE, operation, detail = simple)
            e is java.io.InterruptedIOException || e is java.net.SocketTimeoutException || e is java.net.SocketException ->
                PlaybackFailure(FailureCategory.TRANSIENT, operation, detail = simple)
            simple == "ReCaptchaException" -> PlaybackFailure(FailureCategory.VERIFICATION_CHALLENGE, operation, detail = simple)
            else -> null
        }
    }

    /**
     * A media (googlevideo) response that wasn't audio. A 403 is an expired link only when the
     * link's own `expire` time passed; otherwise it is a refusal whose cause isn't known.
     */
    fun classifyMedia(code: Int, url: String?, nowWallMs: Long = System.currentTimeMillis(), retryAfter: String? = null): PlaybackFailure {
        val expired = url?.let(::expiresAtMs)?.let { it <= nowWallMs } == true
        val category = when {
            code == 429 -> FailureCategory.RATE_LIMITED
            (code == 403 || code == 410) && expired -> FailureCategory.URL_EXPIRED
            code == 403 -> FailureCategory.MEDIA_FORBIDDEN
            code == 404 || code == 410 -> FailureCategory.URL_EXPIRED
            code >= 500 || code == -1 -> FailureCategory.TRANSIENT
            else -> FailureCategory.UNKNOWN
        }
        return PlaybackFailure(category, "media", code.takeIf { it > 0 }, RetryAfter.parseMs(retryAfter, nowWallMs))
    }

    /** A signed stream URL's `expire` parameter, in epoch milliseconds. */
    fun expiresAtMs(url: String): Long? =
        Regex("[?&]expire=([0-9]{1,12})").find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000L)
}

/** HTTP Retry-After: delta-seconds or an HTTP date. */
object RetryAfter {
    /** Longest wait a server may impose through this header; anything longer is treated as this. */
    const val MAX_MS = 6L * 3600_000L

    fun parseMs(header: String?, nowWallMs: Long = System.currentTimeMillis()): Long? {
        val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.all { it.isDigit() }) {
            // Overflowing numbers ("99999999999999999999") are still "a very long time".
            val seconds = value.toLongOrNull() ?: return MAX_MS
            return (if (seconds > MAX_MS / 1000) MAX_MS else seconds * 1000L).coerceAtLeast(0L)
        }
        val date = runCatching {
            java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        return (date - nowWallMs).coerceIn(0L, MAX_MS)
    }
}
