package com.auralis.music.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.R
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.viewmodel.LyricsProviderStatus

/** Display name of a lyrics source, as the player names it ("Lyrics by …"). */
internal fun LyricsProvider.label(): String = when (this) {
    LyricsProvider.AMLL -> "AMLL"
    LyricsProvider.BETTER_LYRICS -> "BetterLyrics"
    LyricsProvider.UNISON -> "Unison"
    LyricsProvider.PAXSENIX -> "PaxSenix"
    LyricsProvider.LRCLIB -> "LRCLIB"
    LyricsProvider.KUGOU -> "KuGou"
    LyricsProvider.QQMUSIC -> "QQ Music"
    LyricsProvider.JIOSAAVN -> "JioSaavn"
    LyricsProvider.NETEASE -> "NetEase"
    LyricsProvider.GENIUS -> "Genius"
    LyricsProvider.MUSIXMATCH -> "Musixmatch"
    LyricsProvider.YOUTUBE -> "YouTube Music"
    LyricsProvider.YOUTUBE_CAPTIONS -> "YouTube captions"
    LyricsProvider.LOCAL -> "Local"
    LyricsProvider.YOULYPLUS -> "YouLy+"
}

@Composable
private fun SyncType.label(): String = when (this) {
    SyncType.RICHSYNC -> str(R.string.sync_word)
    SyncType.LINE_SYNC -> str(R.string.sync_line)
    SyncType.PLAIN -> str(R.string.sync_none)
}

private enum class ProviderRowState { CURRENT, FOUND, FETCHING, NOT_FOUND, IDLE }

/**
 * "Choose lyrics provider": every enabled source with what it has for this song.
 * Tapping one asks that source alone; lyrics it finds replace the shown ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LyricsProviderSheet(
    providers: List<LyricsProvider>,
    currentProvider: LyricsProvider?,
    currentSyncType: SyncType?,
    status: Map<LyricsProvider, LyricsProviderStatus>,
    syncTypes: Map<LyricsProvider, SyncType>,
    onPick: (LyricsProvider) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    // The source shown now is listed even when it isn't one the sheet can ask (e.g. captions).
    val rows = remember(providers, currentProvider) {
        if (currentProvider != null && currentProvider !in providers) listOf(currentProvider) + providers else providers
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF111111),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Text(
                text = str(R.string.choose_lyrics_provider),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(rows, key = { it.name }) { provider ->
                    val rowState = when {
                        provider == currentProvider -> ProviderRowState.CURRENT
                        else -> when (status[provider]) {
                            LyricsProviderStatus.FOUND -> ProviderRowState.FOUND
                            LyricsProviderStatus.FETCHING -> ProviderRowState.FETCHING
                            LyricsProviderStatus.NOT_FOUND -> ProviderRowState.NOT_FOUND
                            null -> ProviderRowState.IDLE
                        }
                    }
                    ProviderRow(
                        name = provider.label(),
                        state = rowState,
                        syncType = if (provider == currentProvider) currentSyncType else syncTypes[provider],
                        onClick = { if (rowState != ProviderRowState.CURRENT) onPick(provider) }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProviderRow(name: String, state: ProviderRowState, syncType: SyncType?, onClick: () -> Unit) {
    val background by animateColorAsState(
        targetValue = if (state == ProviderRowState.CURRENT) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.07f),
        animationSpec = tween(220),
        label = "providerRowBackground"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = state != ProviderRowState.FETCHING,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (state == ProviderRowState.CURRENT) 0.22f else 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Crossfade(targetState = state, animationSpec = tween(200), label = "providerRowIcon") { s ->
                Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                    when (s) {
                        ProviderRowState.FETCHING -> CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        else -> Icon(
                            imageVector = when (s) {
                                ProviderRowState.CURRENT, ProviderRowState.FOUND -> Icons.Rounded.Check
                                ProviderRowState.NOT_FOUND -> Icons.Rounded.Close
                                else -> Icons.Rounded.Search
                            },
                            contentDescription = null,
                            tint = Color.White.copy(alpha = if (s == ProviderRowState.NOT_FOUND) 0.5f else 0.9f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = if (state == ProviderRowState.CURRENT) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when (state) {
                    ProviderRowState.CURRENT -> syncType?.let { str(R.string.provider_status_x_y, str(R.string.current_provider), it.label()) }
                        ?: str(R.string.current_provider)
                    ProviderRowState.FOUND -> syncType?.let { str(R.string.provider_status_x_y, it.label(), str(R.string.tap_to_use)) }
                        ?: str(R.string.lyrics_found_tap_to_use)
                    ProviderRowState.FETCHING -> str(R.string.fetching_lyrics)
                    ProviderRowState.NOT_FOUND -> str(R.string.lyrics_not_found)
                    ProviderRowState.IDLE -> str(R.string.tap_to_fetch)
                },
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp,
                maxLines = 1
            )
        }
    }
}
