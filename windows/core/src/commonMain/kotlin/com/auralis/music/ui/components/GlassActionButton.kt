package com.auralis.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Paint state changes across the entire clipped control, including its content padding. */
@Composable
fun GlassActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(
        containerColor = Color.White.copy(alpha = .12f), contentColor = Color.White,
        disabledContainerColor = Color.White.copy(alpha = .05f), disabledContentColor = Color.White.copy(alpha = .5f)),
    content: @Composable RowScope.() -> Unit
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val base = if (enabled) colors.containerColor else colors.disabledContainerColor
    val target = if (enabled && (hovered || focused || pressed)) Color.White.copy(alpha = if (pressed) .16f else .10f).compositeOver(base) else base
    val background by animateColorAsState(target, tween(160), label = "glass button highlight")
    Row(modifier.defaultMinSize(minWidth = 58.dp, minHeight = 40.dp).clip(shape)
        .background(background).hoverable(interactions, enabled)
        .clickable(interactionSource = interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(ButtonDefaults.ContentPadding),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) colors.contentColor else colors.disabledContentColor) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
        }
    }
}
