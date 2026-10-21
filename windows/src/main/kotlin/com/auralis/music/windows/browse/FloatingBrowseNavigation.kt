package com.auralis.music.windows.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.components.IconButton
import com.auralis.music.ui.i18n.Strings
import com.auralis.music.ui.i18n.str

/** Navigation belongs to the page viewport, above both scrolling content and page transitions. */
@Composable
internal fun FloatingBrowseNavigation(onBack: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize()) {
        content()
        IconButton(onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 8.dp)
                .size(40.dp).clip(CircleShape)
                .background(Color(0xFF202024).copy(alpha = .72f))
                .border(1.dp, Color.White.copy(alpha = .16f), CircleShape)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, str(Strings.back), tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}
