package com.auralis.music.windows.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
import coil3.compose.AsyncImage
import com.auralis.music.domain.model.PlaylistResult

@Composable
internal fun YouTubeAccountAvatar(url: String?, modifier: Modifier = Modifier.size(28.dp)) {
    Box(modifier.clip(CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.AccountCircle, "Account", Modifier.fillMaxSize())
        if (!url.isNullOrBlank()) AsyncImage(url, "YouTube account photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
internal fun YouTubeAccountMenu(state: YouTubeLibraryState, onSelect: (String) -> Unit,
    onRemove: (String) -> Unit, onAdd: () -> Unit, onSettings: () -> Unit, onRefresh: () -> Unit, onDismiss: () -> Unit, expanded: Boolean = true) {
    var manage by remember { mutableStateOf(false) }
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded
    LaunchedEffect(expanded) { if (expanded) manage = false }
    if (!visibility.currentState && !visibility.targetState) return
    Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, 36), onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = expanded)) {
        AnimatedVisibility(
            visibleState = visibility,
            enter = fadeIn(tween(180)) +
                scaleIn(tween(240, easing = CubicBezierEasing(.16f, 1f, .3f, 1f)),
                    initialScale = .94f, transformOrigin = TransformOrigin(1f, 0f)) +
                slideInVertically(tween(240, easing = CubicBezierEasing(.16f, 1f, .3f, 1f))) { -it / 30 },
            exit = fadeOut(tween(130)) +
                scaleOut(tween(150), targetScale = .97f, transformOrigin = TransformOrigin(1f, 0f)) +
                slideOutVertically(tween(150)) { -it / 50 }
        ) {
            SignInGlassSurface {
                Column(Modifier.width(380.dp).heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (manage) "Manage accounts" else "Switch account", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close") }
                    }
                    state.accounts.forEach { item ->
                        Row(Modifier.fillMaxWidth().clickable { onSelect(item.id) }.padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            YouTubeAccountAvatar(item.identity.avatar, Modifier.size(40.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.identity.name, fontWeight = FontWeight.SemiBold)
                                Text(item.identity.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (manage) IconButton(onClick = { onRemove(item.id) }) { Icon(Icons.Outlined.DeleteOutline, "Remove ${item.identity.name}") }
                            else if (item.id == state.activeId) Icon(Icons.Outlined.Check, "Selected account")
                        }
                    }
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let {
                        Text(it, modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onRefresh, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Try again") }
                    }
                    AccountMenuAction("Add account", Icons.Outlined.Add, onAdd)
                    AccountMenuAction(if (manage) "Back to accounts" else "Manage accounts", Icons.Outlined.ManageAccounts) { manage = !manage }
                    AccountMenuAction("Settings", Icons.Outlined.Settings, onSettings)
                }
            }
        }
    }
}

@Composable
internal fun AccountMenuAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    Row(Modifier.fillMaxWidth().height(52.dp)
        .background(if (hovered) Color.White.copy(alpha = .10f) else Color.Transparent)
        .clickable(interactionSource = interactions, indication = null, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface)
    }
}
