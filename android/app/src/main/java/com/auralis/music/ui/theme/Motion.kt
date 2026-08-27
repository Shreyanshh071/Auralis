package com.auralis.music.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.IntOffset

// ============================================================================
// 🎞️ AURALIS MOTION SYSTEM
// ============================================================================
//
// One source of truth for every duration, easing curve and spring in the app.
// Screens should compose the helpers below instead of inlining magic numbers,
// so the whole app's motion can be re-tuned (or disabled) from this file.
//
// Timing budget — motion should never be something the user waits for:
//   • Micro / state flips ....... 100-180 ms   (AuralisDuration.Micro..Quick)
//   • Normal navigation ......... 180-300 ms   (AuralisDuration.Standard..Nav)
//   • Large / emphasized ........ 300-450 ms   (AuralisDuration.Emphasized..Large)
//
// Every helper prefixed `motion*` / `auralis*` collapses to an instant change
// when the user has animations turned off in system accessibility settings.
// ============================================================================

/** Canonical durations in milliseconds. */
object AuralisDuration {
    /** Colour/alpha flips on a control the finger is already touching. */
    const val Micro = 100

    /** Icon swaps, toggles, selection states. */
    const val Fast = 140

    /** Small container changes, chips, inline loading swaps. */
    const val Quick = 180

    /** Default for in-screen content changes and top-level destination slides. */
    const val Standard = 220

    /** Top-level destination changes and overlay entrances. */
    const val Nav = 260

    /** Overlay exits — leaving should read slightly faster than arriving. */
    const val NavExit = 200

    /** Mini-player -> Now Playing and other full-container transforms. */
    const val Emphasized = 340

    /** Emphasized exits. */
    const val EmphasizedExit = 280

    /** Upper bound. Only for genuinely large, once-per-session motion. */
    const val Large = 420

    /**
     * Cadence of the playback position tick (see AuralisAudioPlayer). Progress
     * animations use exactly this so each retarget lands as the next value
     * arrives and the motion reads as continuous rather than stepped.
     */
    const val ProgressTick = 250
}

/**
 * Canonical easing curves.
 *
 * [Decelerate], [Accelerate] and [Expressive] are the curves already used
 * throughout Auralis, kept bit-identical so the existing feel is preserved.
 */
object AuralisEasing {
    /** Symmetric workhorse curve for in-place changes. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Entering: fast off the mark, settles softly. */
    val Decelerate: Easing = CubicBezierEasing(0.08f, 0.82f, 0.17f, 1f)

    /** Leaving: eases in, exits quickly. */
    val Accelerate: Easing = CubicBezierEasing(0.4f, 0f, 0.8f, 1f)

    /** Slightly overshoot-flavoured curve for push/pop navigation. */
    val Expressive: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    val Linear: Easing = LinearEasing
}

/**
 * Synchronized motion specs for the Mini Player <-> Full Player (Now Playing) container transform.
 * Ensures the shared artwork, text, corner morphing, and surface enter/exit are perfectly locked.
 */
object PlayerMotion {
    const val EnterDuration = 280
    const val ExitDuration = 220
    const val ControlsExitDuration = 140

    val EnterEasing: Easing = AuralisEasing.Decelerate
    val ExitEasing: Easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1f)
}

/**
 * Canonical springs. Prefer these over tweens for anything driven directly by
 * touch, where the animation may be interrupted and retargeted mid-flight.
 */
object AuralisSpring {
    /** Press-down: firm, no overshoot, arrives immediately. */
    val TactilePress: SpringSpec<Float> = spring(
        dampingRatio = 0.80f,
        stiffness = Spring.StiffnessHigh
    )

    /** Press-release: a small amount of life on the way back to rest. */
    val TactileRelease: SpringSpec<Float> = spring(
        dampingRatio = 0.48f,
        stiffness = Spring.StiffnessMediumLow
    )

    /** Default: quick, critically damped, no wobble. */
    val Snappy: SpringSpec<Float> = spring(
        dampingRatio = 0.90f,
        stiffness = Spring.StiffnessMediumLow
    )

    /** Immediate tactile response for bottom navigation bar icons. */
    val NavIcon: SpringSpec<Float> = spring(
        dampingRatio = 0.80f,
        stiffness = Spring.StiffnessMedium
    )

    /** Soft settle for larger surfaces. */
    val Gentle: SpringSpec<Float> = spring(
        dampingRatio = 1f,
        stiffness = Spring.StiffnessMediumLow
    )

    /** Reserved for deliberate accents (play/pause). Use sparingly. */
    val Bouncy: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )

    /** Offset-typed spring with the correct visibility threshold for pixels. */
    val Offset: SpringSpec<IntOffset> = spring(
        dampingRatio = 0.90f,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = IntOffset.VisibilityThreshold
    )

    /** Generic snappy spring for non-Float animatables (Color, Dp, Size, ...). */
    fun <T> snappy(): SpringSpec<T> = spring(
        dampingRatio = 0.90f,
        stiffness = Spring.StiffnessMediumLow
    )

    /** Generic soft-settle spring. */
    fun <T> gentle(): SpringSpec<T> = spring(
        dampingRatio = 1f,
        stiffness = Spring.StiffnessMediumLow
    )
}

// ============================================================================
// ♿ REDUCED MOTION
// ============================================================================

/**
 * True when the user has asked the system to remove animations
 * (Settings > Accessibility > Remove animations, or Developer options >
 * Animator duration scale = off).
 *
 * Provided by [AuralisTheme]; defaults to false so previews animate normally.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Observes `ANIMATOR_DURATION_SCALE` — the platform signal AndroidX itself uses
 * for reduced motion — and reports whether animations should be suppressed.
 *
 * Live: toggling the setting takes effect without restarting the app.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    val resolver = context.contentResolver
    var reduced by remember {
        mutableStateOf(animatorDurationScaleIsOff(resolver))
    }

    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = animatorDurationScaleIsOff(resolver)
            }
        }
        runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer
            )
        }
        onDispose { runCatching { resolver.unregisterContentObserver(observer) } }
    }

    return reduced
}

private fun animatorDurationScaleIsOff(resolver: android.content.ContentResolver): Boolean =
    runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

// ============================================================================
// 🧩 SPEC BUILDERS (reduced-motion aware)
// ============================================================================

/** Duration that collapses to 0 ms under reduced motion. */
@Composable
fun motionDuration(durationMillis: Int): Int =
    if (LocalReducedMotion.current) 0 else durationMillis

/** Tween that becomes an instant [snap] under reduced motion. */
@Composable
fun <T> motionTween(
    durationMillis: Int = AuralisDuration.Standard,
    easing: Easing = AuralisEasing.Standard,
    delayMillis: Int = 0
): FiniteAnimationSpec<T> =
    if (LocalReducedMotion.current) snap()
    else tween(durationMillis = durationMillis, delayMillis = delayMillis, easing = easing)

/** Spring that becomes an instant [snap] under reduced motion. */
@Composable
fun <T> motionSpring(
    dampingRatio: Float = 0.90f,
    stiffness: Float = Spring.StiffnessMediumLow,
    visibilityThreshold: T? = null
): FiniteAnimationSpec<T> =
    if (LocalReducedMotion.current) snap()
    else spring(dampingRatio, stiffness, visibilityThreshold)

// ============================================================================
// 🚪 SHARED ENTER / EXIT TRANSITIONS
// ============================================================================
//
// Overlays and pushed pages pull their transitions from here so that every
// surface in the app enters and leaves the same way.

/** Full-height surface rising from the bottom (Now Playing). */
@Composable
fun auralisSheetEnter(): EnterTransition {
    if (LocalReducedMotion.current) return EnterTransition.None
    return slideInVertically(
        animationSpec = tween(PlayerMotion.EnterDuration, easing = PlayerMotion.EnterEasing),
        initialOffsetY = { fullHeight -> fullHeight / 4 }
    ) + fadeIn(tween(PlayerMotion.EnterDuration, easing = AuralisEasing.Standard))
}

/** Counterpart to [auralisSheetEnter] — seamless fade-out while shared elements travel cleanly. */
@Composable
fun auralisSheetExit(): ExitTransition {
    if (LocalReducedMotion.current) return ExitTransition.None
    return fadeOut(
        animationSpec = tween(PlayerMotion.ExitDuration, easing = AuralisEasing.Standard)
    )
}

/**
 * Unified primary navigation transition used across both top navigation (History, Listen Together, Profile)
 * and bottom navigation (Home, Search, Library).
 * Fast, responsive pop/fade: 160ms alpha fade-in + 0.988f -> 1.0f subtle scale lift.
 */
@Composable
fun auralisNavigationEnter(): EnterTransition {
    if (LocalReducedMotion.current) return EnterTransition.None
    return fadeIn(
        animationSpec = tween(160, easing = AuralisEasing.Standard)
    ) + scaleIn(
        initialScale = 0.988f,
        animationSpec = tween(160, easing = AuralisEasing.Decelerate)
    )
}

/**
 * Counterpart to [auralisNavigationEnter].
 * 120ms alpha fade-out + 1.0f -> 0.988f subtle scale settle.
 */
@Composable
fun auralisNavigationExit(): ExitTransition {
    if (LocalReducedMotion.current) return ExitTransition.None
    return fadeOut(
        animationSpec = tween(120, easing = AuralisEasing.Standard)
    ) + scaleOut(
        targetScale = 0.988f,
        animationSpec = tween(120, easing = AuralisEasing.Standard)
    )
}

/** A page pushed in from the trailing edge — unified with [auralisNavigationEnter]. */
@Composable
fun auralisPushEnter(): EnterTransition = auralisNavigationEnter()

/** Counterpart to [auralisPushEnter] — unified with [auralisNavigationExit]. */
@Composable
fun auralisPushExit(): ExitTransition = auralisNavigationExit()

/**
 * Forward entrance for full-screen detail views (Playlist Detail, Artist Page).
 * Smoothly elevates and expands into view with subtle vertical lift and scale.
 */
@Composable
fun auralisDetailForwardEnter(): EnterTransition {
    if (LocalReducedMotion.current) return EnterTransition.None
    return fadeIn(
        animationSpec = tween(260, easing = AuralisEasing.Standard)
    ) + scaleIn(
        initialScale = 0.95f,
        animationSpec = tween(260, easing = AuralisEasing.Decelerate)
    ) + slideInVertically(
        initialOffsetY = { fullHeight -> (fullHeight * 0.05f).toInt() },
        animationSpec = tween(260, easing = AuralisEasing.Decelerate)
    )
}

/**
 * Forward exit for the parent background grid when a detail screen is opening over it.
 */
@Composable
fun auralisDetailForwardExit(): ExitTransition {
    if (LocalReducedMotion.current) return ExitTransition.None
    return fadeOut(
        animationSpec = tween(200, easing = AuralisEasing.Standard)
    ) + scaleOut(
        targetScale = 0.96f,
        animationSpec = tween(200, easing = AuralisEasing.Standard)
    )
}

/**
 * Backward entrance for the parent background grid when closing a detail screen.
 */
@Composable
fun auralisDetailBackwardEnter(): EnterTransition {
    if (LocalReducedMotion.current) return EnterTransition.None
    return fadeIn(
        animationSpec = tween(220, easing = AuralisEasing.Standard)
    ) + scaleIn(
        initialScale = 0.96f,
        animationSpec = tween(220, easing = AuralisEasing.Decelerate)
    )
}

/**
 * Backward exit for a detail screen when closing back to the grid.
 */
@Composable
fun auralisDetailBackwardExit(): ExitTransition {
    if (LocalReducedMotion.current) return ExitTransition.None
    return fadeOut(
        animationSpec = tween(200, easing = AuralisEasing.Standard)
    ) + slideOutVertically(
        targetOffsetY = { fullHeight -> (fullHeight * 0.05f).toInt() },
        animationSpec = tween(200, easing = AuralisEasing.Accelerate)
    ) + scaleOut(
        targetScale = 0.95f,
        animationSpec = tween(200, easing = AuralisEasing.Accelerate)
    )
}

/**
 * In-place content swap: loading -> results -> empty, tab bodies, inline state.
 * Deliberately motion-light — a short cross-fade with a barely-there lift, so
 * changing content never reads as a whole-screen jump.
 */
@Composable
fun auralisContentEnter(): EnterTransition {
    if (LocalReducedMotion.current) return EnterTransition.None
    return fadeIn(tween(AuralisDuration.Quick, easing = AuralisEasing.Standard)) +
        scaleIn(
            initialScale = 0.985f,
            animationSpec = tween(AuralisDuration.Standard, easing = AuralisEasing.Decelerate)
        )
}

/** Counterpart to [auralisContentEnter]. */
@Composable
fun auralisContentExit(): ExitTransition {
    if (LocalReducedMotion.current) return ExitTransition.None
    return fadeOut(tween(AuralisDuration.Fast, easing = AuralisEasing.Standard)) +
        scaleOut(
            targetScale = 0.985f,
            animationSpec = tween(AuralisDuration.Fast, easing = AuralisEasing.Accelerate)
        )
}

/** Simple appear/disappear for badges, hints and inline affordances. */
@Composable
fun auralisFadeEnter(durationMillis: Int = AuralisDuration.Fast): EnterTransition {
    if (LocalReducedMotion.current) return EnterTransition.None
    return fadeIn(tween(durationMillis, easing = AuralisEasing.Standard))
}

/** Counterpart to [auralisFadeEnter]. */
@Composable
fun auralisFadeExit(durationMillis: Int = AuralisDuration.Micro): ExitTransition {
    if (LocalReducedMotion.current) return ExitTransition.None
    return fadeOut(tween(durationMillis, easing = AuralisEasing.Standard))
}

/**
 * Swapping one icon for another in place (play/pause, favourite, repeat mode).
 * The scale is small and the spring only lightly under-damped: enough to read as
 * a state change the finger caused, not enough to bounce.
 */
@Composable
fun auralisIconSwapEnter(): EnterTransition {
    if (LocalReducedMotion.current) return EnterTransition.None
    return fadeIn(tween(AuralisDuration.Fast, easing = AuralisEasing.Standard)) +
        scaleIn(
            initialScale = 0.72f,
            animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)
        )
}

/** Counterpart to [auralisIconSwapEnter]. Leaves fast so the two never overlap visibly. */
@Composable
fun auralisIconSwapExit(): ExitTransition {
    if (LocalReducedMotion.current) return ExitTransition.None
    return fadeOut(tween(AuralisDuration.Micro, easing = AuralisEasing.Standard)) +
        scaleOut(
            targetScale = 0.72f,
            animationSpec = tween(AuralisDuration.Micro, easing = AuralisEasing.Accelerate)
        )
}

/**
 * Entrance for a surface whose *content* is already being carried by a shared
 * element (the Now Playing sheet, whose artwork and title fly in from the
 * mini-player).
 *
 * Deliberately not [auralisSheetEnter]: a full-height slide drags the whole
 * destination — including its ambient glow and artwork shadow — out from under
 * the travelling artwork, which reads as an empty glowing frame chasing the
 * album cover. A one-eighth rise keeps the upward direction without competing
 * with the element that is already doing the work.
 */
@Composable
fun auralisContainerEnter(): EnterTransition {
    if (LocalReducedMotion.current) return EnterTransition.None
    return fadeIn(tween(AuralisDuration.Quick, easing = AuralisEasing.Standard)) +
        slideInVertically(
            animationSpec = tween(AuralisDuration.Emphasized, easing = AuralisEasing.Decelerate),
            initialOffsetY = { it / 8 }
        )
}

/** Counterpart to [auralisContainerEnter]. */
@Composable
fun auralisContainerExit(): ExitTransition {
    if (LocalReducedMotion.current) return ExitTransition.None
    return fadeOut(tween(AuralisDuration.NavExit, easing = AuralisEasing.Standard)) +
        slideOutVertically(
            animationSpec = tween(AuralisDuration.EmphasizedExit, easing = AuralisEasing.Accelerate),
            targetOffsetY = { it / 8 }
        )
}
