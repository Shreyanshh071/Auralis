package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.auralis.music.desktop.lyrics.DesktopLyricsController
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import com.auralis.music.ui.theme.*
import com.auralis.music.windows.LiquidPopupSurface

private fun LyricsProvider.displayName(): String = when (this) {
    LyricsProvider.SPICY_LYRICS -> "Spicy Lyrics"
    LyricsProvider.BETTER_LYRICS -> "BetterLyrics"
    LyricsProvider.QQMUSIC -> "QQ Music"
    LyricsProvider.KUGOU -> "KuGou"
    LyricsProvider.YOULYPLUS -> "YouLy+"
    LyricsProvider.PAXSENIX -> "PaxSenix"
    LyricsProvider.LRCLIB -> "LRCLIB"
    LyricsProvider.YOUTUBE -> "YouTube Music"
    LyricsProvider.YOUTUBE_CAPTIONS -> "YouTube captions"
    LyricsProvider.JIOSAAVN -> "JioSaavn"
    LyricsProvider.NETEASE -> "NetEase"
    LyricsProvider.MUSIXMATCH -> "Musixmatch"
    LyricsProvider.GENIUS -> "Genius"
    LyricsProvider.LOCAL -> "Local"
    else -> name
}

@Composable
internal fun LyricsProviderPicker(controller: DesktopLyricsController, onDismiss: () -> Unit) {
    val state by controller.state.collectAsState()
    val initialTrack = remember { state.trackId }
    var pending by remember { mutableStateOf<LyricsProvider?>(null) }
    LaunchedEffect(state.trackId, state.lyrics?.provider, state.providerStatus) {
        if (state.trackId != initialTrack || (pending != null && state.lyrics?.provider == pending &&
            state.providerStatus[pending] == DesktopLyricsController.ProviderStatus.FOUND)) onDismiss()
    }
    LyricsProviderPickerContent(controller.selectableProviders(), state, onPick = {
        pending = it; controller.pickProvider(it)
    }, onDismiss = onDismiss)
}

@Composable
internal fun LyricsProviderPickerContent(providers: List<LyricsProvider>, state: DesktopLyricsController.State,
    onPick: (LyricsProvider) -> Unit, onDismiss: () -> Unit) {
    val glass = LocalAppearanceSettings.current.liquidGlass
    val surface = MaterialTheme.dynamicSurface
    val accent = MaterialTheme.dynamicPrimary
    val ink = if (glass) { if (surface.luminance() > .5f) Color.Black else Color.White }
        else MaterialTheme.dynamicOnSurface
    val rows = (listOfNotNull(state.lyrics?.provider) + providers).distinct()
    val content: @Composable () -> Unit = {
        Column(Modifier.width(380.dp).padding(16.dp).testTag("lyrics-provider-picker")) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Choose lyrics provider", Modifier.weight(1f), color = ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.Close, "Close", tint = ink, modifier = Modifier.size(18.dp))
                }
            }
            LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(rows, key = { it.name }) { provider ->
                    val selected = state.lyrics?.provider == provider
                    val status = state.providerStatus[provider]
                    val busy = status == DesktopLyricsController.ProviderStatus.FETCHING
                    val sync = if (selected) state.lyrics?.syncType else state.providerSyncTypes[provider]
                    val syncLabel = when (sync) { SyncType.RICHSYNC -> "Word-synced"; SyncType.LINE_SYNC -> "Line-synced"; else -> "Plain lyrics" }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (glass) Color.White.copy(alpha = if (selected) .16f else .055f)
                            else lerp(surface, accent, if (selected) .18f else .055f))
                        .clickable(enabled = !busy && !selected && provider in providers) { onPick(provider) }
                        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = ink, strokeWidth = 2.dp)
                        else Icon(if (selected) Icons.Rounded.Check else Icons.Rounded.Search, null,
                            tint = if (glass) ink else accent, modifier = Modifier.size(18.dp))
                        Column {
                            Text(provider.displayName(), color = ink, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                            Text(when {
                                selected -> "Current provider · $syncLabel"
                                busy -> "Fetching lyrics…"
                                status == DesktopLyricsController.ProviderStatus.UNAVAILABLE -> "Couldn't reach provider · Click to retry"
                                status == DesktopLyricsController.ProviderStatus.NOT_FOUND -> "No lyrics found · Try another provider"
                                status == DesktopLyricsController.ProviderStatus.FOUND -> "$syncLabel · Click to use"
                                else -> "Click to fetch"
                            }, color = ink.copy(alpha = .78f), fontSize = 11.sp, lineHeight = 14.sp)
                        }
                    }
                }
            }
        }
    }
    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        if (glass) LiquidPopupSurface(tint = lerp(surface, accent, .10f).copy(alpha = .66f), content = content)
        else Surface(shape = RoundedCornerShape(18.dp), color = surface, shadowElevation = 12.dp, content = content)
    }
}
