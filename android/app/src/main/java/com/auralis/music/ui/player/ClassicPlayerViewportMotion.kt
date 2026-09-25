package com.auralis.music.ui.player

internal data class ClassicCompactShellBounds(
    val chromeBottomPx: Int,
    val headerBottomPx: Int,
    val contentTopPx: Int,
    val contentBottomPx: Int
) {
    val contentHeightPx: Int get() = contentBottomPx - contentTopPx
}

/**
 * Draw/input bounds for the playback overlay, expressed in the stable player viewport.
 * These bounds never resize the Lyrics or Queue layouts that own their LazyListState.
 */
internal data class ClassicPlaybackOverlayBounds(
    val controlsTopPx: Int,
    val controlsBottomPx: Int,
    val contentClipBottomPx: Int,
    val ownsInput: Boolean
) {
    fun contains(yPx: Float): Boolean =
        ownsInput && yPx >= controlsTopPx && yPx < controlsBottomPx
}

/** Draw-layer transform projected from the shared classic-player transition. */
internal data class ClassicViewportLayerTransform(
    val alpha: Float = 1f,
    val translationYDp: Float = 0f,
    val scale: Float = 1f
)

/**
 * Visual state for the shared-header transformation shown by the reference player.
 * [compactHeaderProgress] drives the existing hero artwork and metadata from their measured
 * Player positions into the compact top header. Timeline, transport, volume, and bottom actions
 * intentionally remain identity transforms in every mode.
 */
internal data class ClassicPlayerViewportMotionValues(
    val compactHeaderProgress: Float,
    val topBar: ClassicViewportLayerTransform,
    val lyrics: ClassicViewportLayerTransform,
    val queue: ClassicViewportLayerTransform,
    val persistentControls: ClassicViewportLayerTransform
)

internal object ClassicPlayerViewportMotion {
    // Timings: the cover flies into the header on a 500ms
    // FastOutSlowIn tween, and the body crossfades with the exit (250ms) finishing before the
    // enter (350ms) so the two never visibly overlap. No spring overshoot anywhere.
    const val HeroDurationMillis = 500
    // Cover curve: soft start, fast middle, long glide into the header ("liquid").
    // An instant-start curve made the collapse look abrupt.
    val HeroEasing = androidx.compose.animation.core.FastOutSlowInEasing
    const val ContentEnterDurationMillis = 350
    const val ContentExitDurationMillis = 250
    // Measured from a reference recording: the incoming Lyrics glide up ~110dp and the
    // incoming Queue ~40dp, decelerating into place; the outgoing layer only fades.
    const val LyricsEntryTravelDp = 110f
    const val QueueEntryTravelDp = 40f
    // The compact row follows the measured header/content split, adapted to Auralis.
    const val CompactArtworkSizeDp = 64f
    const val CompactHeaderSideInsetDp = 16f
    const val CompactArtworkTextGapDp = 12f
    const val CompactActionSizeDp = 40f
    const val LyricsControlsTimeoutMillis = 2_000L
    const val TimelineContentGapDp = 8f
    // Controls: fade + slide by a third of their height; no expand/shrink relayout.
    const val ControlsEnterDurationMillis = 400
    const val ControlsExitDurationMillis = 300
    const val ControlsSlideFraction = 3
    private const val VisibleControlsThreshold = 0.001f

    fun heroProgressTarget(tab: NowPlayingTab): Float =
        if (tab == NowPlayingTab.PLAYER) 0f else 1f

    // The title stays in place: the large title/♥/⋮ fade out as soon as the cover starts to
    // move, and the compact title/♥/⋮ fade in at their final spot only once the cover has mostly
    // landed on top of them. Nothing travels, so no text is exposed mid-flight.
    private const val ExpandedFadeEnd = 0.2f
    private const val CompactFadeStart = 0.55f
    private const val CompactFadeEnd = 0.9f

    private fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun sanitize(heroProgress: Float) = (heroProgress.takeIf(Float::isFinite) ?: 1f).coerceIn(0f, 1f)

    fun expandedMetadataAlpha(heroProgress: Float): Float =
        1f - smoothstep(sanitize(heroProgress) / ExpandedFadeEnd)

    fun compactMetadataAlpha(heroProgress: Float): Float =
        smoothstep((sanitize(heroProgress) - CompactFadeStart) / (CompactFadeEnd - CompactFadeStart))

    fun compactActionsAlpha(heroProgress: Float): Float = compactMetadataAlpha(heroProgress)

    fun expandedActionsAlpha(heroProgress: Float): Float = expandedMetadataAlpha(heroProgress)

    // One visual set of controls across tabs: the Player's copy stays opaque over the (aligned)
    // tab copy for most of the flight and only fades in the final 15%, so no ghost/double image.
    fun playerControlsAlpha(heroProgress: Float): Float {
        val p = (heroProgress.takeIf(Float::isFinite) ?: 1f).coerceIn(0f, 1f)
        return ((1f - p) / 0.15f).coerceIn(0f, 1f)
    }

    fun showBottomUtilityActions(tab: NowPlayingTab): Boolean =
        tab != NowPlayingTab.QUEUE

    // Draw-layer opacity is independent from the content's measured bounds.
    fun controlsOpacity(revealProgress: Float): Float =
        (revealProgress.takeIf(Float::isFinite) ?: 0f).coerceIn(0f, 1f)

    fun playbackControlsVisible(tab: NowPlayingTab, idleMillis: Long): Boolean =
        tab != NowPlayingTab.LYRICS || idleMillis < LyricsControlsTimeoutMillis

    fun playbackControlsVisibleForTab(
        tab: NowPlayingTab,
        lyricsControlsVisible: Boolean,
        queueControlsVisible: Boolean
    ): Boolean = when (tab) {
        NowPlayingTab.PLAYER -> true
        NowPlayingTab.LYRICS -> lyricsControlsVisible
        NowPlayingTab.QUEUE -> queueControlsVisible
    }

    /**
     * Content keeps its full measured height. While any part of the controls is visible,
     * drawing stops above their measured top and the control column owns that input region.
     * Once the controls are fully hidden, both restrictions disappear without remeasurement.
     */
    fun playbackOverlayBounds(
        viewportHeightPx: Int,
        controlsTopPx: Int,
        gapPx: Int,
        visibilityProgress: Float
    ): ClassicPlaybackOverlayBounds {
        val height = viewportHeightPx.coerceAtLeast(0)
        val top = controlsTopPx.coerceIn(0, height)
        val progress = controlsOpacity(visibilityProgress)
        val controlsAreVisible = controlsTopPx >= 0 && height > 0 && progress > VisibleControlsThreshold
        val clippedBottom = if (controlsAreVisible) {
            (top - gapPx.coerceAtLeast(0)).coerceIn(0, height)
        } else {
            height
        }
        return ClassicPlaybackOverlayBounds(
            controlsTopPx = top,
            controlsBottomPx = height,
            contentClipBottomPx = clippedBottom,
            ownsInput = controlsAreVisible
        )
    }

    /** The player must be above content only while its playback-control input layer is live. */
    fun playerLayerZIndex(visibilityProgress: Float): Float =
        if (controlsOpacity(visibilityProgress) > VisibleControlsThreshold) 4f else 2f

    /** Mirrors the fixed-width siblings of the weighted, ellipsizing metadata column. */
    fun compactTextWidthDp(viewportWidthDp: Float): Float =
        (viewportWidthDp - 2f * CompactHeaderSideInsetDp - CompactArtworkSizeDp -
            CompactArtworkTextGapDp - 2f * CompactActionSizeDp).coerceAtLeast(0f)

    /** Regions are based on measured chrome/header and playback bounds, not graphics transforms. */
    fun measuredShellBounds(
        viewportHeightPx: Int,
        chromeBottomPx: Int,
        compactHeaderHeightPx: Int,
        controlsTopPx: Int,
        controlsReserved: Boolean,
        gapPx: Int
    ): ClassicCompactShellBounds {
        val height = viewportHeightPx.coerceAtLeast(0)
        val chromeBottom = chromeBottomPx.coerceIn(0, height)
        val headerBottom = (chromeBottom + compactHeaderHeightPx.coerceAtLeast(0)).coerceIn(0, height)
        val visibleBottom = if (controlsTopPx >= 0 && controlsReserved) {
            (controlsTopPx - gapPx.coerceAtLeast(0)).coerceIn(headerBottom, height)
        } else height
        val contentBottom = visibleBottom
        return ClassicCompactShellBounds(
            chromeBottomPx = chromeBottom,
            headerBottomPx = headerBottom,
            contentTopPx = headerBottom,
            contentBottomPx = contentBottom
        )
    }

    /**
     * Lyrics and Queue are measured once behind the playback controls. Hiding or showing
     * those controls only changes their draw-layer alpha/translation, never the content
     * viewport that owns either LazyListState.
     */
    fun stableOverlayContentBounds(
        viewportHeightPx: Int,
        chromeBottomPx: Int,
        compactHeaderHeightPx: Int,
        controlsTopPx: Int,
        gapPx: Int
    ): ClassicCompactShellBounds = measuredShellBounds(
        viewportHeightPx = viewportHeightPx,
        chromeBottomPx = chromeBottomPx,
        compactHeaderHeightPx = compactHeaderHeightPx,
        controlsTopPx = controlsTopPx,
        controlsReserved = false,
        gapPx = gapPx
    )

    private val ContentEasing = androidx.compose.animation.core.FastOutSlowInEasing

    /** Incoming layer: FastOutSlowIn over the full [ContentEnterDurationMillis] window. */
    internal fun entryAlpha(fraction: Float): Float =
        ContentEasing.transform((fraction.takeIf(Float::isFinite) ?: 1f).coerceIn(0f, 1f))

    /** Outgoing layer: FastOutSlowIn compressed into the shorter [ContentExitDurationMillis]. */
    internal fun exitAlpha(fraction: Float): Float {
        val f = (fraction.takeIf(Float::isFinite) ?: 1f).coerceIn(0f, 1f)
        val exitFraction = (f * ContentEnterDurationMillis / ContentExitDurationMillis).coerceIn(0f, 1f)
        return 1f - ContentEasing.transform(exitFraction)
    }

    private val Hidden = ClassicViewportLayerTransform(alpha = 0f)

    fun entryTravelDp(tab: NowPlayingTab): Float = when (tab) {
        NowPlayingTab.LYRICS -> LyricsEntryTravelDp
        NowPlayingTab.QUEUE -> QueueEntryTravelDp
        NowPlayingTab.PLAYER -> 0f
    }

    private val Player = ClassicPlayerViewportMotionValues(
        compactHeaderProgress = 0f,
        topBar = ClassicViewportLayerTransform(),
        lyrics = Hidden,
        queue = Hidden,
        persistentControls = ClassicViewportLayerTransform()
    )

    private val Lyrics = ClassicPlayerViewportMotionValues(
        compactHeaderProgress = 1f,
        topBar = ClassicViewportLayerTransform(alpha = 0f, translationYDp = -12f),
        lyrics = ClassicViewportLayerTransform(),
        queue = Hidden,
        persistentControls = ClassicViewportLayerTransform()
    )

    private val Queue = ClassicPlayerViewportMotionValues(
        compactHeaderProgress = 1f,
        topBar = ClassicViewportLayerTransform(alpha = 0f, translationYDp = -12f),
        lyrics = Hidden,
        queue = ClassicViewportLayerTransform(),
        persistentControls = ClassicViewportLayerTransform()
    )

    fun target(tab: NowPlayingTab): ClassicPlayerViewportMotionValues = when (tab) {
        NowPlayingTab.PLAYER -> Player
        NowPlayingTab.LYRICS -> Lyrics
        NowPlayingTab.QUEUE -> Queue
    }

    /** Pure counterpart of the Compose transition, used to verify every state pair. */
    fun interpolate(
        from: NowPlayingTab,
        to: NowPlayingTab,
        fraction: Float,
        reducedMotion: Boolean = false
    ): ClassicPlayerViewportMotionValues {
        val end = target(to)
        if (reducedMotion) return end

        if (!fraction.isFinite()) return end
        if (from == to) return end
        val start = target(from)
        if (fraction == 0f) return start
        if (fraction == 1f) return end

        val clampedFraction = fraction.coerceIn(0f, 1f)
        val compactHeaderProgress = start.compactHeaderProgress +
            (end.compactHeaderProgress - start.compactHeaderProgress) * clampedFraction
        val topBar = lerp(start.topBar, end.topBar, clampedFraction)
        val persistentControls = lerp(start.persistentControls, end.persistentControls, clampedFraction)

        // Every pair: the destination fades in while gliding up into place (FastOutSlowIn,
        // no overshoot); the origin fades out faster and stays where it is.
        fun layer(tab: NowPlayingTab) = when (tab) {
            to -> ClassicViewportLayerTransform(
                alpha = entryAlpha(clampedFraction),
                translationYDp = entryTravelDp(tab) * (1f - entryAlpha(clampedFraction))
            )
            from -> ClassicViewportLayerTransform(alpha = exitAlpha(clampedFraction))
            else -> Hidden
        }
        val lyricsTransform = layer(NowPlayingTab.LYRICS)
        val queueTransform = layer(NowPlayingTab.QUEUE)

        return ClassicPlayerViewportMotionValues(
            compactHeaderProgress = compactHeaderProgress,
            topBar = topBar,
            lyrics = lyricsTransform,
            queue = queueTransform,
            persistentControls = persistentControls
        )
    }

    /** Continue from the rendered frame when a tab changes mid-transition. */
    fun retarget(
        start: ClassicPlayerViewportMotionValues,
        to: NowPlayingTab,
        fraction: Float,
        reducedMotion: Boolean = false
    ): ClassicPlayerViewportMotionValues {
        val end = target(to)
        if (reducedMotion || !fraction.isFinite() || fraction == 1f) return end
        if (fraction <= 0f) return start

        // Opacity and scale stay bounded while blending from the interrupted frame.
        fun layer(from: ClassicViewportLayerTransform, target: ClassicViewportLayerTransform): ClassicViewportLayerTransform {
            val interpolated = lerp(from, target, fraction)
            return interpolated.copy(
                alpha = interpolated.alpha.coerceIn(0f, 1f),
                scale = interpolated.scale.coerceIn(0.97f, 1.03f)
            )
        }

        return ClassicPlayerViewportMotionValues(
            compactHeaderProgress = (start.compactHeaderProgress +
                (end.compactHeaderProgress - start.compactHeaderProgress) * fraction.coerceIn(0f, 1f)),
            topBar = layer(start.topBar, end.topBar),
            lyrics = layer(start.lyrics, end.lyrics),
            queue = layer(start.queue, end.queue),
            persistentControls = ClassicViewportLayerTransform()
        )
    }

    private fun lerp(
        start: ClassicViewportLayerTransform,
        end: ClassicViewportLayerTransform,
        fraction: Float
    ) = ClassicViewportLayerTransform(
        alpha = start.alpha + (end.alpha - start.alpha) * fraction,
        translationYDp = start.translationYDp + (end.translationYDp - start.translationYDp) * fraction,
        scale = start.scale + (end.scale - start.scale) * fraction
    )
}

/** Direction hysteresis for Queue gestures; only changes state after deliberate travel. */
internal class ClassicQueueControlsScrollTracker(
    private val rowHeightPx: Float,
    private val thresholdPx: Float
) {
    var controlsVisible: Boolean = true
        private set
    private var previousIndex = 0
    private var previousOffset = 0
    private var accumulatedPx = 0f
    private var initialized = false

    fun update(index: Int, offset: Int, isScrolling: Boolean): Boolean? {
        if (!initialized) {
            previousIndex = index
            previousOffset = offset
            initialized = true
            return null
        }
        val delta = (index - previousIndex) * rowHeightPx + offset - previousOffset
        previousIndex = index
        previousOffset = offset
        if (!isScrolling) {
            accumulatedPx = 0f
            return null
        }
        if (index == 0 && offset < 50) {
            accumulatedPx = 0f
            return changeVisibility(true)
        }
        if (!delta.isFinite() || delta == 0f) return null
        accumulatedPx = if (accumulatedPx * delta < 0f) delta else
            (accumulatedPx + delta).coerceIn(-thresholdPx, thresholdPx)
        if (kotlin.math.abs(accumulatedPx) < thresholdPx) return null
        val show = accumulatedPx < 0f
        accumulatedPx = 0f
        return changeVisibility(show)
    }

    private fun changeVisibility(show: Boolean): Boolean? {
        if (controlsVisible == show) return null
        controlsVisible = show
        return show
    }
}
