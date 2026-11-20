package com.auralis.music.data.service

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.auralis.music.data.service.automix.*
import com.auralis.music.domain.model.PlayerSettings
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.*

/** Two decoders overlap; the session's primary player stays stable for notifications and headsets.
 * Inspired by ArchiveTune's secondary-player crossfade and its GPL Automix analysis/planner.
 * All player access is on the owning main-thread scope. A late/failed prepare leaves normal
 * gapless playback untouched; the outgoing player is never faded before the incoming one is ready.
 */
@UnstableApi
internal class AudioTransitionController(
    private val primary: ExoPlayer,
    private val primaryFilter: AutoMixFilterProcessor,
    private val scope: CoroutineScope,
    private val createIncoming: (AutoMixFilterProcessor) -> ExoPlayer,
    private val createAnalyzer: () -> AutoMixAnalyzer,
    private val eligible: () -> Boolean,
    private val onTransition: (Track, Long) -> Unit,
    private val onFinished: () -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime
) {
    private var analyzer: AutoMixAnalyzer? = null
    private var incoming: ExoPlayer? = null
    private var incomingFilter: AutoMixFilterProcessor? = null
    private var job: Job? = null
    private var sourceKey: String? = null
    private var attempted = false
    private var cueMs = 0L
    private var baseVolume = 1f
    private var outgoingEnded = false
    var isHandoff = false
        private set
    val isMixing: Boolean get() = job != null

    private val primaryListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) return
            // pauseAtEndOfMediaItems firing a few ms before the fade loop finishes is the outgoing
            // song ending, not the user pausing: hand off instead of dropping the incoming audio.
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && job != null && !isHandoff) {
                outgoingEnded = true
                return
            }
            cancel()
        }
        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) cancel()
        }
        override fun onPlayerError(error: PlaybackException) { cancel() }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK && !isHandoff) cancel()
        }
    }

    init { primary.addListener(primaryListener) }

    fun tick(current: Track, next: Track?, queueIndex: Int, settings: PlayerSettings) {
        if (!eligible() || !(settings.crossfadeEnabled || settings.automixEnabled)) {
            cancel()
            return
        }
        if (job != null) return
        val currentItem = primary.currentMediaItem ?: return
        val duration = primary.duration
        if (duration == C.TIME_UNSET || duration <= 0L || !primary.isPlaying) return
        // Start analysing the outgoing song immediately, even while its successor is still being
        // resolved/preloaded. Waiting for the next MediaItem delayed both analyses.
        val analysis = if (settings.automixEnabled) analyzer ?: createAnalyzer().also { analyzer = it } else null
        currentItem.localConfiguration?.uri?.let { analysis?.request(current.id, it, duration) }
        if (next == null) { cancel(); return }
        val key = "$queueIndex:${current.id}:${next.id}"
        if (sourceKey != key) {
            cancel()
            sourceKey = key
            attempted = false
        }
        if (attempted || !primary.isPlaying || primary.currentMediaItemIndex + 1 >= primary.mediaItemCount) return
        if (settings.skipGaplessAlbums && AudioTransitionPolicy.sameAlbum(current, next)) return
        val nextItem = primary.getMediaItemAt(primary.currentMediaItemIndex + 1)
        nextItem.localConfiguration?.uri?.let { analysis?.request(next.id, it, next.duration * 1000L) }
        val requestedMs = if (settings.automixEnabled) 5_000L else (settings.crossfadeSeconds * 1000).toLong()
        val plan = if (settings.automixEnabled) AutoMixPlanner.plan(
            analyzer?.analysisFor(current.id), analyzer?.analysisFor(next.id), current.info(duration), next.info(next.duration * 1000L),
            primary.currentPosition, requestedMs, 1_000L, false
        ) else AutoMixPlan.fallback(requestedMs, (duration - requestedMs - 250L).coerceAtLeast(0L), "fixed fade")
        if (plan.blocked || plan.style == AutoMixStyle.GAPLESS) return
        // Missing/low-confidence analysis produces the planner's ordinary fade fallback. Respect
        // the separate Crossfade switch instead of silently fading when only AutoMix is enabled.
        if (settings.automixEnabled && !settings.crossfadeEnabled && plan.style == AutoMixStyle.EQUAL_POWER) return
        val startMs = plan.transitionStartMs.coerceAtLeast(0L)
        if (primary.currentPosition < startMs - 10_000L) return
        val player = incoming ?: run {
            val filter = AutoMixFilterProcessor()
            val created = createIncoming(filter)
            incomingFilter = filter
            incoming = created
            baseVolume = primary.volume
            created.volume = 0f
            created.playbackParameters = primary.playbackParameters
            created.skipSilenceEnabled = settings.skipSilence
            cueMs = plan.incomingCueMs.coerceAtLeast(0L)
            created.setMediaItem(nextItem, cueMs)
            created.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { if (incoming === created) cancel() }
            })
            created.prepare()
            created
        }
        if (plan.incomingCueMs.coerceAtLeast(0L) != cueMs) {
            cueMs = plan.incomingCueMs.coerceAtLeast(0L)
            player.seekTo(cueMs)
        }
        if (primary.currentPosition < startMs) return
        val nextDuration = player.duration.takeIf { it > 0L } ?: (next.duration * 1000L)
        val fadeMs = AudioTransitionPolicy.fadeDuration(plan.fadeMs, duration - primary.currentPosition, nextDuration)
        if (fadeMs < 500L) { cancel(); return }
        if (player.playbackState != Player.STATE_READY || player.bufferedPosition - player.currentPosition < minOf(1_000L, fadeMs)) return
        attempted = true
        val outgoingId = currentItem.mediaId
        val incomingId = nextItem.mediaId
        val baseRate = primary.playbackParameters
        job = scope.launch(start = CoroutineStart.LAZY) {
            val ownJob = currentCoroutineContext()[Job]
            try {
                primary.pauseAtEndOfMediaItems = true
                player.playbackParameters = PlaybackParameters(baseRate.speed * plan.incomingPlaybackRate.toFloat(), baseRate.pitch)
                player.play()
                var last = nanoTime()
                var elapsed = 0L
                outgoingEnded = false
                while (elapsed < fadeMs) {
                    delay(16)
                    if (outgoingEnded && eligible() && player.playWhenReady) break
                    if (!eligible() || !primary.playWhenReady || !player.playWhenReady ||
                        primary.currentMediaItem?.mediaId != outgoingId || primary.playbackParameters != baseRate) return@launch
                    if (primary.playbackState != Player.STATE_READY || player.playbackState != Player.STATE_READY ||
                        primary.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) return@launch
                    val now = nanoTime()
                    elapsed += ((now - last) / 1_000_000.0 * baseRate.speed).toLong()
                    last = now
                    val p = (elapsed.toFloat() / fadeMs).coerceIn(0f, 1f)
                    val (outGain, inGain) = AudioTransitionPolicy.gains(p)
                    primary.volume = baseVolume * outGain
                    player.volume = baseVolume * inGain
                    // Ease the incoming tempo back to the user's rate before the session handoff.
                    player.playbackParameters = PlaybackParameters(
                        baseRate.speed * (plan.incomingPlaybackRate.toFloat() * (1f - p) + p), baseRate.pitch
                    )
                    applyFilters(plan, p)
                }
                // Finish the curve even if the outgoing song ended a frame early.
                player.volume = baseVolume
                player.playbackParameters = baseRate
                // Keep the incoming decoder audible until the stable session player is ready.
                isHandoff = true
                primaryFilter.open()
                incomingFilter?.open()
                val nextIndex = primary.currentMediaItemIndex + 1
                if (nextIndex >= primary.mediaItemCount || primary.getMediaItemAt(nextIndex).mediaId != incomingId) return@launch
                primary.volume = 0f
                primary.pauseAtEndOfMediaItems = false
                primary.seekTo(nextIndex, player.currentPosition)
                primary.play()
                onTransition(next, player.currentPosition)
                fun valid() = eligible() && primary.playWhenReady && primary.currentMediaItem?.mediaId == incomingId
                suspend fun ready(): Boolean = withTimeoutOrNull(8_000L) {
                    while (primary.playbackState != Player.STATE_READY || !primary.isPlaying) {
                        if (!valid()) return@withTimeoutOrNull false
                        delay(20)
                    }
                    true
                } ?: false
                suspend fun drift(): Long? {
                    // The audio clock reports the seek position until the AudioTrack is really running.
                    delay(HANDOFF_SETTLE_MS)
                    val samples = LongArray(5)
                    for (i in samples.indices) {
                        if (!valid()) return null
                        samples[i] = player.currentPosition - primary.currentPosition
                        if (i < samples.lastIndex) delay(16)
                    }
                    return AudioTransitionPolicy.medianDrift(samples)
                }
                if (!ready()) return@launch
                // The first seek always lands behind the overlap decoder by however long the session
                // player took to become ready (the overlap kept playing meanwhile). Re-seek with a
                // measured lead until both decoders play the same audio before any gain moves, or
                // the swap is heard as a stutter/repeated word.
                var drift = drift() ?: return@launch
                var lead = 0L
                var realigns = 0
                while (!AudioTransitionPolicy.handoffAligned(drift) && realigns < AudioTransitionPolicy.HANDOFF_MAX_REALIGNS) {
                    lead = AudioTransitionPolicy.nextHandoffLead(lead, drift)
                    primary.seekTo(player.currentPosition + lead)
                    if (!ready()) return@launch
                    drift = drift() ?: return@launch
                    realigns++
                }
                val swapNs = AudioTransitionPolicy.handoffSwapMs(drift) * 1_000_000f
                val handoffStart = nanoTime()
                while (true) {
                    if (!valid()) return@launch
                    val p = ((nanoTime() - handoffStart) / swapNs).coerceIn(0f, 1f)
                    // These are the same recording; complementary linear gains avoid a volume boost.
                    primary.volume = baseVolume * p
                    player.volume = baseVolume * (1f - p)
                    if (p >= 1f) break
                    delay(16)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Restore normal primary playback in finally; a transition cannot kill the queue.
            } finally {
                if (incoming === player) cleanup()
                if (job === ownJob) job = null
            }
        }
        job?.start()
    }

    private fun Track.info(durationMs: Long) = AutoMixTrackInfo(id, durationMs, title, artist, album.orEmpty())

    private fun applyFilters(plan: AutoMixPlan, progress: Float) {
        if (plan.filterSweep > 0.0) {
            primaryFilter.setCutoffs(20_000.0 - 18_500.0 * progress * plan.filterSweep, 0.0)
        }
        if (plan.bassSwap) {
            val swap = plan.bassSwapFraction.toFloat().coerceIn(.1f, .9f)
            primaryFilter.setCutoffs(20_000.0, 220.0 * ((progress - swap) / (1f - swap)).coerceIn(0f, 1f))
            incomingFilter?.setCutoffs(20_000.0, 220.0 * (1f - progress / swap).coerceIn(0f, 1f))
        }
    }

    fun cancel(resetAttempt: Boolean = false) {
        if (incoming != null || job != null) attempted = true
        job?.cancel()
        job = null
        cleanup()
        if (resetAttempt) { sourceKey = null; attempted = false }
    }

    private fun cleanup() {
        val secondary = incoming
        incoming = null
        incomingFilter = null
        isHandoff = false
        outgoingEnded = false
        primary.pauseAtEndOfMediaItems = false
        primaryFilter.open()
        if (secondary != null) {
            primary.volume = baseVolume
            runCatching { secondary.release() }
            onFinished()
        }
    }

    fun release() {
        cancel()
        primary.removeListener(primaryListener)
        stopAnalysis()
    }

    fun stopAnalysis() {
        analyzer?.close()
        analyzer = null
    }

    private companion object {
        const val HANDOFF_SETTLE_MS = 100L
    }
}
