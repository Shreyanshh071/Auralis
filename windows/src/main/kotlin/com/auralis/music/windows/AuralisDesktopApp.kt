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
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.launch

enum class DesktopDestination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Explore),
    SEARCH("Search", Icons.Outlined.Search),
    LIBRARY("Library", Icons.Outlined.GridView),
}

@Composable
fun AuralisDesktopApp(
    searchRepository: com.auralis.music.domain.repository.SearchRepository,
    playback: com.auralis.music.desktop.playback.DesktopPlaybackController?,
    lyrics: com.auralis.music.desktop.lyrics.DesktopLyricsController?
) {
    var showPlayer by remember { mutableStateOf(false) }
    // Search is the first working page on Windows, so the app opens on it.
    var destination by remember { mutableStateOf(DesktopDestination.SEARCH) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val searchState = remember { com.auralis.music.windows.search.DesktopSearchState(searchRepository, scope) }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val showUnavailable: (String) -> Unit = { message ->
        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(message) }
    }
    androidx.compose.runtime.LaunchedEffect(playback) { playback?.errors?.collect { showUnavailable(it) } }
    val queue = playback?.queue?.collectAsState()?.value
    val playerState = playback?.state?.collectAsState()?.value

    AuralisDesktopTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Sidebar(selected = destination, onSelect = { destination = it })
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when (destination) {
                            DesktopDestination.HOME -> Box(Modifier.padding(horizontal = 40.dp, vertical = 32.dp)) {
                                NotBuiltYet("Home", "Recommendations come after search and playback work.")
                            }
                            DesktopDestination.SEARCH -> com.auralis.music.windows.search.SearchScreen(
                                state = searchState,
                                currentTrackId = queue?.current?.id,
                                isPlaying = playerState == com.auralis.music.desktop.playback.VlcAudioPlayer.State.PLAYING,
                                onPlay = playback?.let { it::playFrom },
                                onUnavailable = showUnavailable
                            )
                            DesktopDestination.LIBRARY -> Box(Modifier.padding(horizontal = 40.dp, vertical = 32.dp)) {
                                NotBuiltYet("Library", "Local library comes after playback.")
                            }
                        }
                        androidx.compose.material3.SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                com.auralis.music.windows.player.NowPlayingBar(
                    playback,
                    onOpenPlayer = { if (queue?.current != null) showPlayer = true }
                )
            }
            // The full player slides up over everything, like the phone's player sheet.
            androidx.compose.animation.AnimatedVisibility(
                visible = showPlayer && playback != null && lyrics != null,
                enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(380)) { it } +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240)),
                exit = androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(320)) { it } +
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(240))
            ) {
                if (playback != null && lyrics != null) {
                    com.auralis.music.windows.player.PlayerScreen(playback, lyrics, onClose = { showPlayer = false })
                }
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
