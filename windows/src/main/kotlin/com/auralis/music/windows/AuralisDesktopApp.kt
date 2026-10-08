package com.auralis.music.windows

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import javax.imageio.ImageIO

enum class DesktopDestination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Explore),
    SEARCH("Search", Icons.Outlined.Search),
    LIBRARY("Library", Icons.Outlined.GridView),
}

@Composable
fun AuralisDesktopApp() {
    var destination by remember { mutableStateOf(DesktopDestination.HOME) }

    AuralisDesktopTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Sidebar(selected = destination, onSelect = { destination = it })
                    Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 40.dp, vertical = 32.dp)) {
                        when (destination) {
                            DesktopDestination.HOME -> NotBuiltYet("Home", "Recommendations come after search and playback work.")
                            DesktopDestination.SEARCH -> NotBuiltYet("Search", "Next step: YouTube Music search on Windows.")
                            DesktopDestination.LIBRARY -> NotBuiltYet("Library", "Local library comes after playback.")
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NowPlayingBar()
            }
        }
    }
}

@Composable
private fun Sidebar(selected: DesktopDestination, onSelect: (DesktopDestination) -> Unit) {
    val logo = remember {
        Thread.currentThread().contextClassLoader.getResourceAsStream("auralis_logo.png")
            ?.use { BitmapPainter(ImageIO.read(it).toComposeImageBitmap()) }
    }
    Column(
        modifier = Modifier
            .width(232.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, bottom = 20.dp)
        ) {
            if (logo != null) Image(logo, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Text("Auralis", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
        DesktopDestination.entries.forEach { item ->
            SidebarItem(item, isSelected = item == selected, onClick = { onSelect(item) })
        }
    }
}

@Composable
private fun SidebarItem(item: DesktopDestination, isSelected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) colors.surfaceContainerHigh else colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp)
    ) {
        Icon(item.icon, contentDescription = null, tint = if (isSelected) colors.primary else colors.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(
            item.label,
            fontSize = 15.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) colors.onSurface else colors.onSurfaceVariant
        )
    }
}

@Composable
private fun NotBuiltYet(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text("Not built yet. $detail", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NowPlayingBar() {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(colors.surface)
            .padding(horizontal = 20.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(colors.surfaceContainerHigh)
        ) {
            Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = colors.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Text("Nothing playing", fontSize = 15.sp, color = colors.onSurfaceVariant)
    }
}
