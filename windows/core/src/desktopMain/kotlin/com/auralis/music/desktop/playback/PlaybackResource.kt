package com.auralis.music.desktop.playback

import com.auralis.music.data.network.FailureCategory
import com.auralis.music.data.network.FailureClassifier
import com.auralis.music.data.network.PlaybackFailure

/**
 * A playable stream and what it is: identity enough to tell whether a replacement link is the
 * same media bytes (itag, mime, length), and the scope it was obtained in.
 */
data class PlaybackResource(
    val videoId: String,
    val url: String,
    val expiresAtWallMs: Long,
    val itag: Int? = null,
    val mimeType: String? = null,
    val contentLength: Long? = null,
    val client: String? = null,
    val profile: String = "",
    val route: String = "",
    val sessionGeneration: Long = 0L,
    val fromDisk: Boolean = false
) {
    override fun toString() = "PlaybackResource($videoId itag=$itag $mimeType clen=$contentLength client=$client disk=$fromDisk)"

    companion object {
        /** Identity read from a signed URL's own parameters (for links from elsewhere). */
        fun fromUrl(videoId: String, url: String) = PlaybackResource(
            videoId, url, FailureClassifier.expiresAtMs(url) ?: (System.currentTimeMillis() + 3600_000L),
            itagOf(url), mimeOf(url), clenOf(url), clientOf(url))

        fun itagOf(url: String): Int? = Regex("[?&]itag=([0-9]+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
        fun clenOf(url: String): Long? = Regex("[?&]clen=([0-9]+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
        fun clientOf(url: String): String? = Regex("[?&]c=([^&]+)").find(url)?.groupValues?.get(1)
        fun mimeOf(url: String): String? = Regex("[?&]mime=([^&]+)").find(url)?.groupValues?.get(1)
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }

        /**
         * Whether [fresh] can continue a stream that was reading [current] mid-song: the same
         * format (itag and mime when both say) and the same length. Equal length alone isn't
         * enough; when nothing is known either way, it isn't proof of a mismatch.
         */
        fun sameMedia(current: String, fresh: String): Boolean {
            fun differ(a: Any?, b: Any?) = a != null && b != null && a != b
            return !differ(itagOf(current), itagOf(fresh)) && !differ(mimeOf(current), mimeOf(fresh)) &&
                !differ(clenOf(current), clenOf(fresh))
        }
    }
}

/** What a lookup produced; never just a null that hides why. */
sealed interface ResolutionResult {
    data class Resolved(val resource: PlaybackResource) : ResolutionResult
    data class Failed(val failure: PlaybackFailure) : ResolutionResult
    /** Not attempted: requests are paused for [retryInMs] (the next app retry, not a promise about YouTube). */
    data class Deferred(val retryInMs: Long, val failure: PlaybackFailure) : ResolutionResult
    object Canceled : ResolutionResult { override fun toString() = "Canceled" }

    val url: String? get() = (this as? Resolved)?.resource?.url

    val category: FailureCategory? get() = when (this) {
        is Resolved -> null
        is Failed -> failure.category
        is Deferred -> failure.category
        Canceled -> FailureCategory.CANCELED
    }
}
