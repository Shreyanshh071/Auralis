package com.auralis.music.windows.settings

import com.auralis.music.windows.player.BlurredArtworkBackground
import com.auralis.music.windows.player.LocalArtworkBackdrop
import com.auralis.music.windows.player.withArtworkSurfaces
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.lerp

import com.auralis.music.ui.i18n.desktopText

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.auralis.music.data.datastore.SettingsStores
import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.HistoryRepository
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.windows.discord.*
import kotlinx.coroutines.*

internal enum class SettingsTab(val englishLabel: String, private val englishHint: String, val icon: ImageVector, val keywords: String) {
    GENERAL("General", "Language, activity and Windows integration", Icons.Outlined.Tune, "tray song notifications startup login title bar"),
    APPEARANCE("Appearance", "Theme, typography and player presentation", Icons.Outlined.Palette, "zoom font accent colour color artwork animation covers roundness"),
    PLAYBACK("Playback", "Audio, queue behaviour and lyrics", Icons.Outlined.Headphones, "quality normalize volume crossfade speed autoplay shuffle video"),
    HOTKEYS("Global hotkeys", "Control Auralis while another app is in front", Icons.Outlined.Keyboard, "keyboard shortcuts bindings"),
    DISCORD("Discord", "Your Rich Presence card and live preview", Icons.Outlined.ChatBubbleOutline, "activity status clickable progress"),
    SCROBBLING("Scrobbling", "Last.fm, ListenBrainz and metadata rules", Icons.Outlined.GraphicEq, "lastfm last.fm listenbrainz metadata"),
    DATA("Data & storage", "Network, downloads and caches", Icons.Outlined.Storage, "proxy folder cache downloads"),
    ABOUT("About", "Version, updates and diagnostics", Icons.Outlined.Info, "release beta logs export"),
    CONTENT("Content & region", "Regional catalogue and lyrics providers", Icons.Outlined.Language, "country language explicit"),
    AI("AI translation", "Translation providers and credentials", Icons.Outlined.Translate, "api key translator"),
    PRIVACY("Privacy & history", "Control and clear recorded activity", Icons.Outlined.Shield, "clear delete search listening");

    val label: String get() = desktopText(englishLabel)
    val hint: String get() = desktopText(englishHint)
}

/** Tabs whose name or keywords match [query], or that hold a setting matching it. */
internal fun matchingSettingsTabs(query: String): List<SettingsTab> {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    val holding = searchSettings(query).map { it.tab }.toSet()
    return SettingsTab.entries.filter { tab ->
        val text = "${tab.label} ${tab.hint} ${tab.englishLabel} ${tab.keywords} ${desktopText(tab.keywords)}"
        tab in holding || terms.all { text.contains(it, ignoreCase = true) }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun DesktopSettingsDialog(stores: SettingsStores, history: HistoryRepository? = null, search: SearchRepository? = null,
    hasActiveTrack: Boolean = false, discordControl: Any? = null, onDismiss: () -> Unit) {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val position = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)) {
        SettingsPopupOverlay(Modifier.size(with(density) { size.width.toDp() }, with(density) { size.height.toDp() }), onDismiss) {
            SettingsWindowContent(stores, history, search, discordControl, onDismiss)
        }
    }
}

/** Wait for a rendered popup frame before starting the entrance transition. */
@Composable
internal fun SettingsPopupOverlay(modifier: Modifier = Modifier, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val focus = remember { FocusRequester() }
    val opening = remember { Animatable(0f) }
    val firstDraw = remember { CompletableDeferred<Unit>() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(Unit) {
        firstDraw.await()
        withFrameNanos { }
        opening.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
    }
    BoxWithConstraints(modifier.onPreviewKeyEvent {
        if (it.key == Key.Escape) {
            if (it.type == KeyEventType.KeyUp) onDismiss()
            true
        } else false
    }.focusRequester(focus).focusable().drawWithContent {
        drawContent()
        firstDraw.complete(Unit)
    }, contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().graphicsLayer { alpha = opening.value }
            .background(Color.Black.copy(alpha = .58f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
        val scheme = MaterialTheme.colorScheme
        val dark = scheme.surface.luminance() < .5f
        val opaqueScheme = scheme.copy(
            surface = if (dark) Color(0xFF1C1B20) else Color(0xFFFAF9FC),
            surfaceContainerLow = if (dark) Color(0xFF17161B) else Color(0xFFF1F0F5),
            surfaceContainer = if (dark) Color(0xFF27262D) else Color(0xFFEFEDF3),
            surfaceContainerHigh = if (dark) Color(0xFF302F37) else Color(0xFFE6E3ED),
            surfaceContainerHighest = if (dark) Color(0xFF393740) else Color(0xFFDED9E5)
        )
        // With an artwork background the panel shows the same song-synced field as the app.
        // Dialog containers (High/Highest) stay opaque, only washed with the song's colour.
        val backdrop = LocalArtworkBackdrop.current
        val artworkTint = if (dark) Color(0xFF101010) else Color(0xFFF6F6F6)
        val songWash by animateColorAsState(backdrop?.palette?.primary ?: scheme.primary, tween(600))
        val popupScheme = if (backdrop == null) opaqueScheme else scheme.withArtworkSurfaces(artworkTint).copy(
            surfaceContainerHigh = lerp(opaqueScheme.surfaceContainerHigh, songWash, .10f),
            surfaceContainerHighest = lerp(opaqueScheme.surfaceContainerHighest, songWash, .12f))
        val panelWidth = minOf(1080.dp, (maxWidth - 48.dp).coerceAtLeast(1.dp))
        val panelHeight = minOf(760.dp, (maxHeight - 48.dp).coerceAtLeast(1.dp))
        MaterialTheme(colorScheme = popupScheme) {
        CompositionLocalProvider(
            com.auralis.music.ui.components.LocalGlassDialogHost provides { dismiss, dialog -> SettingsOptionPopup(dismiss, dialog) },
            LocalScrollbarStyle provides LocalScrollbarStyle.current.copy(
            thickness = 4.dp,
            unhoverColor = scheme.onSurface.copy(alpha = .20f),
            hoverColor = scheme.onSurface.copy(alpha = .40f)
        )) {
        Surface(
            Modifier.size(panelWidth, panelHeight)
                .graphicsLayer {
                    alpha = opening.value
                    scaleX = .90f + .10f * opening.value
                    scaleY = scaleX
                    translationY = 24.dp.toPx() * (1f - opening.value)
                }
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {}),
            shape = MaterialTheme.shapes.large,
            color = if (backdrop == null) popupScheme.surface else artworkTint,
            contentColor = popupScheme.onSurface,
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = .55f)),
            shadowElevation = 24.dp
        ) {
            if (backdrop == null) content() else Box(Modifier.fillMaxSize()) {
                BlurredArtworkBackground(backdrop.artwork, blurRadius = 48.dp, brightness = .68f, artworkScale = 1.10f)
                val tintAmount = DesktopPreferences.store.number("backgroundTint", 0.0).toFloat().coerceIn(0f, .4f)
                Box(Modifier.matchParentSize().background(scheme.primary.copy(alpha = tintAmount)))
                Box(Modifier.fillMaxSize().background(popupScheme.surface)) { content() }
            }
        }
        }
        }
    }
}

@Composable
internal fun SettingsWindowContent(stores: SettingsStores, history: HistoryRepository?, search: SearchRepository?, discordControl: Any?, onDismiss: () -> Unit, initialTab: SettingsTab = SettingsTab.GENERAL) {
    var selected by remember { mutableStateOf(initialTab) }
    var displayed by remember { mutableStateOf(initialTab) }
    LaunchedEffect(selected) {
        // Paint the selected navigation row/header before composing a new pane.
        withFrameNanos { }
        displayed = selected
    }
    var filter by remember { mutableStateOf("") }
    var jump by remember { mutableStateOf<SettingJump?>(null) }
    val languageRevision = com.auralis.music.ui.i18n.StringLookup.revision.intValue
    val matches = remember(filter, languageRevision) { matchingSettingsTabs(filter) }
    val results = remember(filter, languageRevision) { searchSettings(filter) }
    val searching = filter.isNotBlank()
    fun open(entry: SettingEntry) {
        selected = entry.tab
        jump = SettingJump(entry.title, entry.group)
        filter = ""
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val navigationWidth = if (maxWidth < 760.dp) 200.dp else 228.dp
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(navigationWidth).fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 14.dp, vertical = 20.dp)) {
            Text(desktopText("Settings"), Modifier.padding(horizontal = 10.dp).padding(bottom = 18.dp),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            SettingsSearchField(filter, onSubmit = { results.firstOrNull()?.let(::open) }) { value ->
                filter = value
                val tabs = matchingSettingsTabs(value)
                if (selected !in tabs && tabs.isNotEmpty()) selected = tabs.first()
            }
            Spacer(Modifier.height(14.dp))
            val navScroll = rememberScrollState()
            Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth().verticalScroll(navScroll), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                matches.forEach { tab ->
                    val active = selected == tab
                    SettingsHoverSurface(onClick = { selected = tab; jump = null; filter = "" }, shape = RoundedCornerShape(10.dp),
                        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = .13f) else Color.Transparent,
                        contentColor = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 42.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(tab.icon, null, Modifier.size(18.dp), tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(tab.label, style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (matches.isEmpty()) Text(desktopText("No matching sections"), Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            VerticalScrollbar(rememberScrollbarAdapter(navScroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
            Text("Auralis · ${DesktopReleaseCheck.version}", Modifier.padding(horizontal = 10.dp).padding(top = 14.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (searching) desktopText("Search results") else selected.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (searching) desktopText(if (results.size == 1) "1 setting" else "%1\$s settings", results.size.toString()) else selected.hint,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onDismiss, Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))) {
                    Icon(Icons.Outlined.Close, desktopText("Close settings"), Modifier.size(18.dp))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            if (searching) SettingsSearchResults(results, ::open)
            else CompositionLocalProvider(LocalSettingJump provides jump) { if (displayed == SettingsTab.DISCORD) {
                val config by DiscordCardPreferences.store.settings.collectAsState()
                val presence = discordControl as? WindowsDiscordPresence
                val now = presence?.now?.collectAsState()?.value ?: DiscordNow()
                val status = presence?.status?.collectAsState()?.value ?: "Open Discord desktop to connect."
                DiscordSettingsContent(config, now, status, DiscordCardPreferences.store::update, DiscordCardPreferences.store::reset, onDismiss, embedded = true)
            } else key(displayed) {
                val scroll = rememberScrollState()
                val entrance = remember { Animatable(0f) }
                LaunchedEffect(Unit) { entrance.animateTo(1f, tween(180, easing = FastOutSlowInEasing)) }
                Box(Modifier.weight(1f).fillMaxWidth().graphicsLayer { alpha = entrance.value }) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    when (displayed) {
                        SettingsTab.GENERAL -> GeneralPane(stores)
                        SettingsTab.APPEARANCE -> { DesktopAppearanceExtras(); AppearanceSettingsPane(stores.appearance) }
                        SettingsTab.PLAYBACK -> PlaybackPane(stores)
                        SettingsTab.HOTKEYS -> HotkeysPane()
                        SettingsTab.SCROBBLING -> ScrobblingPane()
                        SettingsTab.DATA -> DataPane(stores)
                        SettingsTab.ABOUT -> AboutPane()
                        SettingsTab.CONTENT -> ContentSettingsPane(stores.content)
                        SettingsTab.AI -> AiTranslationSettingsPane(stores.aiTranslation)
                        SettingsTab.PRIVACY -> PrivacySettingsPane(stores.privacy, history, search)
                        else -> Unit
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).padding(vertical = 8.dp).fillMaxHeight())
                }
            } }
        }
    }
    }
}

/** Matching settings from every tab, each labelled with where it lives; choosing one opens it there. */
@Composable
private fun ColumnScope.SettingsSearchResults(results: List<SettingEntry>, onOpen: (SettingEntry) -> Unit) {
    val scroll = rememberScrollState()
    Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (results.isEmpty()) Text(desktopText("No settings match your search."), Modifier.padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            results.forEach { entry ->
                SettingsHoverSurface(onClick = { onOpen(entry) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(entry.tab.icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(desktopText(entry.title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            if (entry.description.isNotBlank()) Text(desktopText(entry.description), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${entry.tab.label} › ${desktopText(entry.group)}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = .85f))
                        }
                        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).padding(vertical = 8.dp).fillMaxHeight())
    }
}

@Composable
internal fun SettingsSearchField(value: String, onSubmit: () -> Unit = {}, onValueChange: (String) -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme
    BasicTextField(value, onValueChange, singleLine = true, interactionSource = interactions,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
        cursorBrush = SolidColor(scheme.primary),
        modifier = Modifier.fillMaxWidth().height(40.dp)
            // Enter opens the first result.
            .onPreviewKeyEvent { if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) { onSubmit(); true } else false }
            .background(scheme.surfaceContainer, RoundedCornerShape(10.dp))
            .border(1.dp, if (focused) scheme.primary.copy(alpha = .65f) else scheme.outlineVariant.copy(alpha = .5f), RoundedCornerShape(10.dp)),
        decorationBox = { input ->
            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Search, null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(desktopText("Search settings"), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    input()
                }
                if (value.isNotEmpty()) Box(Modifier.size(24.dp).clickable { onValueChange("") }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Close, desktopText("Clear search"), Modifier.size(14.dp))
                }
            }
        })
}

@Composable private fun GeneralPane(stores: SettingsStores) {
    val scope = rememberCoroutineScope()
    val privacy by stores.privacy.settingsFlow.collectAsState(PrivacySettings())
    val language = DesktopAppLanguage.currentTag()
    val system by DesktopSystemPreferences.store.settings.collectAsState()
    val prefs = DesktopPreferences.store
    val revision by prefs.changes.collectAsState()
    var autostart by remember { mutableStateOf(runCatching { WindowsAutostart.enabled() }.getOrDefault(false)) }
    var error by remember { mutableStateOf("") }
    var testingNotification by remember { mutableStateOf(false) }
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) {
        if (focused) runCatching { WindowsAutostart.enabled() }.onSuccess { autostart = it }
    }
    val languages = remember { listOf("" to "System default") + DesktopAppLanguage.available().map {
        it to java.util.Locale.forLanguageTag(it).getDisplayName(java.util.Locale.forLanguageTag(it))
    } }
    SettingsGroup("Language") {
        SettingSearchableChoice("App language", "Applies to translated app text", language, DesktopAppLanguage.currentLabel(),
            languages) {
            DesktopAppLanguage.apply(it)
        }
    }
    SettingsGroup("Activity") {
        SettingToggle("Play history", "Record songs played on this PC", !privacy.pauseListenHistory) { scope.launch { stores.privacy.setPauseListenHistory(!it) } }
    }
    SettingsGroup("System") {
        SettingToggle("Close to system tray", "Keep playing when the window is closed", system.closeToTray) { DesktopSystemPreferences.store.update { s -> s.copy(closeToTray = it) } }
        SettingToggle("Song notifications", "Show the next song while Auralis is in the background", system.songNotifications) { DesktopSystemPreferences.store.update { s -> s.copy(songNotifications = it) } }
        if (system.songNotifications) TextButton(enabled = !testingNotification, onClick = {
            testingNotification = true
            scope.launch {
                try {
                    com.auralis.music.windows.media.WindowsSongNotifications.test()
                    error = ""
                } catch (failure: Exception) {
                    error = failure.message.orEmpty()
                } finally { testingNotification = false }
            }
        }) { Text(desktopText("Test notification")) }
        SettingToggle("Start at login", "Launch Auralis with your Windows account", autostart) { value ->
            runCatching { WindowsAutostart.set(value); WindowsAutostart.enabled() }
                .onSuccess { autostart = it; error = "" }.onFailure { error = it.message.orEmpty() }
        }
        if (autostart) SettingToggle("Start minimized", "Only for automatic startup; opening Auralis normally shows its window", prefs.bool("startMinimized")) { prefs.set("startMinimized", it) }
        SettingToggle("System title bar", "Use Windows window decorations", prefs.bool("systemTitlebar")) { prefs.set("systemTitlebar", it) }
        if (error.isNotEmpty()) Text(desktopText(error), color = MaterialTheme.colorScheme.error)
    }
}

@Composable private fun DesktopAppearanceExtras() {
    val prefs = DesktopPreferences.store; val revision by prefs.changes.collectAsState()
    SettingsGroup("Interface") {
        SettingSlider("Interface zoom", prefs.number("zoom", 1.0).toFloat(), .75f..1.5f, { "${(it*100).toInt()}%" }) { prefs.set("zoom", it.toDouble()) }
        SettingSlider("Corner roundness", prefs.number("roundness",12.0).toFloat(), 0f..28f, { "${it.toInt()} dp" }) { prefs.set("roundness",it.toDouble()) }
        SettingToggle("Open player on play", "Expand the main player when a new playback session starts", prefs.bool("openPlayer")) { prefs.set("openPlayer", it) }
        SettingSlider("Background tint",prefs.number("backgroundTint",0.0).toFloat(),0f..0.4f,{"${(it*100).toInt()}%"}) {prefs.set("backgroundTint",it.toDouble())}
        SettingToggle("Stage player", "Show cover and lyrics side by side in the main player",prefs.bool("stagePlayer",true)) {prefs.set("stagePlayer",it)}
        SettingToggle("Tabbed player", "Switch between lyrics and queue; turn off to show both",prefs.bool("tabbedPlayer",true)) {prefs.set("tabbedPlayer",it)}
    }
    SettingsGroup("Typography") {
        val fonts by produceState(listOf("Default"), revision.optInt("fontRevision")) {
            value = withContext(Dispatchers.IO) {
                listOf("Default") + java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toList()
            }
        }
        for ((key, label) in listOf("bodyFont" to "Body font", "headingFont" to "Heading font")) {
            SettingSearchableChoice(label, "Installed Windows fonts", prefs.text(key,"Default"), prefs.text(key,"Default"), fonts.map { it to it }) { prefs.set(key,it) }
        }
        var message by remember {mutableStateOf("")}
        SettingAction("Load font file", message.ifBlank { "Add a TTF or OTF font to the font lists" }, "Choose file") {runCatching {
            val picker=java.awt.FileDialog(null as java.awt.Frame?,"Load a TTF or OTF font",java.awt.FileDialog.LOAD);picker.isVisible=true
            val file=picker.file?.let {java.io.File(picker.directory,it)};picker.dispose()
            if(file!=null) {
                val font=java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,file)
                val target=java.io.File(com.auralis.music.util.desktopAppDataDir(),"fonts/${file.name}");target.parentFile.mkdirs();file.copyTo(target,overwrite=true)
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font)
                prefs.set("fontRevision",revision.optInt("fontRevision")+1);message="Loaded ${font.family}. Choose it from Body or Heading font."
            }
        }.onFailure {message=it.message.orEmpty()}}
        Text(desktopText("Fonts apply throughout the app. Theme and player controls below preserve your current choices."), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun PlaybackPane(stores: SettingsStores) {
    val settings by stores.settings.settingsFlow.collectAsState(PlayerSettings()); val scope = rememberCoroutineScope()
    val prefs = DesktopPreferences.store; val revision by prefs.changes.collectAsState()
    SettingsGroup("Audio") {
        SettingChoice("Audio quality", "Applies to the next stream", settings.audioQuality.name, AudioQuality.entries.map { it.name }) { scope.launch { stores.settings.setAudioQuality(AudioQuality.valueOf(it)) } }
        SettingToggle("Normalize volume", "VLC volume normalization, applied when the next song starts", prefs.bool("normalize")) { prefs.set("normalize", it) }
        SettingToggle("Autoplay", "Continue with recommendations when the queue ends", settings.autoLoadMore) { scope.launch { stores.settings.setAutoLoadMore(it) } }
        SettingToggle("Preload next song", "Resolve the upcoming audio stream in advance", settings.gaplessPlayback) { scope.launch { stores.settings.setGaplessPlayback(it) } }
        SettingSlider("Crossfade (experimental)",prefs.number("crossfadeSeconds",0.0).toFloat(),0f..12f,{if(it<.1f) desktopText("Off") else "%.1f".format(it) + " " + desktopText("Seconds")}) {prefs.set("crossfadeSeconds",it.toDouble())}
        Text(desktopText("Crossfade is suspended in Listen Together rooms. Preloading helps the next stream start before the outgoing song ends."),style=MaterialTheme.typography.bodySmall)
        SettingSlider("Playback speed", settings.playbackRate, .5f..2f, { "%.2f×".format(it) }) { scope.launch { stores.settings.setPlaybackRate(it) } }
    }
    SettingsGroup("Queue") {
        SettingToggle("Prevent duplicates", "Do not add the same song twice to the upcoming queue", prefs.bool("preventDuplicates",true)) { prefs.set("preventDuplicates",it) }
        SettingToggle("Sticky shuffle", "Keep shuffling when you start a different album or playlist", prefs.bool("stickyShuffle")) { prefs.set("stickyShuffle",it) }
        SettingToggle("Shuffle whole queue", "When shuffling, include earlier songs while retaining the current track", prefs.bool("shuffleWholeQueue")) { prefs.set("shuffleWholeQueue",it) }
    }
    SettingsGroup("Video") {
        SettingToggle("Music videos", "Show a synchronized video in the sidebar when the playing recording has one. Audio continues independently.",prefs.bool("musicVideos")) {prefs.set("musicVideos",it)}
        if(prefs.bool("musicVideos")) SettingToggle("Ambient light", "Sample the video colours for the sidebar background",prefs.bool("ambientVideo",true)) {prefs.set("ambientVideo",it)}
        SettingToggle("Hide music videos in search", "Show audio recordings instead of video results on your next search",prefs.bool("hideVideos")) {prefs.set("hideVideos",it)}
    }
    SettingsBlockedArtistsSection()
    SettingsGroup("Advanced · stream clients") {
        Text(desktopText("These are the clients supported by Auralis's extractor. Keep at least one enabled. Changes apply to the next stream lookup."),style=MaterialTheme.typography.bodySmall)
        val enabled=prefs.text("streamClients","VISIONOS").split(',').toSet()
        for(client in listOf("VISIONOS","IOS")) SettingToggle(client,checked=client in enabled,onChecked={on->
            val next=if(on) enabled+client else enabled-client
            if(next.isNotEmpty()) prefs.set("streamClients",next.joinToString(","))
        })
    }
    SettingsGroup("Lyrics source order") {
        SpicyLyricsKeySetting(stores.content)
        val available=com.auralis.music.data.network.LyricsClient.PICK_ORDER.filter {it !in setOf(LyricsProvider.LOCAL,LyricsProvider.YOUTUBE_CAPTIONS)}
        val chosen=prefs.text("lyricsPriority").split(',').mapNotNull {name->runCatching {LyricsProvider.valueOf(name)}.getOrNull()}
        SettingToggle("Use custom source priority", "Try enabled sources in this order before automatic fallback. Applies to newly fetched lyrics.",chosen.isNotEmpty()) {prefs.set("lyricsPriority",if(it) available.joinToString(",") {p->p.name} else "")}
        if(chosen.isNotEmpty()) chosen.forEachIndexed {index,provider->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(provider.name.replace('_',' '),Modifier.weight(1f))
            fun move(step:Int) {val next=chosen.toMutableList();val to=index+step;if(to in next.indices) {java.util.Collections.swap(next,index,to);prefs.set("lyricsPriority",next.joinToString(",") {it.name})}}
            TextButton({move(-1)},enabled=index>0) {Text("↑")};TextButton({move(1)},enabled=index<chosen.lastIndex) {Text("↓")}
        }}
    }
    ContentSettingsPane(stores.content)
}

@Composable private fun HotkeysPane() {
    val prefs = DesktopPreferences.store; val revision by prefs.changes.collectAsState()
    val errors by DesktopGlobalHotkeys.errors.collectAsState()
    SettingToggle("Enable global hotkeys", "Works outside Auralis. Conflicts are reported beside each binding.", prefs.bool("hotkeysEnabled")) { prefs.set("hotkeysEnabled",it) }
    if (prefs.bool("hotkeysEnabled")) hotkeyActions.forEach { action ->
        var draft by remember(action.id) { mutableStateOf(prefs.text("hotkey.${action.id}",action.default)) }
        var error by remember { mutableStateOf("") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) { Text(desktopText(action.title)); Text(desktopText(error.ifBlank { errors[action.id].orEmpty() }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(draft, { draft=it }, Modifier.width(220.dp), singleLine=true)
            TextButton({ runCatching {
                if (draft.isNotBlank()) { val parsed = parseHotkey(draft); require(hotkeyActions.filter { it.id != action.id }.none { other ->
                    runCatching { parseHotkey(prefs.text("hotkey.${other.id}",other.default)) == parsed }.getOrDefault(false) }) { "Binding already assigned." } }
                prefs.set("hotkey.${action.id}", draft); error=""
            }.onFailure { error=it.message.orEmpty() } }) { Text(desktopText("Apply")) }
        }
    }
    Text(desktopText("Examples: Ctrl+Alt+P, Ctrl+Shift+F9. Clear a field and Apply to remove a binding."), style=MaterialTheme.typography.bodySmall)
    TextButton({ hotkeyActions.forEach { prefs.set("hotkey.${it.id}",it.default) } }) { Text(desktopText("Reset bindings")) }
}

@Composable private fun ScrobblingPane() {
    val cfg by ScrobblePreferences.changes.collectAsState(); val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    fun action(block: () -> String) { busy=true; scope.launch { message=withContext(Dispatchers.IO) { runCatching(block).getOrElse { it.message ?: "Connection failed" } }; busy=false } }
    fun set(key:String,value:Any) = ScrobblePreferences.set(key,value)
    SettingsGroup("Last.fm") {
        Text(desktopText("Use your Last.fm API application key and shared secret. Your credentials are protected by your Windows account."), style=MaterialTheme.typography.bodySmall)
        SecretField("API key", "apiKey"); SecretField("Shared secret", "apiSecret")
        if (ScrobblePreferences.text("session").isBlank()) {
            Button({ action { val url=ScrobbleApi.beginLastfm(); java.awt.Desktop.getDesktop().browse(java.net.URI(url)); "Approve Auralis in your browser, then finish connecting." } }, enabled=!busy) { Text(desktopText("Connect in browser")) }
            if (ScrobblePreferences.text("requestToken").isNotBlank()) Button({ action { ScrobbleApi.finishLastfm(); "Connected to Last.fm." } },enabled=!busy) { Text(desktopText("Finish connecting")) }
        } else {
            SettingToggle("Scrobble to Last.fm · ${ScrobblePreferences.text("lastfmUser")}", checked=ScrobblePreferences.bool("lastfmEnabled"), onChecked={set("lastfmEnabled",it)})
            TextButton({set("lastfmEnabled",false);set("session","");set("lastfmUser","")}) {Text(desktopText("Disconnect Last.fm"))}
        }
    }
    SettingsGroup("ListenBrainz") {
        SecretField("User token from listenbrainz.org/profile", "lbToken")
        Button({ action { val user=ScrobbleApi.validateListenBrainz(ScrobblePreferences.text("lbToken"));set("lbUser",user);set("lbEnabled",true);"Connected as $user." } },enabled=!busy) {Text(desktopText("Validate and connect"))}
        SettingToggle("Scrobble to ListenBrainz", checked=ScrobblePreferences.bool("lbEnabled"), onChecked={set("lbEnabled",it)})
        TextButton({set("lbEnabled",false);set("lbToken","");set("lbUser","")}) {Text(desktopText("Disconnect ListenBrainz"))}
    }
    SettingsGroup("Timing & metadata") {
        SettingToggle("Send now playing", checked=ScrobblePreferences.bool("nowPlaying",true), onChecked={set("nowPlaying",it)})
        SettingSlider("Track percentage", ScrobblePreferences.number("percent",50.0).toFloat(),50f..100f,{"${it.toInt()}%"}) {set("percent",it.toDouble())}
        SettingToggle("Use a time threshold", "Turn off to use only the track percentage",ScrobblePreferences.number("minutes",4.0)>0) {set("minutes",if(it) 4.0 else 0.0)}
        if(ScrobblePreferences.number("minutes",4.0)>0) SettingSlider("Maximum listening time", ScrobblePreferences.number("minutes",4.0).toFloat(),4f..30f,{"${it.toInt()} minutes"}) {set("minutes",it.toDouble())}
        Text(desktopText("A play counts after the earlier threshold; paused time and seek jumps do not count. Tracks shorter than 30 seconds are excluded."),style=MaterialTheme.typography.bodySmall)
        SettingToggle("Clean title labels", "Remove Official Audio, Music Video and lyrics suffixes",ScrobblePreferences.bool("cleanTitle",true)) {set("cleanTitle",it)}
        SettingToggle("Remove artist Topic suffix", checked=ScrobblePreferences.bool("stripTopic",true),onChecked={set("stripTopic",it)})
        SettingToggle("Split music video titles", "Read Artist - Title from video titles",ScrobblePreferences.bool("splitVideoTitles")) {set("splitVideoTitles",it)}
        SettingToggle("Primary artist only", "Exclude featured artists from scrobbles",ScrobblePreferences.bool("primaryArtist")) {set("primaryArtist",it)}
        if(ScrobblePreferences.bool("primaryArtist")) SettingToggle("Strict artist splitting", "Also split commas, ampersands and collaboration separators",ScrobblePreferences.bool("primaryStrict")) {set("primaryStrict",it)}
        SettingToggle("Skip local files", checked=ScrobblePreferences.bool("skipLocal"),onChecked={set("skipLocal",it)})
        val rules = runCatching { org.json.JSONArray(ScrobblePreferences.text("rules").ifBlank {"[]"}) }.getOrElse { org.json.JSONArray() }
        for (index in 0 until rules.length()) {
            val rule=rules.getJSONObject(index)
            key(rule.optString("id",index.toString())) {
                Column(Modifier.fillMaxWidth().border(1.dp,MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(12.dp)).padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    fun change(name:String,value:Any) {rule.put(name,value);set("rules",rules.toString())}
                    SettingToggle("Rule ${index+1}",checked=rule.optBoolean("enabled",true),onChecked={change("enabled",it)})
                    SettingChoice("Field",selected=rule.optString("field","title"),options=listOf("title","artist","album")) {change("field",it)}
                    OutlinedTextField(rule.optString("find"),{change("find",it)},Modifier.fillMaxWidth(),label={Text(desktopText("Find"))},singleLine=true)
                    OutlinedTextField(rule.optString("replace"),{change("replace",it)},Modifier.fillMaxWidth(),label={Text(desktopText("Replace with"))},singleLine=true)
                    SettingToggle("Regular expression",checked=rule.optBoolean("regex"),onChecked={change("regex",it)})
                    if(rule.optBoolean("regex") && runCatching {Regex(rule.optString("find"))}.isFailure) Text(desktopText("Invalid pattern; this rule is ignored."),color=MaterialTheme.colorScheme.error)
                    TextButton({rules.remove(index);set("rules",rules.toString())}) {Text(desktopText("Remove rule"))}
                }
            }
        }
        TextButton({rules.put(org.json.JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("field","title").put("find","").put("replace","").put("enabled",true));set("rules",rules.toString())}) {Text(desktopText("Add metadata rule"))}
        TextButton({runCatching {
            val picker=java.awt.FileDialog(null as java.awt.Frame?,"Export metadata rules",java.awt.FileDialog.SAVE);picker.file="auralis-scrobble-rules.json";picker.isVisible=true
            picker.file?.let {java.io.File(picker.directory,it).writeText(rules.toString(2))};picker.dispose()
        }.onFailure {message=it.message.orEmpty()}}) {Text(desktopText("Export rules"))}
        TextButton({runCatching {
            val picker=java.awt.FileDialog(null as java.awt.Frame?,"Import metadata rules",java.awt.FileDialog.LOAD);picker.isVisible=true
            picker.file?.let { val imported=org.json.JSONArray(java.io.File(picker.directory,it).readText());require(imported.length()<=100) {"Too many rules."};require((0 until imported.length()).all {index->imported.optJSONObject(index)?.optString("field") in setOf("title","artist","album")}) {"Each rule must have a title, artist or album field."};set("rules",imported.toString()) };picker.dispose()
        }.onFailure {message=it.message.orEmpty()}}) {Text(desktopText("Import rules"))}
    }
    val current = com.auralis.music.ui.settings.LocalSettingsPlatform.current.nowPlaying?.track?.collectAsState()?.value
    if(current!=null) SettingsGroup("Current song preview & edit") {
        val metadata=ScrobblePreferences.metadata(current)
        Text("${metadata.first}\n${metadata.second}\n${metadata.third}")
        var title by remember(current.id) {mutableStateOf(metadata.first)}
        var artist by remember(current.id) {mutableStateOf(metadata.second)}
        var album by remember(current.id) {mutableStateOf(metadata.third)}
        OutlinedTextField(title,{title=it},Modifier.fillMaxWidth(),label={Text(desktopText("Title"))})
        OutlinedTextField(artist,{artist=it},Modifier.fillMaxWidth(),label={Text(desktopText("Artist"))})
        OutlinedTextField(album,{album=it},Modifier.fillMaxWidth(),label={Text(desktopText("Album"))})
        TextButton({val edits=org.json.JSONObject(ScrobblePreferences.text("edits").ifBlank {"{}"});edits.put(current.id,org.json.JSONObject().put("title",title).put("artist",artist).put("album",album));set("edits",edits.toString())}) {Text(desktopText("Save correction for this song"))}
        TextButton({val edits=org.json.JSONObject(ScrobblePreferences.text("edits").ifBlank {"{}"});edits.remove(current.id);set("edits",edits.toString())}) {Text(desktopText("Remove song correction"))}
        val edits=org.json.JSONObject(ScrobblePreferences.text("edits").ifBlank {"{}"})
        SettingToggle("Skip this song",checked=edits.optJSONObject(current.id)?.optBoolean("skip") == true,onChecked={skip->val edit=edits.optJSONObject(current.id) ?: org.json.JSONObject();edit.put("skip",skip);edits.put(current.id,edit);set("edits",edits.toString())})
    }
    val savedEdits=org.json.JSONObject(ScrobblePreferences.text("edits").ifBlank {"{}"})
    if(savedEdits.length()>0) SettingsGroup("Saved song corrections") {
        savedEdits.keys().asSequence().toList().forEach {id->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(savedEdits.getJSONObject(id).optString("title",id),Modifier.weight(1f))
            TextButton({savedEdits.remove(id);set("edits",savedEdits.toString())}) {Text(desktopText("Remove"))}
        }}
    }
    Text(desktopText(message), color=MaterialTheme.colorScheme.primary)
    val status by DesktopScrobbler.status.collectAsState(); Text(desktopText(status),style=MaterialTheme.typography.bodySmall)
}

@Composable private fun SecretField(label:String,key:String) {
    var value by remember(key) { mutableStateOf(ScrobblePreferences.text(key)) }
    OutlinedTextField(value,{value=it;ScrobblePreferences.set(key,it.trim())},Modifier.fillMaxWidth(),label={Text(desktopText(label))},singleLine=true,visualTransformation=PasswordVisualTransformation())
}

@Composable private fun DataPane(stores:SettingsStores) {
    val content by stores.content.settingsFlow.collectAsState(ContentSettings()); val scope=rememberCoroutineScope()
    var proxy by remember {mutableStateOf(content.proxyUrl)}
    SettingsGroup("Network") {
        SettingToggle("Use proxy", "New connections use this proxy",content.proxyEnabled) {scope.launch {stores.content.update {s->s.copy(proxyEnabled=it)}}}
        SettingChoice("Proxy type",selected=content.proxyType.name,options=ProxyType.entries.map {it.name}) {scope.launch {stores.content.update {s->s.copy(proxyType=ProxyType.valueOf(it))}}}
        OutlinedTextField(proxy,{proxy=it},Modifier.fillMaxWidth(),label={Text(desktopText("Proxy host:port"))},singleLine=true)
        var message by remember {mutableStateOf("")}
        Button({if(com.auralis.music.data.network.ContentProxy.parseHostPort(proxy)!=null) scope.launch {stores.content.update {it.copy(proxyUrl=proxy)};message="Proxy saved."} else message="Enter a valid host and port."}) {Text(desktopText("Save proxy"))}
        Text(desktopText(message),style=MaterialTheme.typography.bodySmall)
    }
    val downloads=com.auralis.music.desktop.library.DesktopDownloads.instance; val root by downloads.rootDirectory.collectAsState()
    SettingsGroup("Downloads") {
        Text(root.absolutePath);TextButton({java.awt.Desktop.getDesktop().open(root)}) {Text(desktopText("Open download folder"))}
        var message by remember {mutableStateOf("")}
        TextButton({scope.launch {runCatching {withContext(Dispatchers.IO) {
            com.auralis.music.windows.library.NativeFolderPicker.chooseFolder(root)?.let {downloads.changeDirectory(it,false)}
        }}.onSuccess {message="Download location saved. Existing files stay available at their current locations."}.onFailure {message=it.message.orEmpty()}}}) {Text(desktopText("Change folder"))}
        Text(desktopText(message),style=MaterialTheme.typography.bodySmall)
    }
    SettingsGroup("Caches") {
        var refresh by remember {mutableStateOf(0)}; var message by remember {mutableStateOf("")}
        val bytes by produceState<Long?>(null, refresh) {
            value = withContext(Dispatchers.IO) { DesktopCaches.imageBytes() + DesktopCaches.lyricsBytes() }
        }
        Text(if (bytes == null) desktopText("Calculating cache size…") else
            desktopText("Artwork and lyrics: %1\$s", com.auralis.music.util.formatBytes(bytes!!)))
        Button({scope.launch {withContext(Dispatchers.IO) {DesktopCaches.clearImages();DesktopCaches.clearLyrics();com.auralis.music.desktop.playback.DesktopStreamResolver.clearCache()};refresh++;message="Caches cleared. Downloads and accounts are preserved."}}) {Text(desktopText("Clear caches"))}
        Text(desktopText(message),style=MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun AboutPane() {
    val scope=rememberCoroutineScope();var message by remember {mutableStateOf("")};var url by remember {mutableStateOf<String?>(null)};var busy by remember {mutableStateOf(false)}
    val prefs=DesktopPreferences.store;val revision by prefs.changes.collectAsState()
    Text("Auralis · ${DesktopReleaseCheck.version}",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
    Text(desktopText("Auralis for Windows · Compose Multiplatform · VLC"))
    SettingsGroup("Credits") {
        TextButton({ java.awt.Desktop.getDesktop().browse(java.net.URI("https://github.com/Spikerko/spicy-lyrics")) }) {
            Text(desktopText("Animation inspired by Spicy Lyrics"))
        }
        Text(desktopText("Lyrics providers and community contributors are credited alongside the lyrics they supply."),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SettingsGroup("Updates") {
        SettingToggle("Include prereleases",checked=prefs.bool("betaUpdates"),onChecked={prefs.set("betaUpdates",it)})
        Button({busy=true;scope.launch {runCatching {withContext(Dispatchers.IO) {DesktopReleaseCheck.check(prefs.bool("betaUpdates"))}}.onSuccess {message=it.first;url=it.second}.onFailure {message=it.message.orEmpty()};busy=false}},enabled=!busy) {Text(desktopText(if(busy) "Checking…" else "Check for updates"))}
        Text(desktopText(message))
        url?.let {link->TextButton({java.awt.Desktop.getDesktop().browse(java.net.URI(link))}) {Text(desktopText("View release and installers"))}}
    }
    SettingsGroup("Support & diagnostics") {
        TextButton({java.awt.Desktop.getDesktop().browse(java.net.URI(DesktopReleaseCheck.REPOSITORY))}) {Text(desktopText("Source code and licenses"))}
        TextButton({java.awt.Desktop.getDesktop().browse(java.net.URI(DesktopReleaseCheck.REPOSITORY+"/issues/new"))}) {Text(desktopText("Report a problem"))}
        val clipboard=androidx.compose.ui.platform.LocalClipboardManager.current
        TextButton({clipboard.setText(androidx.compose.ui.text.AnnotatedString(DesktopReleaseCheck.diagnostics()));message="Diagnostics copied. No tokens, cookies or account identifiers are included."}) {Text(desktopText("Copy diagnostics"))}
        TextButton({runCatching {
            val picker=java.awt.FileDialog(null as java.awt.Frame?,"Save diagnostics",java.awt.FileDialog.SAVE);picker.file="auralis-diagnostics.txt";picker.isVisible=true
            picker.file?.let {java.io.File(picker.directory,it).writeText(DesktopReleaseCheck.diagnostics());message="Diagnostics saved."};picker.dispose()
        }.onFailure {message=it.message.orEmpty()}}) {Text(desktopText("Save diagnostics"))}
    }
}
