package com.auralis.music.windows.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import com.auralis.music.ui.components.clickable

@Composable
internal fun SettingsHoverSurface(onClick: () -> Unit, modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium, color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color), border: BorderStroke? = null,
    content: @Composable () -> Unit) {
    val background by animateColorAsState(color, tween(180), label = "settings selection")
    Surface(modifier, shape, background, contentColor, border = border) {
        Box(Modifier.clickable(role = Role.Button, onClick = onClick)) { content() }
    }
}
