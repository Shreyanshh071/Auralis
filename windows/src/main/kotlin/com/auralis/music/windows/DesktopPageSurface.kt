package com.auralis.music.windows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Keep scrolling text in the normal draw pass. Glass popups capture only while open. */
@Composable
internal fun DesktopPageSurface(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier, content = content)
}
