package com.auralis.music.windows.history

import com.auralis.music.ui.components.Surface

import com.auralis.music.ui.components.IconButton
import com.auralis.music.ui.components.FilledIconButton
import com.auralis.music.ui.components.TextButton
import com.auralis.music.ui.components.DropdownMenuItem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.auralis.music.desktop.library.DesktopListeningStats
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.Track
import com.auralis.music.windows.search.DesktopArtwork
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The phone's stats periods: rolling ranges, calendar weeks, months or years. */
private enum class StatsOption(val label: String) { CONTINUOUS("Continuous"), WEEKS("Weeks"), MONTHS("Months"), YEARS("Years") }

/** A chip's label and the [from, until) window it covers, in epoch milliseconds. */
private data class StatsRange(val label: String, val from: Long, val until: Long)

private fun ranges(option: StatsOption, firstEvent: Long?): List<StatsRange> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val now = System.currentTimeMillis()
    fun start(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val first = firstEvent?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: today.minusMonths(3)
    return when (option) {
        StatsOption.CONTINUOUS -> listOf(
            StatsRange("1 week", now - 7 * 86_400_000L, Long.MAX_VALUE),
            StatsRange("1 month", start(today.minusMonths(1)), Long.MAX_VALUE),
            StatsRange("3 months", start(today.minusMonths(3)), Long.MAX_VALUE),
            StatsRange("6 months", start(today.minusMonths(6)), Long.MAX_VALUE),
            StatsRange("1 year", start(today.minusYears(1)), Long.MAX_VALUE),
            StatsRange("All time", 0L, Long.MAX_VALUE))
        StatsOption.WEEKS -> {
            val format = DateTimeFormatter.ofPattern("d MMM")
            buildList {
                var end = today
                while (size < 12 && (end.isAfter(first.minusWeeks(1)) || size < 4)) {
                    val begin = end.minusDays(6)
                    add(StatsRange("${format.format(begin)} - ${format.format(end)}", start(begin), start(end.plusDays(1))))
                    end = end.minusDays(7)
                }
            }
        }
        StatsOption.MONTHS -> {
            val format = DateTimeFormatter.ofPattern("MMM yyyy")
            buildList {
                var month = YearMonth.from(today)
                while (size < 12 && (!month.isBefore(YearMonth.from(first)) || size < 4)) {
                    add(StatsRange(format.format(month), start(month.atDay(1)), start(month.plusMonths(1).atDay(1))))
                    month = month.minusMonths(1)
                }
            }
        }
        StatsOption.YEARS -> buildList {
            var year = today.year
            while (year >= first.year.coerceAtMost(today.year - 1) && size < 5) {
                add(StatsRange("$year", start(LocalDate.of(year, 1, 1)), start(LocalDate.of(year + 1, 1, 1))))
                year--
            }
        }
    }
}

/** H:MM:SS above an hour, M:SS below, as the phone prints listening time. */
private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun count(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

private fun artistName(track: Track) = track.artist.trim().removeSuffix(" - Topic")

/**
 * The phone's Stats screen on Windows: period chips, Your Highlights, Listening Overview, top songs
 * and top artists. Every number comes from measured listening on this PC; nothing is estimated.
 */
@Composable
fun ListeningStatsPage(
    stats: DesktopListeningStats,
    playback: DesktopPlaybackController?,
    wrapped: Boolean,
    onBack: () -> Unit,
    onOpenWrapped: () -> Unit = {},
    search: com.auralis.music.domain.repository.SearchRepository? = null,
    onOpenArtist: (Artist) -> Unit = {},
    onTrackMenu: (Track) -> Unit = {}
) {
    val events by stats.listens.collectAsState()
    var option by remember { mutableStateOf(StatsOption.CONTINUOUS) }
    var chip by remember { mutableStateOf(0) }
    var optionMenu by remember { mutableStateOf(false) }
    var statsMenu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val year = LocalDate.now().year
    val firstEvent = events.minOfOrNull { it.startedAt }
    val chips = remember(option, firstEvent) { ranges(option, firstEvent) }
    val range = if (wrapped) StatsRange("$year", LocalDate.of(year, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), Long.MAX_VALUE)
        else chips.getOrElse(chip) { chips.first() }
    val selected = events.filter { it.startedAt >= range.from && it.startedAt < range.until }
    val songs = selected.groupBy { DesktopListeningStats.songKey(it.track) }.values
        .sortedWith(compareByDescending<List<DesktopListeningStats.Listen>> { list -> list.count { it.countsAsPlay } }.thenByDescending { list -> list.sumOf { it.timeMs } })
    val artists = selected.groupBy { artistName(it.track) }.entries.sortedByDescending { (_, list) -> list.sumOf { it.timeMs } }
    val albumCount = selected.mapNotNull { it.track.album?.takeIf(String::isNotBlank)?.let { album -> "${artistName(it.track)}|$album" } }.distinct().size
    fun songTrack(list: List<DesktopListeningStats.Listen>) = list.groupBy { it.track.id }.values.maxBy { it.sumOf { e -> e.timeMs } }.first().track
    val topTracks = songs.map(::songTrack)

    // Artist photos, as the phone shows them; looked up once per name.
    val photos = remember { mutableStateMapOf<String, String?>() }
    LaunchedEffect(artists.take(12).map { it.key }) {
        if (search == null) return@LaunchedEffect
        artists.take(12).map { it.key }.filter { it !in photos }.forEach { name ->
            photos[name] = runCatching { search.searchArtists(name).firstOrNull { it.name.equals(name, true) }?.thumbnail }.getOrNull()
        }
    }

    val colours = MaterialTheme.colorScheme
    val card = colours.surfaceContainer
    val accent = colours.primary
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    com.auralis.music.ui.components.PageHeader(if (wrapped) "Your $year Wrapped" else "Stats", onBack, Modifier.weight(1f))
                    if (!wrapped) IconButton(onClick = onOpenWrapped) { Icon(Icons.Rounded.AutoAwesome, "Your Wrapped") }
                    Box {
                        IconButton(onClick = { statsMenu = true }) { Icon(Icons.Default.MoreVert, "Stats options") }
                        com.auralis.music.windows.GlassDropdownMenu(statsMenu, onDismissRequest = { statsMenu = false }) {
                            DropdownMenuItem(text = { Text("Clear listening stats") }, enabled = events.isNotEmpty(),
                                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                                onClick = { statsMenu = false; confirmClear = true })
                        }
                    }
                }
            }
            if (!wrapped) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Box {
                            StatsPill(selected = false, onClick = { optionMenu = true }) {
                                Text(option.label); Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
                            }
                            com.auralis.music.windows.GlassDropdownMenu(optionMenu, onDismissRequest = { optionMenu = false }) {
                                StatsOption.entries.forEach { entry ->
                                    DropdownMenuItem(text = { Text(entry.label) }, onClick = { option = entry; chip = 0; optionMenu = false })
                                }
                            }
                        }
                    }
                    itemsIndexed(chips) { index, entry ->
                        StatsPill(selected = index == chip, onClick = { chip = index }) { Text(entry.label) }
                    }
                }
            }
            if (selected.isEmpty()) item {
                Text("Play some music to build your stats. Only time you actually listen on this PC is counted.",
                    color = colours.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
            } else {
                item { SectionTitle("Your Highlights", Icons.Rounded.AutoAwesome) }
                artists.firstOrNull()?.let { (name, list) ->
                    item {
                        HighlightCard(label = "Top Artist", title = name,
                            subtitle = "${count(list.distinctBy { DesktopListeningStats.songKey(it.track) }.size, "song")} played · ${clock(list.sumOf { it.timeMs })}",
                            image = photos[name] ?: list.first().track.thumbnail, round = true, action = Icons.Rounded.Star,
                            onAction = { onOpenArtist(Artist(id = "", name = name)) }, onClick = { onOpenArtist(Artist(id = "", name = name)) })
                    }
                }
                songs.firstOrNull()?.let { list ->
                    val track = songTrack(list)
                    item {
                        HighlightCard(label = "Top Song", title = track.title,
                            subtitle = "${count(list.count { it.countsAsPlay }, "play")} · ${clock(list.sumOf { it.timeMs })}",
                            image = track.thumbnail, round = false, action = Icons.Rounded.PlayArrow, actionFilled = true,
                            onAction = { playback?.playFrom(track, topTracks) }, onClick = { playback?.playFrom(track, topTracks) })
                    }
                }
                item { SectionTitle("Listening Overview") }
                item {
                    Surface(shape = RoundedCornerShape(24.dp), color = card, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(76.dp).clip(CircleShape).background(colours.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Headphones, null, Modifier.size(36.dp))
                            }
                            Spacer(Modifier.width(18.dp))
                            Column {
                                Text("Total Time Listened", style = MaterialTheme.typography.labelLarge, color = colours.onSurfaceVariant)
                                Text(clock(selected.sumOf { it.timeMs }), fontSize = 36.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard(Icons.Rounded.MusicNote, songs.size, "Songs", Modifier.weight(1f))
                        MetricCard(Icons.Rounded.Headphones, artists.size, "Artists", Modifier.weight(1f))
                        MetricCard(Icons.Rounded.Album, albumCount, "Albums", Modifier.weight(1f))
                    }
                }
                item { SectionTitle("Top Songs") }
                itemsIndexed(songs.take(50)) { index, list ->
                    val track = songTrack(list)
                    StatLine(rank = index + 1, title = track.title,
                        subtitle = "${count(list.count { it.countsAsPlay }, "time")} · ${clock(list.sumOf { it.timeMs })}",
                        image = track.thumbnail, round = false, onClick = { playback?.playFrom(track, topTracks) },
                        onMenu = { onTrackMenu(track) })
                }
                item { SectionTitle("Top Artists") }
                itemsIndexed(artists.take(30)) { index, (name, list) ->
                    StatLine(rank = index + 1, title = name,
                        subtitle = "${count(list.distinctBy { DesktopListeningStats.songKey(it.track) }.size, "song")} · ${clock(list.sumOf { it.timeMs })}",
                        image = photos[name] ?: list.first().track.thumbnail, round = true, onClick = { onOpenArtist(Artist(id = "", name = name)) })
                }
            }
        }
        if (topTracks.isNotEmpty()) ExtendedFloatingActionButton(
            onClick = { topTracks.shuffled().let { playback?.playFrom(it.first(), it) } },
            icon = { Icon(Icons.Rounded.Shuffle, null) }, text = { Text("Shuffle") },
            containerColor = colours.surfaceContainerHigh, contentColor = accent,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp))
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear listening stats?") },
        text = { Text("This removes measured listening stats on this PC. Your library and listening history are kept.") },
        confirmButton = { TextButton(onClick = { stats.clear(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
}

@Composable
private fun StatsPill(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val colours = MaterialTheme.colorScheme
    Row(Modifier.height(36.dp).clip(RoundedCornerShape(50))
        .background(if (selected) colours.primary.copy(alpha = .22f) else Color.Transparent)
        .border(1.dp, if (selected) colours.primary.copy(alpha = .6f) else colours.onSurface.copy(alpha = .18f), RoundedCornerShape(50))
        .clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(color = if (selected) colours.onSurface else colours.onSurfaceVariant)) { content() }
    }
}

@Composable
private fun SectionTitle(text: String, icon: ImageVector? = null) {
    Row(Modifier.padding(top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun HighlightCard(label: String, title: String, subtitle: String, image: String?, round: Boolean,
    action: ImageVector, onAction: () -> Unit, onClick: () -> Unit, actionFilled: Boolean = false) {
    val colours = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = colours.surfaceContainer, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            StatImage(image, round, 60)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = colours.onSurfaceVariant)
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colours.onSurfaceVariant)
            }
            val onAccent = if (colours.primary.luminance() > .5f) Color.Black else Color.White
            FilledIconButton(onClick = onAction, colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (actionFilled) colours.primary else colours.primary.copy(alpha = .18f),
                contentColor = if (actionFilled) onAccent else colours.primary)) { Icon(action, null) }
        }
    }
}

@Composable
private fun MetricCard(icon: ImageVector, value: Int, label: String, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("$value", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatLine(rank: Int, title: String, subtitle: String, image: String?, round: Boolean, onClick: () -> Unit, onMenu: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        StatImage(image, round, 52)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("$rank. $title", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onMenu != null) IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, "Options") }
    }
}

@Composable
private fun StatImage(url: String?, round: Boolean, size: Int) {
    if (round) Box(Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        if (!url.isNullOrBlank()) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    } else DesktopArtwork(url, Modifier.size(size.dp), 10.dp, null)
}
