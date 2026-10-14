package com.auralis.music.ui.search

import androidx.compose.foundation.Canvas
import com.auralis.music.ui.components.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import com.auralis.music.ui.components.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.i18n.Strings
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.theme.LocalReducedMotion

/**
 * What search shows before a search is submitted: while typing, up to three text suggestions
 * and the songs that already match; with an empty field, recent searches. Rows use [LocalSearchUi].
 */
@Composable
fun SearchSuggestionsList(
    query: String,
    suggestions: List<String>,
    liveSongs: List<Track>,
    recentQueries: List<String>,
    currentTrackId: String?,
    isPlaying: Boolean,
    contentPadding: PaddingValues,
    /** Runs a search for the text (a suggestion or a recent search). */
    onSearch: (String) -> Unit,
    /** Puts the text in the field to keep editing (the ↖ arrow). */
    onInsert: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onMenuClick: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    val animateItems = !LocalReducedMotion.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 1. Live Autocomplete Text Suggestions (Top 3 text recommendations)
        if (query.isNotBlank() && suggestions.isNotEmpty()) {
            items(
                items = suggestions.take(3),
                key = { "sug_$it" }
            ) { suggestion ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (animateItems) Modifier.animateItem() else Modifier)
                        .clickable {
                            onSearch(suggestion)
                        }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.60f),
                        modifier = Modifier.size(22.dp)
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Text(
                        text = suggestion,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = { onInsert(suggestion) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        DiagonalInsertArrow()
                    }
                }
            }
        }

        // 2. Direct Live Song Recommendations (Top Songs section right below text suggestions)
        if (query.isNotBlank() && liveSongs.isNotEmpty()) {
            item(key = "header_top_songs") {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = str(Strings.top_songs),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 17.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                )
            }

            items(
                items = liveSongs,
                key = { trk -> "live_song_${trk.id}" }
            ) { track ->
                val isCurrent = track.id == currentTrackId
                run {
                    TrackRowItem(
                        track = track,
                        isCurrent = isCurrent,
                        isPlaying = isPlaying,
                        playlist = listOf(track),
                        onTrackClick = onTrackClick,
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onMenuClick = { onMenuClick(track) }
                    )
                }
            }
        }

        // 2. Recent Searches History Items (when input is empty)
        if (query.isBlank()) {
            items(
                items = recentQueries,
                key = { "recent_$it" }
            ) { recent ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (animateItems) Modifier.animateItem() else Modifier)
                        .clickable {
                            onSearch(recent)
                        }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = str(Strings.history),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Text(
                        text = recent,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f)
                    )

                    // Remove from history
                    IconButton(
                        onClick = { onRemoveRecent(recent) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = str(Strings.remove),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Diagonal insert arrow (↖)
                    IconButton(
                        onClick = { onInsert(recent) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        DiagonalInsertArrow(tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * Custom 60fps canvas-rendered diagonal insert arrow (↖) matching YouTube Music.
 */
@Composable
fun DiagonalInsertArrow(
    modifier: Modifier = Modifier,
    tint: Color = Color.White.copy(alpha = 0.60f)
) {
    Canvas(modifier = modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.8.dp.toPx()

        // Main diagonal arrow shaft
        drawLine(
            color = tint,
            start = Offset(w * 0.82f, h * 0.82f),
            end = Offset(w * 0.18f, h * 0.18f),
            strokeWidth = stroke
        )
        // Top horizontal arrow bar
        drawLine(
            color = tint,
            start = Offset(w * 0.18f, h * 0.18f),
            end = Offset(w * 0.68f, h * 0.18f),
            strokeWidth = stroke
        )
        // Left vertical arrow bar
        drawLine(
            color = tint,
            start = Offset(w * 0.18f, h * 0.18f),
            end = Offset(w * 0.18f, h * 0.68f),
            strokeWidth = stroke
        )
    }
}
