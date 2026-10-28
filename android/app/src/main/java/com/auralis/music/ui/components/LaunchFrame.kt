package com.auralis.music.ui.components

import android.os.Build
import android.view.View
import android.view.ViewTreeObserver

/** One real frame before a handoff, with a fallback for renderers missing commit callbacks. */
internal fun View.afterLaunchFrame(action: () -> Unit): () -> Unit {
    var delivered = false
    var pendingAction: (() -> Unit)? = action
    val observer = viewTreeObserver
    lateinit var preDraw: ViewTreeObserver.OnPreDrawListener
    val deliver = Runnable {
        if (!delivered && isAttachedToWindow) {
            delivered = true
            val callback = pendingAction
            pendingAction = null
            callback?.invoke()
        }
    }
    val committed = Runnable { post(deliver) }
    preDraw = ViewTreeObserver.OnPreDrawListener {
        if (observer.isAlive) observer.removeOnPreDrawListener(preDraw)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isHardwareAccelerated) {
            observer.registerFrameCommitCallback(committed)
            postDelayed(deliver, 1_000L)
        } else {
            postOnAnimation(deliver)
        }
        true
    }
    observer.addOnPreDrawListener(preDraw)
    invalidate()
    return {
        delivered = true
        pendingAction = null
        removeCallbacks(deliver)
        removeCallbacks(committed)
        if (observer.isAlive) {
            observer.removeOnPreDrawListener(preDraw)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                observer.unregisterFrameCommitCallback(committed)
            }
        }
    }
}
