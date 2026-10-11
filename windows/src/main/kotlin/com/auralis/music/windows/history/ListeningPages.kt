package com.auralis.music.windows.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.auralis.music.desktop.library.DesktopListeningStats
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.domain.model.Track
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun ListeningStatsPage(stats: DesktopListeningStats, playback: DesktopPlaybackController?, wrapped: Boolean, onBack: () -> Unit) {
    val events by stats.listens.collectAsState()
    var days by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val year = LocalDate.now().year
    val from = if (wrapped) LocalDate.of(year, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        else if (days == 0) 0L else System.currentTimeMillis() - days * 86_400_000L
    val selected = events.filter { it.startedAt >= from }
    val songs = selected.groupBy { DesktopListeningStats.songKey(it.track) }.values.sortedByDescending { list -> list.sumOf { it.timeMs } }
    val artists = selected.groupBy { it.track.artist.trim().removeSuffix(" - Topic") }.entries.sortedByDescending { (_, list) -> list.sumOf { it.timeMs } }
    val albums = selected.filter { !it.track.album.isNullOrBlank() }.groupBy { "${it.track.artist}|${it.track.album}" }
        .values.sortedByDescending { list -> list.sumOf { it.timeMs } }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(if (wrapped) "Your $year Wrapped" else "Listening stats", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = { confirmClear = true }, enabled = events.isNotEmpty()) { Text("Clear stats") }
        }
        if (!wrapped) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7 to "Week", 30 to "Month", 365 to "Year", 0 to "All time").forEach { (value, label) ->
                FilterChip(selected = days == value, onClick = { days = value }, label = { Text(label) })
            }
        }
        Text("${duration(selected.sumOf { it.timeMs })} listened · ${songs.size} songs · ${artists.size} artists", modifier = Modifier.padding(vertical = 20.dp), style = MaterialTheme.typography.titleLarge)
        if (selected.isEmpty()) Text("Play some music to build your stats. Only actual listening time is counted; earlier play counts cannot tell us how long you listened.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Top songs", style = MaterialTheme.typography.titleLarge) }
            items(songs) { list ->
                val track = list.groupBy { it.track.id }.values.maxBy { it.sumOf { e -> e.timeMs } }.first().track
                StatRow(track.title, "${track.artist} · ${duration(list.sumOf { it.timeMs })} · ${list.count { it.countsAsPlay }} plays", track) {
                    playback?.playFrom(track, songs.map { it.first().track })
                }
            }
            item { Text("Top artists", style = MaterialTheme.typography.titleLarge) }
            items(artists) { (artist, list) -> StatRow(artist, duration(list.sumOf { it.timeMs }), null) {} }
            item { Text("Top albums", style = MaterialTheme.typography.titleLarge) }
            items(albums) { list -> StatRow(list.first().track.album.orEmpty(), duration(list.sumOf { it.timeMs }), null) {} }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear listening stats?") },
        text = { Text("This removes measured listening stats on this PC. Your library and listening history are kept.") },
        confirmButton = { TextButton(onClick = { stats.clear(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
}

@Composable
private fun StatRow(title: String, subtitle: String, track: Track?, onClick: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().clickable(enabled = track != null, onClick = onClick).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (track != null) com.auralis.music.ui.components.ArtworkCard(track.thumbnail, modifier = Modifier.size(56.dp))
            Column { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun duration(ms: Long): String = if (ms < 60_000) "${ms / 1000}s" else if (ms < 3_600_000) "${ms / 60_000}m" else "${ms / 3_600_000}h ${(ms / 60_000) % 60}m"
