package com.auralis.music.ui.player

import kotlinx.coroutines.CancellationException

internal data class CoverVideo(val url: String, val spotifyId: String? = null, val attempt: Int = 0)

/** Spotify Canvas, then motion artwork; null leaves the caller's static artwork visible. */
internal suspend fun resolveCoverVideo(
    canvas: suspend () -> ResolvedCanvas?,
    animatedCover: suspend () -> String?
): CoverVideo? {
    val found = try { canvas() } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { null }
    if (found != null) return CoverVideo(found.path, found.spotifyId)
    return try { animatedCover()?.let { CoverVideo(it) } } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { null }
}
