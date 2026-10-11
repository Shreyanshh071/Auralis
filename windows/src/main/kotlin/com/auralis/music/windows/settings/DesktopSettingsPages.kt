package com.auralis.music.windows.settings

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
        item { Text("Not implemented on Windows yet", style = MaterialTheme.typography.titleMedium) }
        item { Text("Skip silence, Android spatial audio, restoring the queue after restart, automatic radio extension, Bluetooth reconnection, system-mute detection and keeping the screen awake are not connected. Closing Auralis stops playback.") }
    }
}

@Composable
fun DesktopAppearanceSettings(onDismiss: () -> Unit) {
    val store = LocalSettingsStores.current.appearance
    val settings by store.settingsFlow.collectAsState(store.getInitialSettings())
    val scope = rememberCoroutineScope()
    fun update(transform: AppearanceSettings.() -> AppearanceSettings) { scope.launch { store.updateSettings(settings.transform()) } }
    val liquidify by DesktopLiquidify.enabled.collectAsState()
    SettingsPage("Appearance", onDismiss) {
        item { ThemeModeCards(settings.appTheme) { update { copy(appTheme = it) } } }
        item { LiquidifyCard(liquidify) { scope.launch(kotlinx.coroutines.Dispatchers.IO) { DesktopLiquidify.setEnabled(it) } } }
        item { PaletteCards(settings.colorPalette) { update { copy(colorPalette = it) } } }
        item { Toggle("Colours from playing artwork", settings.dynamicTheme) { update { copy(dynamicTheme = it) } } }
        item { BackgroundCards(settings.playerBackgroundStyle) { update { copy(playerBackgroundStyle = it) } } }
        item { Toggle("Hide player artwork", settings.hidePlayerThumbnail) { update { copy(hidePlayerThumbnail = it) } } }
        item { Toggle("Crop player artwork", settings.cropAlbumArt) { update { copy(cropAlbumArt = it) } } }
        item { Choice("Seek bar", settings.playerSliderStyle, listOf("Wavy", "Slim", "Squiggly", "Default")) { update { copy(playerSliderStyle = it) } } }
        item { Toggle("Black mini-player", settings.pureBlackMiniPlayer) { update { copy(pureBlackMiniPlayer = it) } } }
        item { Text("Lyrics", style = MaterialTheme.typography.titleLarge) }
        item { Choice("Text position", settings.lyricsTextPosition, listOf("Left", "Centre", "Right")) { update { copy(lyricsTextPosition = it) } } }
        item { Text("Text size: ${settings.lyricsTextSize.toInt()}"); Slider(settings.lyricsTextSize, { update { copy(lyricsTextSize = it) } }, valueRange = 14f..36f) }
        item { Text("Line spacing: ${"%.1f".format(settings.lyricsLineSpacing)}"); Slider(settings.lyricsLineSpacing, { update { copy(lyricsLineSpacing = it) } }, valueRange = 1.1f..1.6f) }
        item { Toggle("Blur surrounding lyrics", settings.standardLyricsBlur) { update { copy(standardLyricsBlur = it) } } }
        item { Toggle("Tap lyrics to seek", settings.changeLyricsOnTap) { update { copy(changeLyricsOnTap = it) } } }
        item { Toggle("Automatically scroll lyrics", settings.autoScrollLyrics) { update { copy(autoScrollLyrics = it) } } }
        item { Choice("Open on launch", settings.defaultOpenTab, listOf("Home", "Search", "Library")) { update { copy(defaultOpenTab = it) } } }
        item { Choice("Display density", settings.displayDensity, listOf("Compact (85%)", "Native (100%)", "Large (115%)")) { update { copy(displayDensity = it) } } }
        item { Text("Phone player/mini-player designs, animated mesh/glow backgrounds, alternative lyric renderers and glass refraction are not ported yet. The desktop player uses MetroLyrics. Wallpaper-based Material You colours are not available on Windows.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
        item { Text("Offline downloads and an audio-file cache are not implemented on Windows yet. Audio streams are relayed in memory; no audio files are saved.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (message.isNotBlank()) item { Text(message) }
    }
}

@Composable
private fun SettingsPage(title: String, back: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        TextButton(onClick = back) { Text("Back") }
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(bottom = 20.dp))
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
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text(selected) }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { select(option); expanded = false }) }
            }
        }
    }
}
