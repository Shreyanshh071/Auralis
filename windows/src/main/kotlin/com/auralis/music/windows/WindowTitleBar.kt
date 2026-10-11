package com.auralis.music.windows

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class TitleBarActions(
    val canGoBack: Boolean, val canGoForward: Boolean,
    val back: () -> Unit, val forward: () -> Unit, val refresh: () -> Unit,
    val accountLabel: String, val openAccount: () -> Unit,
    val openTogether: () -> Unit, val openSettings: () -> Unit, val home: () -> Unit,
    val canRefresh: Boolean = true
)

/** Compact desktop chrome; the surrounding WindowDraggableArea supplies window dragging. */
@Composable
fun WindowTitleBar(
    actions: TitleBarActions, title: String, icon: Painter?, maximized: Boolean,
    minimize: () -> Unit, maximize: () -> Unit, close: () -> Unit
) {
    Box(Modifier.fillMaxWidth().height(36.dp).background(Color(0xFF0D0B0E))) {
        Text(title, color = Color(0xFFD4D0D8), fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(36.dp).fillMaxHeight().clickable(onClick = actions.home), contentAlignment = Alignment.Center) {
                if (icon != null) Image(icon, "Home", Modifier.size(18.dp))
                else Icon(Icons.Outlined.Home, "Home", Modifier.size(18.dp), tint = Color(0xFFBDD66A))
            }
            ChromeButton(Icons.Outlined.ChevronLeft, "Back", actions.back, actions.canGoBack, 34)
            ChromeButton(Icons.Outlined.ChevronRight, "Forward", actions.forward, actions.canGoForward, 34)
            ChromeButton(Icons.Outlined.Refresh, "Refresh", actions.refresh, enabled = actions.canRefresh, width = 34)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxHeight().clickable(onClick = actions.openAccount).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(Icons.Outlined.AccountCircle, "Account", Modifier.size(17.dp), tint = Color(0xFFA6A0AD))
                Text(actions.accountLabel.take(22), fontSize = 12.sp, color = Color(0xFFE8E4EB))
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(13.dp), tint = Color(0xFFA6A0AD))
            }
            Box(Modifier.width(1.dp).height(16.dp).background(Color(0xFF302B34)))
            ChromeButton(Icons.Outlined.Groups, "Listen Together", actions.openTogether, width = 34)
            ChromeButton(Icons.Outlined.Settings, "Settings", actions.openSettings, width = 34)
            Box(Modifier.width(1.dp).height(16.dp).background(Color(0xFF302B34)))
            ChromeButton(Icons.Outlined.Remove, "Minimize", minimize)
            ChromeButton(if (maximized) Icons.Outlined.FilterNone else Icons.Outlined.CropSquare,
                if (maximized) "Restore" else "Maximize", maximize)
            ChromeButton(Icons.Outlined.Close, "Close", close, closeButton = true)
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(Color(0xFF242026)))
    }
}

@Composable
private fun ChromeButton(icon: ImageVector, label: String, click: () -> Unit, enabled: Boolean = true,
    width: Int = 46, closeButton: Boolean = false) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val fill = if (!hovered || !enabled) Color.Transparent else if (closeButton) Color(0xFFC42B38) else Color(0xFF29252E)
    Box(Modifier.width(width.dp).fillMaxHeight().background(fill).hoverable(interactions)
        .clickable(interactionSource = interactions, indication = null, enabled = enabled, onClick = click),
        contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(if (width == 46) 14.dp else 18.dp),
            tint = if (enabled) Color(0xFFBDB7C5) else Color(0xFF514C56))
    }
}
