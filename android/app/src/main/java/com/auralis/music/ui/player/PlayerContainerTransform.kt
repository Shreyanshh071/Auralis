package com.auralis.music.ui.player

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.theme.AuralisDuration
import com.auralis.music.ui.theme.AuralisEasing
import com.auralis.music.ui.theme.LocalReducedMotion
import com.auralis.music.ui.theme.PlayerMotion

// ============================================================================
// 🔗 MINI-PLAYER  ->  NOW PLAYING  CONTAINER TRANSFORM
// ============================================================================
//
// The mini-player lives in the Scaffold's bottom bar and Now Playing is a
// sibling overlay of that Scaffold, so the two halves cannot be expressed as one
// AnimatedContent. They are matched by key instead, through the single
// SharedTransitionLayout that wraps the whole app in AuralisApp.
//
// Only the artwork and the title/artist block are shared. That is deliberate:
// they are the two things the eye tracks, and keeping the set small means the
// transition stays a handful of animated layers instead of a full-screen
// re-layout every frame.
//
// Every helper here degrades to a plain Modifier when either scope is missing
// (so the players still work outside a SharedTransitionLayout, e.g. in previews)
// or when the user has asked the system to remove animations.
// ============================================================================

/** Shared-element keys. Must be identical on both sides of the transition. */
private const val ArtworkKey = "auralis.player.artwork"
private const val TrackInfoKey = "auralis.player.trackInfo"

/**
 * Corner radius of the mini-player's artwork disc. Exactly half of its 36.dp box,
 * i.e. a circle.
 */
val MiniArtworkCorner: Dp = 18.dp

/** Corner radius of the full-screen artwork. */
val ExpandedArtworkCorner: Dp = 26.dp

/**
 * Tags the album artwork so it travels between the two players instead of
 * disappearing in one place and reappearing in another.
 *
 * [enabled] must only be true for the *currently playing* page: both players show
 * their artwork inside a queue [androidx.compose.foundation.pager.HorizontalPager],
 * whose neighbouring pages are composed off-screen, and two live layouts claiming
 * the same shared key at once is undefined.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun playerSharedArtwork(
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    enabled: Boolean
): Modifier {
    // MiniPlayer <-> Full Player uses unified sheet translation and opacity crossfade (matching VIVI).
    // SharedElement overlay flight across this boundary causes double-displacement and trajectory jumping.
    return Modifier
}

/**
 * Tags the title/artist block.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun playerSharedTrackInfo(
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    enabled: Boolean
): Modifier {
    // Title/artist uses unified sheet translation and opacity crossfade (matching VIVI).
    return Modifier
}

/**
 * Corner radius for the artwork while it is in flight.
 *
 * The mini disc is a circle and the full artwork is a 26.dp rounded square. The
 * shared element animates bounds but not shape, so without interpolating the
 * radius the artwork visibly pops from round to square on the first frame.
 * Both sides run the same 18.dp <-> 26.dp interpolation, in opposite directions,
 * so the shape is continuous across the hand-off.
 */
@Composable
fun playerArtworkCorner(
    animatedVisibilityScope: AnimatedVisibilityScope?,
    expanded: Boolean
): Dp {
    val restingRadius = if (expanded) ExpandedArtworkCorner else MiniArtworkCorner
    if (animatedVisibilityScope == null || LocalReducedMotion.current) return restingRadius

    val radius by animatedVisibilityScope.transition.animateDp(
        transitionSpec = {
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow
            )
        },
        label = "playerArtworkCorner"
    ) { state ->
        // Visible on the expanded side, or hidden on the collapsed side, both mean
        // "the full-screen player owns the artwork right now".
        if ((state == EnterExitState.Visible) == expanded) ExpandedArtworkCorner else MiniArtworkCorner
    }
    return radius
}

/**
 * One curve for every part of the container transform, so the artwork, the title
 * and the corner radius cannot drift apart from each other.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun rememberPlayerBoundsTransform(): BoundsTransform = remember {
    BoundsTransform { _, _ ->
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        )
    }
}
