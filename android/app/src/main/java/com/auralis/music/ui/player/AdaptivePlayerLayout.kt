package com.auralis.music.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How far the text below the artwork has been tightened to keep the artwork at full size.
 * Steps are taken in priority order — title, then artist, then the lyric preview — and only
 * after every flexible gap is already at its minimum.
 */
@Immutable
internal class PlayerFit(val step: Int) {
    val titleScale: Float get() = when (step) { 0 -> 1f; 1 -> 0.92f; else -> 0.86f }
    val artistScale: Float get() = if (step >= 2) 0.93f else 1f
    val lyricsScale: Float get() = if (step >= 3) 0.88f else 1f
    /** The last step gives the lyric preview a single line instead of two. */
    val lyricsMaxLines: Int get() = if (step >= 3) 1 else 2

    companion object {
        const val MAX_STEP = 3
        val Default = PlayerFit(0)
    }
}

internal val LocalPlayerFit = compositionLocalOf { PlayerFit.Default }

private sealed interface PlayerSlot
private class ArtworkSlot(val horizontalInset: Dp, val widthFraction: Float, val minimumFraction: Float) : PlayerSlot
private class GapSlot(val ideal: Dp, val minimum: Dp, val grow: Float, val maximum: Dp) : PlayerSlot

private class PlayerSlotModifier(val slot: PlayerSlot) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = slot
}

/**
 * The square artwork slot of an [AdaptivePlayerBody]. Its ideal side is the body width minus
 * [horizontalInset] on each side, times [widthFraction]. It is only ever reduced after all
 * gaps and text steps are spent, and never below [minimumFraction] of that ideal. The floor is
 * deliberately low: on a phone that genuinely cannot fit everything, a smaller cover beats
 * transport controls pushed off-screen. Typical phones never get near it.
 */
internal fun Modifier.playerArtwork(
    horizontalInset: Dp = 0.dp,
    widthFraction: Float = 1f,
    minimumFraction: Float = 0.5f
): Modifier = this.then(PlayerSlotModifier(ArtworkSlot(horizontalInset, widthFraction, minimumFraction)))

/**
 * A vertical gap that is [ideal] tall on a comfortable screen, shrinks toward [minimum] before
 * anything else changes, and takes a [grow] share of any height left once the artwork is full.
 * A gap never grows past [maximum]; what it would have taken goes to the other growing gaps,
 * so the spacing a typical phone shows stays the same on taller ones.
 */
internal fun Modifier.playerGap(ideal: Dp, minimum: Dp = ideal, grow: Float = 0f, maximum: Dp = Dp.Infinity): Modifier =
    this.then(PlayerSlotModifier(GapSlot(ideal, minimum, grow, maximum)))

/**
 * Height-budgeted player column. Everything except the artwork and the gaps keeps its natural
 * size; the artwork keeps its width-derived size while the gaps absorb the shortfall, then the
 * text tightens through [PlayerFit] steps, then the artwork gives up a little, and only a
 * viewport that still cannot fit everything scrolls.
 *
 * The fit step only ever increases for a given viewport/font scale/[fitKey], so it settles in
 * at most [PlayerFit.MAX_STEP] frames and never oscillates.
 */
@Composable
internal fun AdaptivePlayerBody(
    modifier: Modifier = Modifier,
    fitKey: Any? = null,
    maxContentWidth: Dp = 560.dp,
    content: @Composable () -> Unit
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val bounded = constraints.hasBoundedHeight
        val viewportHeightPx = if (bounded) constraints.maxHeight else Int.MAX_VALUE / 4
        var fitStep by remember(constraints.maxWidth, viewportHeightPx, fontScale, fitKey) { mutableIntStateOf(0) }
        var overflowing by remember(constraints.maxWidth, viewportHeightPx, fontScale, fitKey) { mutableStateOf(false) }
        // Captured per composition: the measure pass asks for the next step only once the
        // current one has actually been composed, so it can never skip ahead of the text.
        val composedStep = fitStep
        CompositionLocalProvider(LocalPlayerFit provides PlayerFit(composedStep)) {
            Layout(
                content = content,
                modifier = Modifier
                    .widthIn(max = maxContentWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState(), enabled = overflowing)
            ) { measurables, constraints ->
                val width = constraints.maxWidth
                val loose = Constraints(maxWidth = width)
                val slots = measurables.map { it.parentData as? PlayerSlot }

                val fixed = arrayOfNulls<androidx.compose.ui.layout.Placeable>(measurables.size)
                var fixedHeight = 0
                measurables.forEachIndexed { i, m ->
                    if (slots[i] == null) {
                        fixed[i] = m.measure(loose).also { fixedHeight += it.height }
                    }
                }

                val gaps = slots.mapNotNull { it as? GapSlot }
                val idealGaps = gaps.sumOf { it.ideal.roundToPx() }
                val minimumGaps = gaps.sumOf { it.minimum.roundToPx() }

                val art = slots.firstNotNullOfOrNull { it as? ArtworkSlot }
                val idealArt = art?.let {
                    ((width - 2 * it.horizontalInset.roundToPx()) * it.widthFraction).roundToInt().coerceAtLeast(0)
                } ?: 0
                val minimumArt = art?.let { (idealArt * it.minimumFraction).roundToInt() } ?: 0

                val plan = planPlayerHeights(
                    viewport = viewportHeightPx - fixedHeight,
                    idealArt = idealArt, minimumArt = minimumArt,
                    idealGaps = idealGaps, minimumGaps = minimumGaps
                )
                // Text tightening comes before any artwork reduction.
                if (plan.short && composedStep < PlayerFit.MAX_STEP &&
                    Snapshot.withoutReadObservation { fitStep } == composedStep
                ) fitStep = composedStep + 1
                val artSide = plan.artSide
                val gapProgress = plan.gapProgress
                val extra = plan.extra

                val gapHeights = IntArray(measurables.size)
                slots.forEachIndexed { i, slot ->
                    if (slot is GapSlot) {
                        val min = slot.minimum.roundToPx()
                        val ideal = slot.ideal.roundToPx()
                        gapHeights[i] = min + ((ideal - min) * gapProgress).roundToInt()
                    }
                }
                // Spare height goes to the growing gaps by weight; a gap that reaches its maximum
                // drops out and its share is handed to the rest on the next pass.
                var growLeft = extra
                val growing = slots.indices.filter { (slots[it] as? GapSlot)?.grow?.let { g -> g > 0f } == true }.toMutableList()
                while (growLeft > 0 && growing.isNotEmpty()) {
                    val weight = growing.sumOf { (slots[it] as GapSlot).grow.toDouble() }.toFloat()
                    var handed = 0
                    val capped = mutableListOf<Int>()
                    growing.forEach { i ->
                        val slot = slots[i] as GapSlot
                        val share = (growLeft * (slot.grow / weight)).roundToInt()
                        val room = if (slot.maximum == Dp.Infinity) Int.MAX_VALUE
                            else (slot.maximum.roundToPx() - gapHeights[i]).coerceAtLeast(0)
                        val taken = minOf(share, room)
                        gapHeights[i] += taken
                        handed += taken
                        if (taken < share || room == 0) capped += i
                    }
                    growLeft -= handed
                    growing.removeAll(capped)
                    if (handed == 0) break
                }

                val placeables = measurables.mapIndexed { i, m ->
                    when (slots[i]) {
                        is ArtworkSlot -> m.measure(Constraints.fixed(width, artSide))
                        is GapSlot -> m.measure(Constraints.fixed(0, gapHeights[i]))
                        null -> fixed[i]!!
                    }
                }
                val contentHeight = placeables.sumOf { it.height }
                // A pixel or two of gap rounding is not worth turning scrolling on for.
                val overflow = bounded && contentHeight - viewportHeightPx > 1.dp.roundToPx()
                if (overflow != Snapshot.withoutReadObservation { overflowing }) overflowing = overflow
                val height = constraints.constrainHeight(if (bounded) max(contentHeight, viewportHeightPx) else contentHeight)

                layout(width, height) {
                    var y = 0
                    placeables.forEach { p ->
                        p.placeRelative((width - p.width) / 2, y)
                        y += p.height
                    }
                }
            }
        }
    }
}

/**
 * Height split for the player body. [viewport] is the height left after every fixed child.
 * [gapProgress] runs from 0 (minimum gaps) to 1 (ideal gaps); [extra] is spare height for the
 * growing gaps; [short] means even minimum gaps cannot keep the artwork at its ideal size.
 */
internal data class PlayerHeightPlan(val artSide: Int, val gapProgress: Float, val extra: Int, val short: Boolean)

internal fun planPlayerHeights(
    viewport: Int,
    idealArt: Int,
    minimumArt: Int,
    idealGaps: Int,
    minimumGaps: Int
): PlayerHeightPlan = when {
    viewport >= idealArt + idealGaps ->
        PlayerHeightPlan(idealArt, 1f, viewport - idealArt - idealGaps, short = false)
    viewport >= idealArt + minimumGaps -> PlayerHeightPlan(
        idealArt,
        if (idealGaps == minimumGaps) 1f else (viewport - idealArt - minimumGaps).toFloat() / (idealGaps - minimumGaps),
        0,
        short = false
    )
    else -> PlayerHeightPlan((viewport - minimumGaps).coerceIn(minimumArt, idealArt), 0f, 0, short = true)
}

/** Width of the reference phone (Moto Edge 50 Fusion at its default display size). */
private const val REFERENCE_WIDTH_DP = 432f

/**
 * Scales the player's fixed spacing with the screen width, so every phone keeps the reference
 * phone's proportions between cover, lyric line and title instead of the same raw dp.
 */
@Composable
internal fun playerSpacingScale(): Float =
    (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp / REFERENCE_WIDTH_DP).coerceIn(0.8f, 1.25f)

/** Measures the actual action group before deciding whether metadata needs its own row. */
@Composable
internal fun AdaptiveTrackActions(content: @Composable () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { children, constraints ->
        check(children.size == 2)
        val gap = 8.dp.roundToPx()
        val actions = children[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val inlineWidth = (constraints.maxWidth - actions.width - gap).coerceAtLeast(0)
        // Stacking costs a whole row of height that the artwork would otherwise keep, so the
        // title only moves to its own row once it could no longer show a few words inline.
        val stacked = inlineWidth < (112.dp.toPx() * fontScale).toInt()
        val metadata = children[0].measure(constraints.copy(
            minWidth = 0, minHeight = 0,
            maxWidth = if (stacked) constraints.maxWidth else inlineWidth
        ))
        val height = if (stacked) metadata.height + gap + actions.height
            else max(metadata.height, actions.height)
        layout(constraints.maxWidth, constraints.constrainHeight(height)) {
            if (stacked) {
                metadata.placeRelative(0, 0)
                actions.placeRelative(constraints.maxWidth - actions.width, metadata.height + gap)
            } else {
                metadata.placeRelative(0, (height - metadata.height) / 2)
                actions.placeRelative(constraints.maxWidth - actions.width, (height - actions.height) / 2)
            }
        }
    }
}
