package com.auralis.music.ui.history

import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.ui.platform.LocalContext
import com.auralis.music.data.datastore.PrivacyDataStore
import com.auralis.music.domain.model.PrivacySettings
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import com.auralis.music.ui.components.SwipeableTrackContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.HistoryEntry
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.EqualizerBars
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.theme.dynamicSurface
import com.auralis.music.domain.recommendations.TrackDeduplicator
import com.auralis.music.ui.components.bottomChromePadding
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

val HISTORY_LIME: Color
    @Composable get() = MaterialTheme.dynamicPrimary
val HISTORY_CARD_BG: Color
    @Composable get() = MaterialTheme.dynamicSurface

/**
 * Fullscreen Listening History Sheet opened from the top header history button.
 * Lists played songs by day under a pinned bar, with clear history and instant play.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    history: List<HistoryEntry>,
    currentTrack: Track? = null,
    currentTrackId: String? = currentTrack?.id,
    isPlaying: Boolean,
    onTrackClick: (Track, List<Track>) -> Unit,
    onRemoveFromHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onDismiss: () -> Unit,
    onPlayNext: ((Track) -> Unit)? = null,
    onAddToQueue: ((Track) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showClearDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val privacyDataStore = remember { PrivacyDataStore(context) }
    val privacySettings by privacyDataStore.settingsFlow.collectAsState(initial = PrivacySettings())
    val pageBackground = MaterialTheme.dynamicBackground
    // Records the list alone, for the frost under the pinned bar.
    val listBackdrop = rememberLayerBackdrop()

    val pausedBanner: @Composable (Modifier) -> Unit = { bannerModifier ->
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
            modifier = bannerModifier
                .fillMaxWidth()
                .clickable {
                    coroutineScope.launch {
                        privacyDataStore.setPauseListenHistory(false)
                    }
                }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PauseCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = str(R.string.history_is_paused_in_privacy_settings_ta),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }

    com.auralis.music.ui.glass.OptionalGlassPage(
        background = pageBackground,
        accent = HISTORY_LIME,
        modifier = modifier.fillMaxSize().background(pageBackground),
        glowWithoutGlass = true
    ) {
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        if (history.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(top = HistoryBarHeight)) {
                if (privacySettings.pauseListenHistory) pausedBanner(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(HISTORY_LIME.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = HISTORY_LIME,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = str(R.string.no_listening_history_yet),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = str(R.string.tracks_you_listen_to_will_appear_here),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            val currentTrackFp = remember(currentTrack) { currentTrack?.let { TrackDeduplicator.getSongFingerprint(it) } }
            val days = remember(history) { history.groupBy { startOfDay(it.playedAt) }.toList() }

            LazyColumn(
                modifier = Modifier.fillMaxSize().layerBackdrop(listBackdrop),
                contentPadding = bottomChromePadding(start = 16.dp, end = 16.dp, top = HistoryBarHeight + 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                item(key = "history_summary", contentType = "summary") {
                    Column(Modifier.padding(start = 4.dp, bottom = 6.dp)) {
                        Text(
                            text = str(R.string.listening_history),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 30.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        if (privacySettings.pauseListenHistory) pausedBanner(Modifier.padding(bottom = 10.dp))
                        Text(
                            text = str(R.string.x_recently_played_songs, history.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                days.forEach { (day, entries) ->
                    item(key = "history_day_$day", contentType = "day") {
                        Text(
                            text = remember(day) { dayLabel(day) },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = HISTORY_LIME,
                            fontSize = 17.sp,
                            modifier = Modifier.animateItem().padding(start = 4.dp, top = 14.dp, bottom = 8.dp)
                        )
                    }
                    itemsIndexed(
                        items = entries,
                        key = { _, entry -> "${entry.track.id}_${entry.playedAt}" },
                        contentType = { _, _ -> "song" }
                    ) { index, entry ->
                        val track = entry.track
                        HistoryRow(
                            // Playing a song moves it to the top: it fades out here, in there, and
                            // the rows between slide over instead of snapping.
                            modifier = Modifier.animateItem(),
                            entry = entry,
                            isFirst = index == 0,
                            isLast = index == entries.lastIndex,
                            isCurrent = track.id == currentTrackId ||
                                (currentTrackFp != null && TrackDeduplicator.isDuplicateSong(TrackDeduplicator.getSongFingerprint(track), currentTrackFp, matchAlternateVersions = true)),
                            isPlaying = isPlaying,
                            onClick = { onTrackClick(track, listOf(track)) },
                            onRemove = { onRemoveFromHistory(track.id) },
                            onPlayNext = onPlayNext?.let { { it(track) } },
                            onAddToQueue = onAddToQueue?.let { { it(track) } }
                        )
                    }
                }
            }
        }
        // Pinned over the list with no bar around them. A heavy frost keeps covers passing behind
        // from flashing through.
        com.auralis.music.ui.glass.FloatingPageButton(
            Icons.Default.Close, str(R.string.close), listBackdrop,
            Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 12.dp), onClick = onDismiss
        )
        if (history.isNotEmpty()) {
            com.auralis.music.ui.glass.FloatingPageButton(
                Icons.Default.DeleteOutline, str(R.string.clear_history), listBackdrop,
                Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 12.dp)
            ) { showClearDialog = true }
        }
    }
    }

    // ── CLEAR HISTORY CONFIRMATION DIALOG ──
    if (showClearDialog) {
        com.auralis.music.ui.components.AuralisConfirmDialog(
            title = str(R.string.clear_listening_history),
            message = str(R.string.this_will_remove_all_played_tracks_from),
            confirmLabel = str(R.string.clear_history),
            onDismiss = { showClearDialog = false },
            onConfirm = {
                        onClearHistory()
                        showClearDialog = false
                    },
        )
    }
}

private val HistoryBarHeight = 72.dp

private fun startOfDay(timeMs: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = timeMs
    set(java.util.Calendar.HOUR_OF_DAY, 0)
    set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

/** "Today", "Yesterday", then the date, in the phone's language. */
private fun dayLabel(dayStartMs: Long): String = android.text.format.DateUtils.getRelativeTimeSpanString(
    dayStartMs,
    System.currentTimeMillis(),
    android.text.format.DateUtils.DAY_IN_MILLIS,
    android.text.format.DateUtils.FORMAT_ABBREV_MONTH
).toString()

/** Rows join into one rounded card per day. A soft tint, because a list of glass cards stutters. */
@Composable
private fun HistoryRow(
    modifier: Modifier = Modifier,
    entry: HistoryEntry,
    isFirst: Boolean,
    isLast: Boolean,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onPlayNext: (() -> Unit)?,
    onAddToQueue: (() -> Unit)?
) {
    val track = entry.track
    val context = LocalContext.current
    val playedTime = remember(entry.playedAt) {
        android.text.format.DateUtils.formatDateTime(context, entry.playedAt, android.text.format.DateUtils.FORMAT_SHOW_TIME)
    }
    val shape = remember(isFirst, isLast) {
        RoundedCornerShape(
            topStart = if (isFirst) 22.dp else 6.dp,
            topEnd = if (isFirst) 22.dp else 6.dp,
            bottomStart = if (isLast) 22.dp else 6.dp,
            bottomEnd = if (isLast) 22.dp else 6.dp
        )
    }
    val fill = if (isCurrent) HISTORY_LIME.copy(alpha = 0.14f) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f)
    Box(modifier) {
    SwipeableTrackContainer(onPlayNext = onPlayNext, onAddToQueue = onAddToQueue) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(fill)
                .clickable(onClick = onClick)
                .padding(start = 10.dp, top = 9.dp, bottom = 9.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkCard(
                sizeToConstraints = true,
                url = track.thumbnail,
                modifier = Modifier.size(54.dp),
                cornerRadius = 12.dp,
                contentDescription = track.title
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isCurrent) HISTORY_LIME else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${track.artist} · $playedTime",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isCurrent) {
                EqualizerBars(
                    isPlaying = isPlaying,
                    modifier = Modifier.size(18.dp),
                    color = HISTORY_LIME
                )
                Spacer(modifier = Modifier.width(2.dp))
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = str(R.string.remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
    }
}
