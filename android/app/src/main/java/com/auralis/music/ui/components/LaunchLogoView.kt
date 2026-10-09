package com.auralis.music.ui.components

import android.content.Context
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.auralis.music.R

/** Fixed geometry across the system/app handoff; the vector's strokes animate on RenderThread. */
internal class LaunchLogoView(context: Context, private val onRemoved: () -> Unit) : FrameLayout(context) {
    private val mark = context.getDrawable(R.drawable.launch_mark_animated) as AnimatedVectorDrawable
    private val icon = ImageView(context).apply {
        setImageDrawable(mark)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    private var started = false
    private val callback = object : Animatable2.AnimationCallback() {
        override fun onAnimationEnd(drawable: Drawable?) {
            // Keep the backdrop opaque: fading this whole container exposes Home
            // behind a still-visible logo and reads as a flash during the handoff.
            icon.animate().alpha(0f).setDuration(160L)
                .withEndAction { (parent as? ViewGroup)?.removeView(this@LaunchLogoView) }.start()
        }
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
        if (started) return
        started = true
        // Establish a hardware-rendered first frame before starting the native vector animator.
        icon.postOnAnimation { mark.start() }
    }

    override fun onDetachedFromWindow() {
        mark.unregisterAnimationCallback(callback)
        mark.stop()
        icon.animate().cancel()
        onRemoved()
        super.onDetachedFromWindow()
    }
}
