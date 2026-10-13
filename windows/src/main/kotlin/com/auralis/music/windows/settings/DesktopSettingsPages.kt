package com.auralis.music.windows.settings

import com.auralis.music.ui.components.Surface

import com.auralis.music.ui.components.Slider

import com.auralis.music.ui.components.TextButton
import com.auralis.music.ui.components.OutlinedButton
import com.auralis.music.ui.components.RadioButton

import com.auralis.music.ui.components.Switch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auralis.music.data.datastore.LocalSettingsStores
import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.domain.model.AudioQuality
import com.auralis.music.domain.model.PlayerSettings
import com.auralis.music.ui.theme.CuratedPalettes
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Desktop pages expose controls with Windows implementations; unsupported phone controls are named. */
@Composable
fun DesktopAudioSettings(onDismiss: () -> Unit) {
    val stores = LocalSettingsStores.current
    val settings by stores.settings.settingsFlow.collectAsState(PlayerSettings())
    val scope = rememberCoroutineScope()
    SettingsPage("Player & audio", onDismiss) {
        item { Choice("Streaming quality (next song)", settings.audioQuality.name, AudioQuality.entries.map { it.name }) { choice -> scope.launch { stores.settings.setAudioQuality(AudioQuality.valueOf(choice)) } } }
        item { Text("Playback speed: ${settings.playbackRate}×"); Slider(settings.playbackRate, { value -> scope.launch { stores.settings.setPlaybackRate(value) } }, valueRange = 0.5f..2f, steps = 5) }
        item { Toggle("Preload the next song's stream", settings.gaplessPlayback) { scope.launch { stores.settings.setGaplessPlayback(it) } } }
        item { Text("Preloading reduces lookup time between tracks; VLC stream changes are not guaranteed to be gapless.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { HorizontalDivider() }
        item { BlockedArtistsSection() }
        item { HorizontalDivider() }
        item { Text("Not implemented on Windows yet", style = MaterialTheme.typography.titleMedium) }
        item { Text("Skip silence, Android spatial audio, restoring the queue after restart, Bluetooth reconnection, system-mute detection and keeping the screen awake are not connected. Closing Auralis stops playback.") }
    }
}

@Composable
fun DesktopAppearanceSettings(onDismiss: () -> Unit) {
    val store = LocalSettingsStores.current.appearance
    val settings by store.settingsFlow.collectAsState(store.getInitialSettings())
    val scope = rememberCoroutineScope()
    val updates = remember { Mutex() }
    fun update(transform: AppearanceSettings.() -> AppearanceSettings) {
        scope.launch { updates.withLock { store.updateSettings(store.settingsFlow.first().transform()) } }
    }
    var showThemes by remember { mutableStateOf(false) }
    var showSliderStyles by remember { mutableStateOf(false) }
    if (showSliderStyles) com.auralis.music.ui.screens.PlayerSliderStyleChooserDialog(
        settings.playerSliderStyle, { update { copy(playerSliderStyle = it) } }, { showSliderStyles = false },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 1f))
    if (showThemes) {
        DesktopThemesAndColours { showThemes = false }
        return
    }
    SettingsPage("Appearance", onDismiss) {
        item {
            Surface(onClick = { showThemes = true }, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Themes and colours", style = MaterialTheme.typography.titleLarge)
                        Text("${settings.appTheme} · ${settings.colorPalette}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
        item { BackgroundCards(settings.playerBackgroundStyle) { update { copy(playerBackgroundStyle = it) } } }
        item {
            Surface(onClick = { showSliderStyles = true }, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("Seek bar", style = MaterialTheme.typography.titleMedium); Text(settings.playerSliderStyle) }
                    Text("Choose ›")
                }
            }
        }
        item { Text("Lyrics", style = MaterialTheme.typography.titleLarge) }
        item {
            val styles = listOf(com.auralis.music.domain.model.LyricsAnimationMode.METRO_LYRICS,
                com.auralis.music.domain.model.LyricsAnimationMode.FLUID, com.auralis.music.domain.model.LyricsAnimationMode.APPLE_MUSIC_V2)
            val current = com.auralis.music.domain.model.LyricsAnimationMode.fromDisplayName(settings.lyricsAnimation)
                .takeIf { it in styles } ?: com.auralis.music.domain.model.LyricsAnimationMode.METRO_LYRICS
            Choice("Lyrics style", current.displayName, styles.map { it.displayName }) { update { copy(lyricsAnimation = it) } }
        }
        item { Choice("Text position", settings.lyricsTextPosition, listOf("Left", "Centre", "Right")) { update { copy(lyricsTextPosition = it) } } }
        item { ResponsiveSettingsSlider("Text size", settings.lyricsTextSize, 14f..36f, { it.toInt().toString() }) { value -> update { copy(lyricsTextSize = value) } } }
        item { ResponsiveSettingsSlider("Line spacing", settings.lyricsLineSpacing, 1.1f..1.6f, { "%.1f".format(it) }) { value -> update { copy(lyricsLineSpacing = value) } } }
        item { Toggle("Blur surrounding lyrics", settings.standardLyricsBlur) { update { copy(standardLyricsBlur = it) } } }
        item { Toggle("Tap lyrics to seek", settings.changeLyricsOnTap) { update { copy(changeLyricsOnTap = it) } } }
        item { Toggle("Automatically scroll lyrics", settings.autoScrollLyrics) { update { copy(autoScrollLyrics = it) } } }
        item { Choice("Open on launch", settings.defaultOpenTab, listOf("Home", "Search", "Library")) { update { copy(defaultOpenTab = it) } } }
        item { Choice("Display density", settings.displayDensity, listOf("Compact (85%)", "Native (100%)", "Large (115%)")) { update { copy(displayDensity = it) } } }
        item { Text("Phone player/mini-player designs, alternative lyric renderers and glass refraction are not ported yet. The desktop player uses MetroLyrics. Wallpaper-based Material You colours are not available on Windows.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun DesktopStorageSettings(onDismiss: () -> Unit) {
    val stores = LocalSettingsStores.current
    val settings by stores.storage.settingsFlow.collectAsState(com.auralis.music.data.datastore.StorageSettings())
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("") }
    val imageBytes = remember(refresh) { DesktopCaches.imageBytes() }
    val lyricsBytes = remember(refresh) { DesktopCaches.lyricsBytes() }
    var busy by remember { mutableStateOf(false) }
    fun clear(action: () -> Unit) {
        busy = true
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = runCatching { action() }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                message = if (result.isSuccess) "Cache cleared. Your library and settings are kept." else "Couldn't clear cache: ${result.exceptionOrNull()?.message}"
                refresh++; busy = false
            }
        }
    }
    SettingsPage("Storage", onDismiss) {
        item { Text("Artwork cache: ${com.auralis.music.util.formatBytes(imageBytes)}") }
        item { OutlinedButton(onClick = { clear { DesktopCaches.clearImages() } }, enabled = !busy) { Text("Clear artwork cache") } }
        item { Choice("Artwork cache limit (after restart)", settings.maxImageCacheSizeMb.toString(), listOf("128", "256", "537", "1024", "2048")) { scope.launch { stores.storage.setMaxImageCacheSizeMb(it.toInt()) } } }
        item { Text("Lyrics cache: ${com.auralis.music.util.formatBytes(lyricsBytes)}") }
        item { OutlinedButton(onClick = { clear { DesktopCaches.clearLyrics() } }, enabled = !busy) { Text("Clear saved lyrics") } }
        item { OutlinedButton(onClick = { com.auralis.music.desktop.playback.DesktopStreamResolver.clearCache(); message = "Stream lookup cache cleared" }) { Text("Clear stream lookups") } }
        item { Text("Downloads save complete audio files on this computer, grouped as individual songs or by playlist. Streaming audio is relayed in memory.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (message.isNotBlank()) item { Text(message) }
    }
}

/** The block list (Li Music's): who is left out of radio and recommendations, with Unblock. */
@Composable
private fun BlockedArtistsSection() {
    val blocked by com.auralis.music.desktop.library.DesktopBlockedArtists.artists.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Blocked artists", style = MaterialTheme.typography.titleMedium)
        Text("Blocked artists are left out of radio and recommendations. Albums and playlists you open still play everything in them.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (blocked.isEmpty()) Text("Nobody blocked yet. Use \"Block artist\" from an artist's ⋮ menu.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        blocked.forEach { artist ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(artist.name, modifier = Modifier.weight(1f))
                TextButton(onClick = { com.auralis.music.desktop.library.DesktopBlockedArtists.unblock(artist.key) }) { Text("Unblock") }
            }
        }
    }
}

@Composable
internal fun SettingsPage(title: String, back: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        com.auralis.music.ui.components.PageHeader(title, back, Modifier.padding(bottom = 8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}
@Composable
private fun Toggle(title: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(title, modifier = Modifier.weight(1f)); Switch(checked, change) }
}
@Composable
private fun Choice(title: String, selected: String, options: List<String>, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Surface(onClick = { expanded = true }, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text(title, style = MaterialTheme.typography.titleMedium); Text(selected, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("Choose ›")
        }
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text(title) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 1f),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { option ->
                    Surface(onClick = { select(option); expanded = false },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        color = if (option == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(selected = option == selected, onClick = null)
                            Spacer(Modifier.width(12.dp)); Text(option)
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { expanded = false }) { Text("Cancel") } })
}

/** Pointer movement updates only this control; persist one value when the gesture ends. */
@Composable
internal fun ResponsiveSettingsSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String, onCommit: (Float) -> Unit) {
    var draft by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!dragging) draft = value }
    Column {
        Text("$label: ${format(draft)}")
        Slider(value = draft, onValueChange = { dragging = true; draft = it },
            onValueChangeFinished = { dragging = false; onCommit(draft) }, valueRange = range)
    }
}
