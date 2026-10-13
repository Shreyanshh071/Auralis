package com.auralis.music.windows

import com.auralis.music.ui.components.IconButton

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class SidebarPage(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Home), LIBRARY("Library", Icons.Outlined.LibraryMusic),
    HISTORY("History", Icons.Outlined.History), DOWNLOADS("Downloads", Icons.Outlined.Download), NEW_PLAYLIST("New playlist", Icons.Outlined.Add)
}

@Composable
internal fun NavigationSidebar(selected: SidebarPage?, collapsed: Boolean, onToggle: () -> Unit, onSelect: (SidebarPage) -> Unit) {
    val colours = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colours.surface).padding(horizontal = 8.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!collapsed) Text("Auralis", Modifier.weight(1f).padding(start = 12.dp), fontSize = 18.sp)
            IconButton(onClick = onToggle, modifier = Modifier.size(40.dp)) {
                Icon(if (collapsed) Icons.Outlined.ChevronRight else Icons.Outlined.ChevronLeft,
                    if (collapsed) "Expand navigation" else "Collapse navigation")
            }
        }
        Spacer(Modifier.height(12.dp))
        SidebarPage.entries.forEach { page ->
            if (page == SidebarPage.HISTORY) HorizontalDivider(Modifier.padding(vertical = 12.dp), color = colours.outlineVariant.copy(alpha = .4f))
            Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp))
                .background(if (selected == page) colours.onSurface.copy(alpha = .12f) else androidx.compose.ui.graphics.Color.Transparent)
                .clickable { onSelect(page) }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(page.icon, page.label, Modifier.size(20.dp), tint = if (selected == page) colours.onSurface else colours.onSurfaceVariant)
                if (!collapsed) Text(page.label, color = if (selected == page) colours.onSurface else colours.onSurfaceVariant, fontSize = 14.sp)
            }
        }
    }
}
