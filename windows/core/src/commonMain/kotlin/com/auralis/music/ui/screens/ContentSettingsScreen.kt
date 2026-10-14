package com.auralis.music.ui.screens

import com.auralis.music.ui.components.Switch

import com.auralis.music.ui.i18n.Strings

import com.auralis.music.ui.i18n.str

import androidx.compose.foundation.BorderStroke
import com.auralis.music.ui.components.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Explicit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import com.auralis.music.ui.components.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import com.auralis.music.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.data.datastore.ContentSettingsDataStore
import com.auralis.music.data.datastore.ContentSettingsStore
import com.auralis.music.data.network.ContentProxy
import com.auralis.music.data.network.YouTubeLocales
import com.auralis.music.domain.model.AddToPlaylistPosition
import com.auralis.music.domain.model.ContentSettings
import com.auralis.music.domain.model.LyricsProviderId
import com.auralis.music.domain.model.ProxyType
import com.auralis.music.domain.model.QuickPicksMode
import com.auralis.music.domain.model.RomanizationScript
import com.auralis.music.ui.components.bottomChromePadding
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicOnBackground
import com.auralis.music.ui.theme.dynamicOnSurface
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.theme.dynamicSurface
import kotlinx.coroutines.launch

/**
 * Settings → Content. Mirrors Metrolist's Content settings (ui/screens/settings/ContentSettings.kt,
 * GPL-3.0); every row changes real behaviour, see [ContentSettings] for where each is read.
 */
@Composable
fun ContentSettingsScreen(onDismiss: () -> Unit) {
    val platformActions = com.auralis.music.ui.components.LocalPlatformActions.current
    val scope = rememberCoroutineScope()
    val store = com.auralis.music.data.datastore.LocalSettingsStores.current.content
    val appLanguage = com.auralis.music.ui.settings.LocalSettingsPlatform.current.appLanguage
    val settings by ContentSettingsStore.current.collectAsState()
    fun update(transform: (ContentSettings) -> ContentSettings) {
        scope.launch { store.update(transform) }
    }

    var dialog by remember { mutableStateOf<ContentDialog?>(null) }

    val primary = MaterialTheme.dynamicPrimary
    val surface = MaterialTheme.dynamicSurface
    val onSurface = MaterialTheme.dynamicOnSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val onBackground = MaterialTheme.dynamicOnBackground
    val colors = RowColors(primary, onSurface, onSurfaceVariant)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.dynamicBackground) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(Strings.back), tint = onBackground)
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = str(Strings.content),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = onBackground,
                    fontSize = 21.sp
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = bottomChromePadding(includeNavigationBar = false),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item(key = "general") {
                    Section(str(Strings.general), primary, surface) {
                        ValueRow(Icons.Default.Language, str(Strings.content_language),
                            languageName(settings.contentLanguage), colors) { dialog = ContentDialog.CONTENT_LANGUAGE }
                        Divider()
                        ValueRow(Icons.Default.Public, str(Strings.content_country),
                            countryName(settings.contentCountry), colors) { dialog = ContentDialog.CONTENT_COUNTRY }
                    }
                }

                item(key = "playlists") {
                    Section(str(Strings.playlists), primary, surface) {
                        ValueRow(Icons.AutoMirrored.Filled.PlaylistAdd, str(Strings.add_to_playlist_position),
                            com.auralis.music.ui.i18n.UiLabels.of(settings.addToPlaylistPosition.displayName), colors) { dialog = ContentDialog.PLAYLIST_POSITION }
                    }
                }

                item(key = "artist") {
                    Section(str(Strings.artist_page), primary, surface) {
                        SwitchRow(Icons.Default.Description, str(Strings.show_artist_description), null, settings.showArtistDescription, colors) { v ->
                            update { it.copy(showArtistDescription = v) }
                        }
                        Divider()
                        SwitchRow(Icons.Default.People, str(Strings.show_subscriber_count), null, settings.showArtistSubscriberCount, colors) { v ->
                            update { it.copy(showArtistSubscriberCount = v) }
                        }
                        Divider()
                        SwitchRow(Icons.Default.Groups, str(Strings.show_monthly_listeners), null, settings.showMonthlyListeners, colors) { v ->
                            update { it.copy(showMonthlyListeners = v) }
                        }
                    }
                }

                item(key = "app_language") {
                    Section(str(Strings.app_language), primary, surface) {
                        ValueRow(Icons.Default.Translate, str(Strings.app_language), appLanguage?.currentLabel() ?: str(Strings.system_default), colors) {
                            if (appLanguage != null) dialog = ContentDialog.APP_LANGUAGE
                            else platformActions.showMessage(com.auralis.music.ui.settings.PlatformNotes.appLanguageFollowsSystem)
                        }
                    }
                }

                item(key = "proxy") {
                    Section(str(Strings.proxy), primary, surface) {
                        SwitchRow(Icons.Default.VpnLock, str(Strings.enable_proxy),
                            if (settings.proxyEnabled && ContentProxy.configuredProxy(settings) == null) str(Strings.set_a_host_port_to_use_it) else null,
                            settings.proxyEnabled, colors) { v ->
                            update { it.copy(proxyEnabled = v) }
                            if (v && settings.proxyUrl.isBlank()) dialog = ContentDialog.PROXY
                        }
                        if (settings.proxyEnabled) {
                            Divider()
                            ValueRow(Icons.Default.Tune, str(Strings.configure_proxy),
                                settings.proxyUrl.ifBlank { str(Strings.not_set) }.let { "${settings.proxyType} · $it" }, colors) {
                                dialog = ContentDialog.PROXY
                            }
                        }
                    }
                }

                item(key = "lyrics") {
                    Section(str(Strings.lyrics), primary, surface) {
                        val enabledCount = LyricsProviderId.entries.size - settings.disabledLyricsProviders.size
                        ValueRow(Icons.Default.Lyrics, str(Strings.lyrics_providers),
                            str(Strings.x_of_x_enabled, enabledCount, LyricsProviderId.entries.size), colors) { dialog = ContentDialog.LYRICS_PROVIDERS }
                        Divider()
                        SwitchRow(Icons.Default.SortByAlpha, str(Strings.romanize_lyrics),
                            str(Strings.latin_spelling_as_a_small_line_under_the), settings.romanizationEnabled, colors) { v ->
                            update { it.copy(romanizationEnabled = v) }
                        }
                        if (settings.romanizationEnabled) {
                            Divider()
                            ValueRow(Icons.Default.Translate, str(Strings.romanized_scripts),
                                settings.romanizedScripts.size.let { if (it == RomanizationScript.entries.size) "All" else "$it selected" },
                                colors) { dialog = ContentDialog.ROMANIZATION }
                        }
                    }
                }

                item(key = "wrapped") {
                    Section(str(Strings.wrapped), primary, surface) {
                        SwitchRow(Icons.Default.Leaderboard, str(Strings.weekly_and_monthly_most_playlists),
                            str(Strings.your_most_played_songs_of_the_last_7_and), settings.showMostStatsPlaylists, colors) { v ->
                            update { it.copy(showMostStatsPlaylists = v) }
                        }
                        Divider()
                        SwitchRow(Icons.Default.AutoAwesome, str(Strings.show_wrapped_card), str(Strings.on_the_home_screen), settings.showWrappedCard, colors) { v ->
                            update { it.copy(showWrappedCard = v) }
                        }
                    }
                }

                item(key = "misc") {
                    Section(str(Strings.misc), primary, surface) {
                        SwitchRow(Icons.Default.Shuffle, str(Strings.randomize_home_order),
                            str(Strings.shuffle_the_home_shelves_keeping_higher), settings.randomizeHomeOrder, colors) { v ->
                            update { it.copy(randomizeHomeOrder = v) }
                        }
                        Divider()
                        ValueRow(Icons.Default.FormatListNumbered, str(Strings.my_top_list_length),
                            str(Strings.x_songs, settings.topLength), colors) { dialog = ContentDialog.TOP_LENGTH }
                        Divider()
                        ValueRow(Icons.Default.FlashOn, str(Strings.quick_picks),
                            com.auralis.music.ui.i18n.UiLabels.of(settings.quickPicksMode.displayName), colors) { dialog = ContentDialog.QUICK_PICKS }
                    }
                }
            }
        }
    }

    when (dialog) {
        ContentDialog.CONTENT_LANGUAGE -> ChoiceDialog(
            title = str(Strings.content_language),
            note = str(Strings.youtube_shows_home_search_artist_pages_a) +
                str(Strings.some_languages_leave_out_play_counts_so),
            options = listOf(ContentSettings.SYSTEM to str(Strings.system_default)) + YouTubeLocales.languages.toList(),
            selected = settings.contentLanguage,
            onSelect = { code -> update { it.copy(contentLanguage = code) }; dialog = null },
            onDismiss = { dialog = null }
        )
        ContentDialog.CONTENT_COUNTRY -> ChoiceDialog(
            title = str(Strings.content_country),
            note = str(Strings.charts_recommendations_and_what_s_availa),
            options = listOf(ContentSettings.SYSTEM to str(Strings.system_default)) + YouTubeLocales.countries.toList(),
            selected = settings.contentCountry,
            onSelect = { code -> update { it.copy(contentCountry = code) }; dialog = null },
            onDismiss = { dialog = null }
        )
        ContentDialog.PLAYLIST_POSITION -> ChoiceDialog(
            title = str(Strings.add_to_playlist_position),
            note = null,
            options = AddToPlaylistPosition.entries.map { it.name to "${com.auralis.music.ui.i18n.UiLabels.of(it.displayName)} — ${com.auralis.music.ui.i18n.UiLabels.of(it.description)}" },
            selected = settings.addToPlaylistPosition.name,
            onSelect = { name -> update { it.copy(addToPlaylistPosition = AddToPlaylistPosition.valueOf(name)) }; dialog = null },
            onDismiss = { dialog = null }
        )
        ContentDialog.QUICK_PICKS -> ChoiceDialog(
            title = str(Strings.quick_picks),
            note = str(Strings.last_song_listened_builds_the_row_from_y),
            options = QuickPicksMode.entries.map { it.name to com.auralis.music.ui.i18n.UiLabels.of(it.displayName) },
            selected = settings.quickPicksMode.name,
            onSelect = { name -> update { it.copy(quickPicksMode = QuickPicksMode.valueOf(name)) }; dialog = null },
            onDismiss = { dialog = null }
        )
        ContentDialog.TOP_LENGTH -> TopLengthDialog(settings.topLength, onSave = { n ->
            update { it.copy(topLength = n) }; dialog = null
        }, onDismiss = { dialog = null })
        ContentDialog.PROXY -> ProxyDialog(settings, onSave = { type, url, user, pass ->
            update { it.copy(proxyType = type, proxyUrl = url, proxyUsername = user, proxyPassword = pass) }
            dialog = null
        }, onDismiss = { dialog = null })
        ContentDialog.LYRICS_PROVIDERS -> CheckListDialog(
            title = str(Strings.lyrics_providers),
            items = LyricsProviderId.entries.map { Triple(it.name, it.displayName, com.auralis.music.ui.i18n.UiLabels.of(it.description)) },
            checked = LyricsProviderId.entries.filterNot { it in settings.disabledLyricsProviders }.map { it.name }.toSet(),
            onSave = { enabled ->
                update { s -> s.copy(disabledLyricsProviders = LyricsProviderId.entries.filterNot { it.name in enabled }.toSet()) }
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        ContentDialog.ROMANIZATION -> CheckListDialog(
            title = str(Strings.romanized_scripts),
            items = RomanizationScript.entries.map { Triple(it.name, com.auralis.music.ui.i18n.UiLabels.of(it.displayName), null) },
            checked = settings.romanizedScripts.map { it.name }.toSet(),
            onSave = { chosen ->
                update { s -> s.copy(romanizedScripts = RomanizationScript.entries.filter { it.name in chosen }.toSet()) }
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        ContentDialog.APP_LANGUAGE -> {
            // Picked here on every Android version: Android 13+'s own page proved unreliable on
            // some phones (the choice didn't save), and this applies at once.
            val current = appLanguage?.currentTag().orEmpty()
            ChoiceDialog(
                title = str(Strings.app_language),
                note = null,
                options = listOf("" to str(Strings.system_default)) +
                    appLanguage?.available().orEmpty().map { tag ->
                        val locale = java.util.Locale.forLanguageTag(tag)
                        tag to locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
                    },
                selected = current,
                onSelect = { tag ->
                    dialog = null
                    if (tag != current) {
                        appLanguage?.apply(tag)
                    }
                },
                onDismiss = { dialog = null }
            )
        }
        null -> Unit
    }

    // Toast once when the proxy is on but unusable, so a typo doesn't silently go direct.
    val proxyUnusable = settings.proxyEnabled && settings.proxyUrl.isNotBlank() && ContentProxy.configuredProxy(settings) == null
    androidx.compose.runtime.LaunchedEffect(proxyUnusable, settings.proxyUrl) {
        if (proxyUnusable) {
            platformActions.showMessage(str(Strings.proxy_address_isn_t_host_port_connecting))
        }
    }
}

private enum class ContentDialog {
    CONTENT_LANGUAGE, CONTENT_COUNTRY, PLAYLIST_POSITION, QUICK_PICKS, TOP_LENGTH, PROXY,
    LYRICS_PROVIDERS, ROMANIZATION, APP_LANGUAGE
}

private data class RowColors(val primary: Color, val onSurface: Color, val onSurfaceVariant: Color)

private fun languageName(code: String): String =
    if (code == ContentSettings.SYSTEM) str(Strings.system_default) else YouTubeLocales.languages[code] ?: code

private fun countryName(code: String): String =
    if (code == ContentSettings.SYSTEM) str(Strings.system_default) else YouTubeLocales.countries[code] ?: code

@Composable
private fun Section(title: String, primary: Color, surface: Color, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = primary,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
        )
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
    )
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    colors: RowColors,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = colors.onSurfaceVariant, fontSize = 12.5.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.primary)
        )
    }
}

@Composable
private fun ValueRow(icon: ImageVector, title: String, value: String, colors: RowColors, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(value, color = colors.onSurfaceVariant, fontSize = 12.5.sp)
        }
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    note: String?,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.dynamicSurface,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (note != null) {
                    Text(note, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.padding(top = 8.dp))
                }
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(options, key = { it.first }) { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(value) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = value == selected,
                                onClick = { onSelect(value) },
                                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.dynamicPrimary)
                            )
                            Text(label, fontSize = 14.5.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(str(Strings.close)) } }
    )
}

@Composable
private fun CheckListDialog(
    title: String,
    items: List<Triple<String, String, String?>>,
    checked: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var current by remember { mutableStateOf(checked) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.dynamicSurface,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                items(items, key = { it.first }) { (id, label, description) ->
                    val isOn = id in current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { current = if (isOn) current - id else current + id }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isOn,
                            onCheckedChange = { current = if (it) current + id else current - id },
                            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.dynamicPrimary)
                        )
                        Column {
                            Text(label, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                            if (description != null) {
                                Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(current) }) { Text(str(Strings.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(Strings.cancel)) } }
    )
}

@Composable
private fun TopLengthDialog(current: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val parsed = text.toIntOrNull()?.takeIf { it in 1..500 }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.dynamicSurface,
        title = { Text(str(Strings.my_top_list_length), fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(3) },
                label = { Text(str(Strings.songs_1_500)) },
                singleLine = true,
                isError = parsed == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onSave) }, enabled = parsed != null) { Text(str(Strings.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(Strings.cancel)) } }
    )
}

@Composable
private fun ProxyDialog(
    settings: ContentSettings,
    onSave: (ProxyType, String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var type by remember { mutableStateOf(settings.proxyType) }
    var url by remember { mutableStateOf(settings.proxyUrl) }
    var auth by remember { mutableStateOf(settings.hasProxyAuth) }
    var user by remember { mutableStateOf(settings.proxyUsername) }
    var pass by remember { mutableStateOf(settings.proxyPassword) }
    val valid = ContentProxy.parseHostPort(url) != null && (!auth || user.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.dynamicSurface,
        title = { Text(str(Strings.configure_proxy), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ProxyType.entries.forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = type == t,
                            onClick = { type = t },
                            shape = SegmentedButtonDefaults.itemShape(i, ProxyType.entries.size)
                        ) { Text(t.name) }
                    }
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    label = { Text(str(Strings.host_port)) },
                    singleLine = true,
                    isError = url.isNotBlank() && ContentProxy.parseHostPort(url) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(str(Strings.authentication), modifier = Modifier.weight(1f))
                    Switch(checked = auth, onCheckedChange = {
                        auth = it
                        if (!it) { user = ""; pass = "" }
                    })
                }
                if (auth) {
                    OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text(str(Strings.username)) }, singleLine = true)
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text(str(Strings.password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(type, url, if (auth) user else "", if (auth) pass else "") }, enabled = valid) {
                Text(str(Strings.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(Strings.cancel)) } }
    )
}
