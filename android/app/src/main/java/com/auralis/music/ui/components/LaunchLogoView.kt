package com.auralis.music.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.auralis.music.R

/** Start Compose's entrance when the native launch cover has actually gone away. */
val LocalLaunchContentVisible = androidx.compose.runtime.staticCompositionLocalOf { true }

/** RenderThread draws the mark first; the finished mark covers the first expensive app frame. */
internal class LaunchLogoView(
    context: Context,
    private val onDrawingComplete: (() -> Unit)? = null,
    private val animationScale: () -> Float = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) 0f
        else runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
    },
    private val onRemoved: () -> Unit
) : FrameLayout(context) {
    private val mark = (context.getDrawable(R.drawable.launch_mark_animated) as AnimatedVectorDrawable).mutate()
        as AnimatedVectorDrawable
    private val icon = ImageView(context).apply {
        setImageDrawable(mark)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    private var requested = false
    private var started = false
    private var finished = false
    private var detached = false
    private var fading = false
    private var contentReady = onDrawingComplete == null
    private var scale = 1f
    private var completedAt = 0L
    private var cancelStartFrame: (() -> Unit)? = null
    private val removeOverlay = Runnable { (parent as? ViewGroup)?.removeView(this) }
    private val finishDrawing = Runnable { completeDrawing() }
    private val fadeOverlay = Runnable { fadeIfReady() }
    private val startDrawing = Runnable {
        if (!started && !detached && isAttachedToWindow && isShown) {
            started = true
            scale = animationScale().let { if (it.isFinite()) it.coerceAtLeast(0f) else 1f }
            if (scale == 0f) {
                icon.setImageResource(R.drawable.launch_mark_complete)
                // Reduced motion still presents the complete identity, rather than just three dots.
                postDelayed(finishDrawing, 160L)
            } else {
                mark.start()
                // A missing platform callback must show the final mark, never cut off its drawing.
                postDelayed(finishDrawing, (850L * scale.coerceAtLeast(1f)).toLong() + 1_000L)
            }
        }
    }
    private val callback = object : Animatable2.AnimationCallback() {
        override fun onAnimationEnd(drawable: Drawable?) { completeDrawing() }
    }

    init {
        setBackgroundColor(context.getColor(R.color.launch_background))
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val side = (288f * resources.displayMetrics.density).toInt()
        addView(icon, LayoutParams(side, side, Gravity.CENTER))
        mark.registerAnimationCallback(callback)
    }

    fun reveal() {
        requested = true
        scheduleDrawing()
    }

    private fun scheduleDrawing() {
        if (!requested || started || detached || !isAttachedToWindow || !isShown || cancelStartFrame != null) return
        cancelStartFrame = afterLaunchFrame {
            cancelStartFrame = null
            postOnAnimation(startDrawing)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleDrawing()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) scheduleDrawing()
    }

    private fun completeDrawing() {
        if (finished || detached) return
        finished = true
        removeCallbacks(finishDrawing)
        mark.unregisterAnimationCallback(callback)
        mark.stop()
        icon.setImageResource(R.drawable.launch_mark_complete)
        completedAt = SystemClock.uptimeMillis()
        onDrawingComplete?.invoke()
        fadeIfReady()
    }

    /** Called only after the actual app UI has rendered its first frame. */
    fun contentReady() {
        contentReady = true
        fadeIfReady()
    }

    private fun fadeIfReady() {
        if (!finished || !contentReady || fading || detached) return
        val hold = completedAt + 100L - SystemClock.uptimeMillis()
        if (hold > 0L) {
            removeCallbacks(fadeOverlay)
            postDelayed(fadeOverlay, hold)
            return
        }
        fading = true
        if (scale == 0f) {
            removeOverlay.run()
        } else {
            // Keep the backdrop opaque until removal, so Home cannot flash behind the logo.
            icon.animate().alpha(0f).setDuration(160L).withEndAction(removeOverlay).start()
            postDelayed(removeOverlay, (160L * scale.coerceAtLeast(1f)).toLong() + 500L)
        }
    }

    override fun onDetachedFromWindow() {
        detached = true
        cancelStartFrame?.invoke()
        removeCallbacks(startDrawing)
        removeCallbacks(finishDrawing)
        removeCallbacks(fadeOverlay)
        removeCallbacks(removeOverlay)
        mark.unregisterAnimationCallback(callback)
        mark.stop()
        icon.animate().cancel()
        onRemoved()
        super.onDetachedFromWindow()
    }
}
