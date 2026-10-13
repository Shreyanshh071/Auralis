package com.auralis.music.windows.settings

import com.auralis.music.ui.components.Surface

import com.auralis.music.ui.components.Switch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.auralis.music.data.datastore.LocalSettingsStores
import com.auralis.music.domain.model.AppearanceSettings
import kotlinx.coroutines.launch

@Composable
internal fun DesktopThemesAndColours(onBack: () -> Unit) {
    val store = LocalSettingsStores.current.appearance
    val settings by store.settingsFlow.collectAsState(store.getInitialSettings())
    val scope = rememberCoroutineScope()
    fun update(transform: AppearanceSettings.() -> AppearanceSettings) {
        scope.launch { store.updateSettings(settings.transform()) }
    }
    SettingsPage("Themes and colours", onBack) {
        item { ThemeColourPreview() }
        item { ThemeModeCards(settings.appTheme) { update { copy(appTheme = it) } } }
        item { PaletteCards(settings.colorPalette) { update { copy(colorPalette = it) } } }
        item { Text("Artwork backgrounds follow the current song in every theme. When the player is empty, an open playlist uses its cover.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Colours from playing artwork", style = MaterialTheme.typography.titleMedium)
                    Text("Used by the Dynamic palette", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(settings.dynamicTheme, { update { copy(dynamicTheme = it) } })
            }
        }
    }
}

/** Live colour composition, like the phone's preview, sized for a desktop page. */
@Composable
private fun ThemeColourPreview() {
    val colours = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Live preview", style = MaterialTheme.typography.titleMedium)
        Surface(Modifier.fillMaxWidth().height(220.dp), shape = RoundedCornerShape(28.dp),
            color = colours.background, border = BorderStroke(1.dp, colours.outlineVariant)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Box(Modifier.weight(1f).height(24.dp).clip(CircleShape).background(colours.surfaceContainerHigh))
                    Box(Modifier.size(24.dp).clip(CircleShape).background(colours.primary))
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.weight(2f).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(colours.primary))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colours.secondary))
                        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colours.tertiary))
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(colours.primaryContainer))
                }
            }
        }
    }
}
