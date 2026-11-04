package com.auralis.music.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.glass.LocalLiquidGlass
import com.auralis.music.ui.glass.liquidGlassDialog

private val MenuShape = RoundedCornerShape(22.dp)

/** The app's dropdown: real glass on liquid glass, a raised rounded card otherwise. */
@Composable
internal fun AuralisDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val glass = LocalLiquidGlass.current
    val pageView = LocalView.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = if (glass != null) modifier.liquidGlassDialog(glass, MenuShape, pageView) else modifier,
        offset = offset,
        shape = MenuShape,
        containerColor = if (glass != null) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = if (glass != null) 0.dp else 8.dp,
        border = if (glass != null) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        content = content
    )
}
