package com.auralis.music.windows

import com.auralis.music.ui.i18n.desktopText

import com.auralis.music.ui.components.IconButton
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import com.auralis.music.ui.components.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class SidebarPage(private val englishLabel: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Home), LIBRARY("Library", Icons.Outlined.LibraryMusic),
    HISTORY("History", Icons.Outlined.History), DOWNLOADS("Downloads", Icons.Outlined.Download), NEW_PLAYLIST("New playlist", Icons.Outlined.Add);

    val label: String get() = desktopText(englishLabel)
}

@Composable
internal fun NavigationSidebar(selected: SidebarPage?, collapsed: Boolean, onToggle: () -> Unit, onSelect: (SidebarPage) -> Unit,
    shortcutScrollState: androidx.compose.foundation.ScrollState = rememberScrollState(), shortcuts: @Composable () -> Unit = {},
    modifier: Modifier = Modifier, contentBottomInset: androidx.compose.ui.unit.Dp = 0.dp) {
    val colours = MaterialTheme.colorScheme
    // The tint fills the whole panel (it wraps the Up Next card); only the content stops above the card.
    Column(modifier.fillMaxSize().background(colours.surface).padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 12.dp + contentBottomInset)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!collapsed) Text(desktopText("Auralis"), Modifier.weight(1f).padding(start = 12.dp), fontSize = 18.sp)
            IconButton(onClick = onToggle, modifier = Modifier.size(40.dp)) {
                Icon(if (collapsed) Icons.Outlined.ChevronRight else Icons.Outlined.ChevronLeft,
                    if (collapsed) "Expand navigation" else "Collapse navigation")
            }
        }
        Spacer(Modifier.height(12.dp))
        SidebarPage.entries.forEach { page ->
            if (page == SidebarPage.HISTORY) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colours.outlineVariant.copy(alpha = .4f))
            NavigationSidebarItem(
                page = page,
                isSelected = selected == page,
                collapsed = collapsed,
                onClick = { onSelect(page) }
            )
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(shortcutScrollState)) { shortcuts() }
    }
}

@Composable
private fun NavigationSidebarItem(
    page: SidebarPage,
    isSelected: Boolean,
    collapsed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colours = MaterialTheme.colorScheme
    val interaction = remember(page) { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)

    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected -> colours.onSurface.copy(alpha = 0.12f)
            isHovered -> colours.onSurface.copy(alpha = 0.07f)
            else -> Color.Transparent
        },
        animationSpec = tween(150),
        label = "navItemBg"
    )

    val contentColor = when {
        isSelected -> colours.onSurface
        isHovered -> colours.onSurface
        else -> colours.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(bgColor)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = page.icon,
            contentDescription = page.label,
            modifier = Modifier.size(20.dp),
            tint = contentColor
        )
        if (!collapsed) {
            Text(
                text = page.label,
                color = contentColor,
                fontSize = 14.sp
            )
        }
    }
}
