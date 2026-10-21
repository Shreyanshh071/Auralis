package com.auralis.music.windows.settings

import com.auralis.music.ui.i18n.desktopText

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import com.auralis.music.ui.components.GlassAlertDialog as AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.input.key.*
import com.auralis.music.windows.LiquidPopupSurface
import com.auralis.music.data.datastore.*
import com.auralis.music.data.network.YouTubeLocales
import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.HistoryRepository
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.ui.components.IconButton
import com.auralis.music.ui.components.OutlinedButton
import com.auralis.music.ui.components.Switch
import com.auralis.music.ui.components.TextButton
import com.auralis.music.ui.components.Button
import com.auralis.music.ui.components.clickable
import com.auralis.music.ui.theme.CuratedPalettes
import com.auralis.music.util.desktopAppDataDir
import com.auralis.music.desktop.playback.DesktopStreamResolver
import com.auralis.music.windows.discord.DiscordCardPreferences
import com.auralis.music.windows.discord.WindowsDiscordPresence
import com.auralis.music.windows.player.CanvasSettings
import com.auralis.music.windows.player.CanvasSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext
import java.io.File

internal val desktopSeekBarStyles = listOf("Default", "Wavy", "Slim", "Squiggly")

@Composable
internal fun AppearanceSettingsPane(store: AppearanceSettingsDataStore) {
    val settings by store.settingsFlow.collectAsState(store.getInitialSettings())
    val scope = rememberCoroutineScope()
    fun update(transform: AppearanceSettings.() -> AppearanceSettings) {
        scope.launch { store.update { it.transform() } }
    }

    SettingsGroup("Theme & colours") {
        ThemeModeCards(settings.appTheme) { update { copy(appTheme = it) } }
        Spacer(Modifier.height(14.dp))
        PaletteCards(settings.colorPalette) { update { copy(colorPalette = it) } }
    }

    SettingsGroup("Player & seek bar") {
        BackgroundCards(settings.playerBackgroundStyle) { update { copy(playerBackgroundStyle = it) } }
        Spacer(Modifier.height(14.dp))
        SettingChoice(
            title = "Seek bar style",
            description = "Visual appearance of the timeline slider",
            selected = settings.playerSliderStyle,
            options = desktopSeekBarStyles
        ) { update { copy(playerSliderStyle = it) } }
    }

    SettingsGroup("Animated covers") {
        val prefs by CanvasSettings.prefs.collectAsState()
        SettingToggle(
            title = "Show animated covers",
            description = "Play short video loops in Now Playing sidebar when available",
            checked = prefs.enabled,
            onChecked = { CanvasSettings.update(prefs.copy(enabled = it)) }
        )
        if (prefs.enabled) {
            SettingChoice(
                title = "Animated cover source",
                description = "Service to query for canvas video loops",
                selected = prefs.source.label,
                options = CanvasSource.entries.map { it.label }
            ) { label ->
                CanvasSource.entries.firstOrNull { it.label == label }?.let {
                    CanvasSettings.update(prefs.copy(source = it))
                }
            }
        }
    }

    SettingsGroup("Lyrics appearance") {
        val styles = listOf(
            LyricsAnimationMode.METRO_LYRICS,
            LyricsAnimationMode.FLUID,
            LyricsAnimationMode.APPLE_MUSIC_V2
        )
        val current = LyricsAnimationMode.fromDisplayName(settings.lyricsAnimation).takeIf { it in styles } ?: LyricsAnimationMode.METRO_LYRICS
        SettingChoice("Lyrics animation style", "Timing and visual transition mode", current.displayName, styles.map { it.displayName }) {
            update { copy(lyricsAnimation = it) }
        }
        SettingChoice("Text alignment", "Horizontal alignment of lyric lines", settings.lyricsTextPosition, listOf("Left", "Centre", "Right")) {
            update { copy(lyricsTextPosition = it) }
        }
        SettingSlider("Lyrics font size", settings.lyricsTextSize, 14f..36f, { "${it.toInt()} sp" }) {
            update { copy(lyricsTextSize = it) }
        }
        SettingSlider("Line spacing", settings.lyricsLineSpacing, 1.1f..1.6f, { "%.1f×".format(it) }) {
            update { copy(lyricsLineSpacing = it) }
        }
        SettingToggle("Blur surrounding lyrics", "Apply depth blur to non-active lyric lines", settings.standardLyricsBlur) {
            update { copy(standardLyricsBlur = it) }
        }
        SettingToggle("Tap lyrics to seek", "Clicking any lyric jumps directly to that timestamp", settings.changeLyricsOnTap) {
            update { copy(changeLyricsOnTap = it) }
        }
        SettingToggle("Auto-scroll lyrics", "Keep active lyric line smoothly centered", settings.autoScrollLyrics) {
            update { copy(autoScrollLyrics = it) }
        }
    }

    SettingsGroup("Desktop interface & density") {
        SettingChoice("Open on startup", "Default tab when opening Auralis", settings.defaultOpenTab, listOf("Home", "Search", "Library")) {
            update { copy(defaultOpenTab = it) }
        }
        SettingChoice("Display density", "UI scaling factor", settings.displayDensity, listOf("Compact (85%)", "Native (100%)", "Large (115%)")) {
            update { copy(displayDensity = it) }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 2. PLAYER & AUDIO SETTINGS PANE
// ═══════════════════════════════════════════════════════════════════════

@Composable
internal fun ContentSettingsPane(store: ContentSettingsDataStore) {
    val settings by store.settingsFlow.collectAsState(ContentSettings())
    val scope = rememberCoroutineScope()
    fun update(transform: ContentSettings.() -> ContentSettings) {
        scope.launch { store.update { current -> current.transform() } }
    }

    SettingsGroup("Content country & language") {
        val locale = com.auralis.music.ui.i18n.StringLookup.formatLocale()
        fun countryName(code: String, fallback: String): String = runCatching {
            java.util.Locale.Builder().setRegion(code).build().getDisplayCountry(locale).ifBlank { fallback }
        }.getOrDefault(fallback)
        val countries = (listOf("US", "GB", "IN", "CA", "AU", "DE", "FR", "JP", "KR", "BR") + YouTubeLocales.countries.keys)
            .distinct().map { it to countryName(it, YouTubeLocales.countries[it] ?: it) }
        val currentCountryName = countryName(settings.contentCountry, YouTubeLocales.countries[settings.contentCountry] ?: settings.contentCountry)
        SettingSearchableChoice(
            title = "Content country",
            description = "Sets YouTube Music regional charts and new releases",
            selectedKey = settings.contentCountry,
            selectedDisplay = currentCountryName,
            options = countries
        ) { code -> update { copy(contentCountry = code) } }

        val currentLanguageName = YouTubeLocales.languages[settings.contentLanguage] ?: settings.contentLanguage
        SettingSearchableChoice(
            title = "Content language",
            description = "Preferred language for music metadata and descriptions",
            selectedKey = settings.contentLanguage,
            selectedDisplay = currentLanguageName,
            options = listOf("en" to "English (US)", "en-GB" to "English (UK)", "es" to "Español", "hi" to "हिन्दी", "ja" to "日本語", "ko" to "한국어", "fr" to "Français", "de" to "Deutsch") + YouTubeLocales.languages.toList()
        ) { code -> update { copy(contentLanguage = code) } }

        SettingToggle(
            title = "Show artist subscriber count",
            description = "Displays channel subscribers on artist pages",
            checked = settings.showArtistSubscriberCount,
            onChecked = { update { copy(showArtistSubscriberCount = it) } }
        )

        SettingToggle(
            title = "Show monthly listeners",
            description = "Displays estimated monthly listeners on artist pages",
            checked = settings.showMonthlyListeners,
            onChecked = { update { copy(showMonthlyListeners = it) } }
        )

        SettingToggle(
            title = "Show artist description",
            description = "Displays Wikipedia and bio descriptions on artist pages",
            checked = settings.showArtistDescription,
            onChecked = { update { copy(showArtistDescription = it) } }
        )
    }

    SettingsGroup("Lyrics providers") {
        Text(desktopText("Active providers are queried in priority order for synchronous and word-timed lyrics."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LyricsProviderId.entries.forEach { provider ->
            val enabled = provider !in settings.disabledLyricsProviders
            SettingToggle(
                title = provider.displayName,
                description = provider.description,
                checked = enabled,
                onChecked = { isChecked ->
                    val updated = if (isChecked) settings.disabledLyricsProviders - provider else settings.disabledLyricsProviders + provider
                    update { copy(disabledLyricsProviders = updated) }
                }
            )
        }
    }

    SettingsGroup("Lyrics romanization") {
        SettingToggle("Romanize lyrics", "Show pronunciation below supported lyrics", settings.romanizationEnabled) {
            update { copy(romanizationEnabled = it) }
        }
        Text(desktopText("Displays phonetic Latin pronunciation under East Asian scripts."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RomanizationScript.entries.forEach { script ->
            val enabled = script in settings.romanizedScripts
            SettingToggle(
                title = script.displayName,
                checked = enabled,
                onChecked = { isChecked ->
                    val updated = if (isChecked) settings.romanizedScripts + script else settings.romanizedScripts - script
                    update { copy(romanizedScripts = updated) }
                }
            )
        }
    }

    SettingsGroup("Playlists") {
        SettingChoice(
            title = "Add songs to playlist",
            description = "Position where newly saved tracks appear",
            selected = settings.addToPlaylistPosition.displayName,
            options = AddToPlaylistPosition.entries.map { it.displayName }
        ) { choice ->
            AddToPlaylistPosition.entries.firstOrNull { it.displayName == choice }?.let {
                update { copy(addToPlaylistPosition = it) }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 4. AI LYRICS TRANSLATION SETTINGS PANE
// ═══════════════════════════════════════════════════════════════════════

@Composable
internal fun AiTranslationSettingsPane(store: AiTranslationDataStore) {
    val settings by store.settingsFlow.collectAsState(AiTranslationSettings())
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(settings) }
    var dirty by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var models by remember(draft.provider) { mutableStateOf(emptyList<String>()) }
    var loadingModels by remember { mutableStateOf(false) }
    LaunchedEffect(settings) { if (!dirty) draft = settings }
    fun update(transform: AiTranslationSettings.() -> AiTranslationSettings) {
        draft = draft.transform(); dirty = true
    }

    SettingsGroup("AI translation engine") {
        val enabled = DesktopPreferences.store.bool("aiLyricsEnabled")
        SettingToggle("Enable AI lyric translation",
            if (dirty) "Apply pending changes before enabling translation" else "Send loaded lyrics to your configured provider and show translated lines in the player",
            enabled, enabled = enabled || !dirty) { DesktopPreferences.store.set("aiLyricsEnabled",it) }
        val status by DesktopAiLyrics.status.collectAsState()
        Text(desktopText(status), style = MaterialTheme.typography.bodySmall)
        SettingChoice(
            title = "AI provider",
            description = "Language model service used for translating song lyrics",
            selected = draft.provider,
            options = listOf("OpenRouter", "Google Gemini", "OpenAI", "DeepSeek", "Custom")
        ) { provider -> if (provider != draft.provider) update { copy(provider = provider, apiKey = "", model = "") } }

        var showKey by remember { mutableStateOf(false) }
        val clipboard = LocalClipboardManager.current
        SettingTextField(
            title = "API key",
            description = "Your API key for the selected provider",
            value = draft.apiKey,
            isPassword = !showKey,
            onValue = { update { copy(apiKey = it) } },
            trailing = {
                Row {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Toggle visibility", Modifier.size(18.dp))
                    }
                    IconButton(onClick = {
                        val text = clipboard.getText()?.text
                        if (!text.isNullOrBlank()) update { copy(apiKey = text.trim()) }
                    }) {
                        Icon(Icons.Outlined.ContentPaste, desktopText("Paste"), Modifier.size(18.dp))
                    }
                }
            }
        )

        SettingTextField(
            title = "Model name",
            description = "Enter a model ID or load the models available from your provider",
            value = draft.model,
            onValue = { update { copy(model = it) } }
        )
        if (draft.provider == "Custom") SettingTextField("API base URL", "OpenAI-compatible API base URL", draft.customBaseUrl,
            onValue = { update { copy(customBaseUrl=it) } })
        TextButton(onClick = { val requestSettings=draft; loadingModels=true; scope.launch {
            try { models=withContext(Dispatchers.IO) { DesktopAiLyricsClient().models(requestSettings) }; message="Loaded ${models.size} models from ${requestSettings.provider}." }
            catch (error: Exception) { message=error.message.orEmpty() }
            finally { loadingModels=false }
        } }, enabled=!loadingModels) { Text(desktopText(if(loadingModels) "Loading…" else "Load available models")) }
        if (models.isNotEmpty()) SettingSearchableChoice("Available models", selectedKey=draft.model, selectedDisplay=draft.model,
            options=models.map { it to it }) { update { copy(model=it) } }
        SettingChoice("Translation mode", selected=draft.translationMode, options=listOf("Translation","Transcription")) {
            update { copy(translationMode=it) }
        }

        SettingChoice(
            title = "Target translation language",
            description = "Language into which lyrics will be rendered",
            selected = draft.targetLanguage,
            options = listOf("English (US)", "Spanish", "Hindi", "French", "Japanese", "German", "Chinese", "Korean", "Portuguese", "Italian", "Russian")
        ) { update { copy(targetLanguage = it) } }

        SettingTextField(
            title = "System prompt style",
            description = "Custom instruction prompt for artistic and rhythmic fidelity",
            value = draft.systemPrompt,
            onValue = { update { copy(systemPrompt = it) } }
        )
        Button(onClick = { scope.launch {
            try { store.updateSettings(draft); dirty=false; message="Translation settings applied." }
            catch(error: Exception) { message=error.message.orEmpty() }
        } }, enabled=dirty) { Text(desktopText("Apply")) }
        Text(desktopText(message),style=MaterialTheme.typography.bodySmall)
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 5. PRIVACY & HISTORY SETTINGS PANE
// ═══════════════════════════════════════════════════════════════════════

@Composable
internal fun PrivacySettingsPane(store: PrivacyDataStore, history: HistoryRepository?, search: SearchRepository?) {
    val platformHistory = com.auralis.music.ui.settings.LocalSettingsPlatform.current.history
    val settings by store.settingsFlow.collectAsState(PrivacySettings())
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }

    SettingsGroup("Listening & search history") {
        SettingToggle(
            title = "Pause listening history",
            description = "Stops recording songs to your Recents shelf and stats",
            checked = settings.pauseListenHistory,
            onChecked = { scope.launch { store.setPauseListenHistory(it) } }
        )

        SettingToggle(
            title = "Pause search history",
            description = "Stops saving search queries in the search drop-down",
            checked = settings.pauseSearchHistory,
            onChecked = { scope.launch { store.setPauseSearchHistory(it) } }
        )
    }

    SettingsGroup("History data cleanup") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(desktopText("Clear listening history"), style = MaterialTheme.typography.titleMedium)
                Text(desktopText("Deletes all recorded songs from your listening history"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = {
                scope.launch {
                    if (history != null) history.clearHistory() else platformHistory.clearListenHistory()
                    message = "Listening history cleared."
                }
            }) {
                Text(desktopText("Clear history"))
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(desktopText("Clear search queries"), style = MaterialTheme.typography.titleMedium)
                Text(desktopText("Deletes all recent search history queries"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = {
                scope.launch {
                    if (search != null) search.clearSearchHistory() else platformHistory.clearSearchHistory()
                    message = "Search history cleared."
                }
            }) {
                Text(desktopText("Clear queries"))
            }
        }

        if (message != null) {
            Text(desktopText(message!!), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 6. STORAGE & CACHE SETTINGS PANE
// ═══════════════════════════════════════════════════════════════════════

@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().settingJumpTarget(title, group = true), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(desktopText(title), Modifier.padding(start = 2.dp, bottom = 2.dp), style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
internal fun SettingToggle(title: String, description: String? = null, checked: Boolean, enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().settingJumpTarget(title)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp)
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChecked)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(desktopText(title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (!description.isNullOrBlank()) {
                    Text(desktopText(description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

@Composable
internal fun SettingChoice(title: String, description: String? = null, selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        onClick = { expanded = true },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().settingJumpTarget(title)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(desktopText(title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (!description.isNullOrBlank()) {
                    Text(desktopText(description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(desktopText(selected), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }

    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text(desktopText(title)) },
            containerColor = Color(0xFF1C1A22),
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.forEach { option ->
                        Surface(
                            onClick = { onSelect(option); expanded = false },
                            shape = RoundedCornerShape(10.dp),
                            color = if (option == selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = option == selected, onClick = null)
                                Spacer(Modifier.width(10.dp))
                                Text(desktopText(option), fontWeight = if (option == selected) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { expanded = false }) { Text(desktopText("Done")) } }
        )
    }
}

@Composable
internal fun SettingAction(title: String, description: String? = null, action: String, onClick: () -> Unit) {
    // The same card as the choices around it, with the action as a pill on the right.
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().settingJumpTarget(title)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(desktopText(title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (!description.isNullOrBlank()) Text(desktopText(description), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                    Text(desktopText(action), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
internal fun SettingSearchableChoice(
    title: String,
    description: String? = null,
    selectedKey: String,
    selectedDisplay: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var filterText by remember { mutableStateOf("") }

    Surface(
        onClick = { expanded = true; filterText = "" },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().settingJumpTarget(title)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(desktopText(title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (!description.isNullOrBlank()) {
                    Text(desktopText(description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(desktopText(selectedDisplay), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }

    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text(desktopText(title)) },
            containerColor = Color(0xFF1C1A22),
            text = {
                val languageRevision = com.auralis.music.ui.i18n.StringLookup.revision.intValue
                val filtered = remember(filterText, options, languageRevision) {
                    if (filterText.isBlank()) options
                    else options.filter { it.second.contains(filterText, ignoreCase = true) || it.first.contains(filterText, ignoreCase = true) || desktopText(it.second).contains(filterText, ignoreCase = true) }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(360.dp).heightIn(max = 420.dp)) {
                    OutlinedTextField(
                        value = filterText,
                        onValueChange = { filterText = it },
                        placeholder = { Text(desktopText("Search...")) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(filtered) { (key, display) ->
                            val isSel = key == selectedKey
                            Surface(
                                onClick = { onSelect(key); expanded = false },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = isSel, onClick = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (key.isBlank() || display == "System default" || display == "Default") desktopText(display) else display, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { expanded = false }) { Text(desktopText("Cancel")) } }
        )
    }
}

@Composable
internal fun SettingSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, format: (Float) -> String, onValue: (Float) -> Unit) {
    var draft by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!dragging) draft = value }

    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().settingJumpTarget(title)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(desktopText(title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(format(draft), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = draft,
                onValueChange = { dragging = true; draft = it },
                onValueChangeFinished = { dragging = false; onValue(draft) },
                valueRange = range
            )
        }
    }
}

@Composable
internal fun SettingTextField(
    title: String,
    description: String? = null,
    value: String,
    onValue: (String) -> Unit,
    isPassword: Boolean = false,
    trailing: @Composable (() -> Unit)? = null
) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().settingJumpTarget(title)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(desktopText(title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (!description.isNullOrBlank()) {
                Text(desktopText(description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                trailingIcon = trailing,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
internal fun SettingsBlockedArtistsSection() {
    val blocked by com.auralis.music.desktop.library.DesktopBlockedArtists.artists.collectAsState()
    val clipboard=LocalClipboardManager.current
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(desktopText("Blocked artists are excluded from radio and auto-recommendations."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if(blocked.isNotEmpty()) TextButton({clipboard.setText(AnnotatedString(org.json.JSONArray(blocked.map {it.name}).toString(2)))}) {Text(desktopText("Copy blocked artists"))}
            if (blocked.isEmpty()) {
                Text(desktopText("No artists are currently blocked. You can block any artist via their ⋮ menu."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                blocked.forEach { artist ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(artist.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        TextButton(onClick = { com.auralis.music.desktop.library.DesktopBlockedArtists.unblock(artist.key) }) {
                            Text(desktopText("Unblock"))
                        }
                    }
                }
            }
        }
    }
}
