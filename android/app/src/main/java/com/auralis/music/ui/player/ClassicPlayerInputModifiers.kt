package com.auralis.music.ui.player

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Keeps pointer coroutines out of the large ClassicPlayerView composable methods.
 * The callbacks retain the same ownership boundaries: controls signal their own
 * interaction, while only the Lyrics viewport signals a Lyrics interaction.
 */
internal fun Modifier.classicPlaybackControlsInteraction(
    onInteraction: State<() -> Unit>
): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        var wasPressed = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val isPressed = event.changes.any { it.pressed }
            if (isPressed && !wasPressed) onInteraction.value.invoke()
            wasPressed = isPressed
        }
    }
}

internal fun Modifier.classicLyricsViewportInteraction(
    currentTab: NowPlayingTab,
    onLyricsInteraction: () -> Unit
): Modifier = pointerInput(currentTab) {
    if (currentTab == NowPlayingTab.LYRICS) {
        detectTapGestures(
            onTap = { onLyricsInteraction() }
        )
    }
}

/** Clips drawing only; it never changes the measured Lyrics/Queue viewport. */
internal fun Modifier.classicPlaybackOverlayClip(
    contentTopPx: Int,
    contentClipBottomPx: Int
): Modifier = drawWithContent {
    val localClipBottom = (contentClipBottomPx - contentTopPx)
        .toFloat()
        .coerceIn(0f, size.height)
    clipRect(bottom = localClipBottom) { this@drawWithContent.drawContent() }
}
