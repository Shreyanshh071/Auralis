package com.auralis.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.ui.glass.LocalLiquidGlass
import com.auralis.music.ui.glass.glassContentColor
import com.auralis.music.ui.glass.liquidGlassDialog
import com.auralis.music.ui.theme.dynamicPrimary
import kotlinx.coroutines.launch

private val SheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
private val RowShape = RoundedCornerShape(18.dp)

/**
 * A sheet connected to the bottom edge for choosing or adjusting something: real glass on liquid
 * glass, a raised card otherwise. [content] gets `close`, which slides the card away and then
 * runs what it's given, so a choice lands after the card has gone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuralisOptionSheet(
    onDismiss: () -> Unit,
    title: @Composable () -> Unit,
    content: @Composable ColumnScope.(close: (after: () -> Unit) -> Unit) -> Unit
) {
    val glass = LocalLiquidGlass.current
    val pageView = LocalView.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: (() -> Unit) -> Unit = { after -> scope.launch { sheetState.hide(); after() } }
    val foreground = glass?.let { glassContentColor(it.isDark) } ?: MaterialTheme.colorScheme.onSurface
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        contentColor = foreground,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = .5f),
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        shape = RoundedCornerShape(0.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    if (glass != null) Modifier.liquidGlassDialog(glass, SheetShape, pageView)
                    else Modifier.clip(SheetShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                )
                .imePadding()
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 14.dp)
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(foreground.copy(alpha = .25f))
            )
            Spacer(Modifier.height(16.dp))
            Box(Modifier.padding(horizontal = 10.dp)) { title() }
            Spacer(Modifier.height(14.dp))
            content(close)
        }
    }
}

/** One choice: a radio mark, the name, and a soft fill when it's the current one. */
@Composable
internal fun AuralisOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.dynamicPrimary
    val foreground = androidx.compose.material3.LocalContentColor.current
    val fill by animateColorAsState(if (selected) accent.copy(alpha = .2f) else Color.Transparent, label = "optionFill")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .then(
                    if (selected) Modifier.background(accent)
                    else Modifier.border(1.5.dp, foreground.copy(alpha = .35f), CircleShape)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            color = foreground,
            fontSize = 15.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/** The same sheet for popups with buttons: sliders, text fields and longer choices. */
@Composable
internal fun AuralisSheetDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null
) {
    AuralisOptionSheet(onDismiss = onDismissRequest, title = { title?.invoke() }) {
        Box(Modifier.padding(horizontal = 10.dp)) { text?.invoke() }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            dismissButton?.invoke()
            confirmButton()
        }
    }
}
