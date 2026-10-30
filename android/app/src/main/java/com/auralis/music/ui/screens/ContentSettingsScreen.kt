package com.auralis.music.ui.screens

import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ContentSettingsDataStore(context) }
    val settings by ContentSettingsStore.current.collectAsState()
    fun update(transform: (ContentSettings) -> ContentSettings) {
        scope.launch { store.update(transform) }
    }

    var dialog by remember { mutableStateOf<ContentDialog?>(null) }
    val catalogSource by com.auralis.music.data.datastore.CatalogSettingsStore.source.collectAsState()
    val spotifySignedIn by com.auralis.music.data.network.SpotifySession.signedIn.collectAsState()
    var showCatalogPicker by remember { mutableStateOf(false) }
    var showSpotifyLogin by remember { mutableStateOf(false) }
    var showSpotifyDisconnect by remember { mutableStateOf(false) }
    if (showSpotifyDisconnect) com.auralis.music.ui.components.AuralisConfirmDialog(
        title = "Disconnect Spotify?",
        message = "Imported playlists stay in your library. Search will use YouTube Music.",
        confirmLabel = "Disconnect",
        icon = Icons.Default.LinkOff,
        onConfirm = {
            com.auralis.music.data.network.SpotifySession.signOut()
            com.auralis.music.data.datastore.CatalogSettingsStore.select(com.auralis.music.data.datastore.CatalogSource.YOUTUBE_MUSIC)
            showSpotifyDisconnect = false
        },
        onDismiss = { showSpotifyDisconnect = false }
    )
    if (showSpotifyLogin) {
        SpotifySignInScreen(onSignedIn = {
            com.auralis.music.data.datastore.CatalogSettingsStore.select(com.auralis.music.data.datastore.CatalogSource.SPOTIFY)
            showSpotifyLogin = false
        }, onDismiss = { showSpotifyLogin = false })
        return
    }
    if (showCatalogPicker) AlertDialog(
        onDismissRequest = { showCatalogPicker = false },
        title = { Text("Search catalog") },
        text = { Column {
            com.auralis.music.data.datastore.CatalogSource.entries.forEach { source ->
                TextButton(onClick = {
                    showCatalogPicker = false
                    if (source == com.auralis.music.data.datastore.CatalogSource.SPOTIFY && !spotifySignedIn) showSpotifyLogin = true
                    else com.auralis.music.data.datastore.CatalogSettingsStore.select(source)
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(source.label + if (source == catalogSource) " ✓" else "")
                }
            }
            Text("Spotify supplies catalog details. Audio plays from YouTube. If Spotify is unavailable, search falls back to YouTube Music.", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = { showCatalogPicker = false }) { Text("Close") } }
    )

    val primary = MaterialTheme.dynamicPrimary
    val surface = MaterialTheme.dynamicSurface
    val onSurface = MaterialTheme.dynamicOnSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val onBackground = MaterialTheme.dynamicOnBackground

    SettingsPage(title = str(R.string.content), onBack = onDismiss) {
        item(key = "catalog") {
            SettingsGroup("Music catalog") {
                ValueRow(Icons.Default.Public, "Search catalog", catalogSource.label) { showCatalogPicker = true }
                ValueRow(Icons.Default.People, "Spotify account", if (spotifySignedIn) "Connected · Tap to disconnect" else "Connect for catalog and playlist access") {
                    if (!spotifySignedIn) showSpotifyLogin = true
                    else showSpotifyDisconnect = true
                }
                Text("No paid API key required. Playback continues through YouTube.", color = onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            }
        }
        item(key = "general") {
            SettingsGroup(str(R.string.general)) {
                ValueRow(Icons.Default.Language, str(R.string.content_language),
                    languageName(settings.contentLanguage)) { dialog = ContentDialog.CONTENT_LANGUAGE }
                ValueRow(Icons.Default.Public, str(R.string.content_country),
                    countryName(settings.contentCountry)) { dialog = ContentDialog.CONTENT_COUNTRY }
            }
        }

        item(key = "playlists") {
            SettingsGroup(str(R.string.playlists)) {
                ValueRow(Icons.AutoMirrored.Filled.PlaylistAdd, str(R.string.add_to_playlist_position),
                    com.auralis.music.ui.i18n.UiLabels.of(settings.addToPlaylistPosition.displayName)) { dialog = ContentDialog.PLAYLIST_POSITION }
            }
        }

        item(key = "artist") {
            SettingsGroup(str(R.string.artist_page)) {
                SwitchRow(Icons.Default.Description, str(R.string.show_artist_description), null, settings.showArtistDescription) { v ->
                    update { it.copy(showArtistDescription = v) }
                }
                SwitchRow(Icons.Default.People, str(R.string.show_subscriber_count), null, settings.showArtistSubscriberCount) { v ->
                    update { it.copy(showArtistSubscriberCount = v) }
                }
                SwitchRow(Icons.Default.Groups, str(R.string.show_monthly_listeners), null, settings.showMonthlyListeners) { v ->
                    update { it.copy(showMonthlyListeners = v) }
                }
            }
        }

        item(key = "app_language") {
            SettingsGroup(str(R.string.app_language)) {
                ValueRow(Icons.Default.Translate, str(R.string.app_language), currentAppLanguageLabel(context)) {
                    dialog = ContentDialog.APP_LANGUAGE
                }
            }
        }

        item(key = "proxy") {
            SettingsGroup(str(R.string.proxy)) {
                SwitchRow(Icons.Default.VpnLock, str(R.string.enable_proxy),
                    if (settings.proxyEnabled && ContentProxy.configuredProxy(settings) == null) str(R.string.set_a_host_port_to_use_it) else null,
                    settings.proxyEnabled) { v ->
                    update { it.copy(proxyEnabled = v) }
                    if (v && settings.proxyUrl.isBlank()) dialog = ContentDialog.PROXY
                }
                if (settings.proxyEnabled) {
                    ValueRow(Icons.Default.Tune, str(R.string.configure_proxy),
                        settings.proxyUrl.ifBlank { str(R.string.not_set) }.let { "${settings.proxyType} · $it" }) {
                        dialog = ContentDialog.PROXY
                    }
                }
            }
        }

        item(key = "lyrics") {
            SettingsGroup(str(R.string.lyrics)) {
                val enabledCount = LyricsProviderId.entries.size - settings.disabledLyricsProviders.size
                ValueRow(Icons.Default.Lyrics, str(R.string.lyrics_providers),
                    str(R.string.x_of_x_enabled, enabledCount, LyricsProviderId.entries.size)) { dialog = ContentDialog.LYRICS_PROVIDERS }
                SwitchRow(Icons.Default.SortByAlpha, str(R.string.romanize_lyrics),
                    str(R.string.latin_spelling_as_a_small_line_under_the), settings.romanizationEnabled) { v ->
                    update { it.copy(romanizationEnabled = v) }
                }
                if (settings.romanizationEnabled) {
                    ValueRow(Icons.Default.Translate, str(R.string.romanized_scripts),
                        settings.romanizedScripts.size.let { if (it == RomanizationScript.entries.size) "All" else "$it selected" }) { dialog = ContentDialog.ROMANIZATION }
                }
            }
        }

        item(key = "wrapped") {
            SettingsGroup(str(R.string.wrapped)) {
                SwitchRow(Icons.Default.Leaderboard, str(R.string.weekly_and_monthly_most_playlists),
                    str(R.string.your_most_played_songs_of_the_last_7_and), settings.showMostStatsPlaylists) { v ->
                    update { it.copy(showMostStatsPlaylists = v) }
                }
                SwitchRow(Icons.Default.AutoAwesome, str(R.string.show_wrapped_card), str(R.string.on_the_home_screen), settings.showWrappedCard) { v ->
                    update { it.copy(showWrappedCard = v) }
                }
            }
        }

        item(key = "misc") {
            SettingsGroup(str(R.string.misc)) {
                SwitchRow(Icons.Default.Shuffle, str(R.string.randomize_home_order),
                    str(R.string.shuffle_the_home_shelves_keeping_higher), settings.randomizeHomeOrder) { v ->
                    update { it.copy(randomizeHomeOrder = v) }
                }
                ValueRow(Icons.Default.FormatListNumbered, str(R.string.my_top_list_length),
                    str(R.string.x_songs, settings.topLength)) { dialog = ContentDialog.TOP_LENGTH }
                ValueRow(Icons.Default.FlashOn, str(R.string.quick_picks),
                    com.auralis.music.ui.i18n.UiLabels.of(settings.quickPicksMode.displayName)) { dialog = ContentDialog.QUICK_PICKS }
            }
        }
    }

    when (dialog) {
        ContentDialog.CONTENT_LANGUAGE -> ChoiceDialog(
            title = str(R.string.content_language),
            note = str(R.string.youtube_shows_home_search_artist_pages_a) +
                str(R.string.some_languages_leave_out_play_counts_so),
            options = listOf(ContentSettings.SYSTEM to str(R.string.system_default)) + YouTubeLocales.languages.toList(),
            selected = settings.contentLanguage,
            onSelect = { code -> update { it.copy(contentLanguage = code) }; dialog = null },
            onDismiss = { dialog = null }
        )
        ContentDialog.CONTENT_COUNTRY -> ChoiceDialog(
            title = str(R.string.content_country),
            note = str(R.string.charts_recommendations_and_what_s_availa),
            options = listOf(ContentSettings.SYSTEM to str(R.string.system_default)) + YouTubeLocales.countries.toList(),
            selected = settings.contentCountry,
            onSelect = { code -> update { it.copy(contentCountry = code) }; dialog = null },
            onDismiss = { dialog = null }
        )
        ContentDialog.PLAYLIST_POSITION -> ChoiceDialog(
            title = str(R.string.add_to_playlist_position),
            note = null,
            options = AddToPlaylistPosition.entries.map { it.name to "${com.auralis.music.ui.i18n.UiLabels.of(it.displayName)} — ${com.auralis.music.ui.i18n.UiLabels.of(it.description)}" },
            selected = settings.addToPlaylistPosition.name,
            onSelect = { name -> update { it.copy(addToPlaylistPosition = AddToPlaylistPosition.valueOf(name)) }; dialog = null },
            onDismiss = { dialog = null }
        )
        ContentDialog.QUICK_PICKS -> ChoiceDialog(
            title = str(R.string.quick_picks),
            note = str(R.string.last_song_listened_builds_the_row_from_y),
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
            title = str(R.string.lyrics_providers),
            items = LyricsProviderId.entries.map { Triple(it.name, it.displayName, com.auralis.music.ui.i18n.UiLabels.of(it.description)) },
            checked = LyricsProviderId.entries.filterNot { it in settings.disabledLyricsProviders }.map { it.name }.toSet(),
            onSave = { enabled ->
                update { s -> s.copy(disabledLyricsProviders = LyricsProviderId.entries.filterNot { it.name in enabled }.toSet()) }
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        ContentDialog.ROMANIZATION -> CheckListDialog(
            title = str(R.string.romanized_scripts),
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
            val current = com.auralis.music.ui.i18n.AppLanguage.currentTag(context)
            ChoiceDialog(
                title = str(R.string.app_language),
                note = null,
                options = listOf("" to str(R.string.system_default)) +
                    com.auralis.music.ui.i18n.AppLanguage.available(context).map { tag ->
                        val locale = java.util.Locale.forLanguageTag(tag)
                        tag to locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
                    },
                selected = current,
                onSelect = { tag ->
                    dialog = null
                    if (tag != current) {
                        com.auralis.music.ui.i18n.AppLanguage.apply(context, tag)
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
            Toast.makeText(context, str(R.string.proxy_address_isn_t_host_port_connecting), Toast.LENGTH_SHORT).show()
        }
    }
}

private enum class ContentDialog {
    CONTENT_LANGUAGE, CONTENT_COUNTRY, PLAYLIST_POSITION, QUICK_PICKS, TOP_LENGTH, PROXY,
    LYRICS_PROVIDERS, ROMANIZATION, APP_LANGUAGE
}

private fun languageName(code: String): String =
    if (code == ContentSettings.SYSTEM) str(R.string.system_default) else YouTubeLocales.languages[code] ?: code

private fun countryName(code: String): String =
    if (code == ContentSettings.SYSTEM) str(R.string.system_default) else YouTubeLocales.countries[code] ?: code

private fun currentAppLanguageLabel(context: android.content.Context): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val locales = context.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales
        if (locales != null && !locales.isEmpty) return locales[0].let { it.getDisplayName(it) }
        return str(R.string.system_default)
    }
    val tag = com.auralis.music.ui.i18n.AppLanguage.storedTag(context)
    if (tag.isBlank()) return str(R.string.system_default)
    return java.util.Locale.forLanguageTag(tag).let { it.getDisplayName(it) }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) = SettingsSwitchRow(icon, title, subtitle, checked, onCheckedChange)

@Composable
private fun ValueRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) =
    SettingsNavRow(icon, title, value, onClick)

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
        confirmButton = { TextButton(onClick = onDismiss) { Text(str(R.string.close)) } }
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
        confirmButton = { TextButton(onClick = { onSave(current) }) { Text(str(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } }
    )
}

@Composable
private fun TopLengthDialog(current: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val parsed = text.toIntOrNull()?.takeIf { it in 1..500 }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.dynamicSurface,
        title = { Text(str(R.string.my_top_list_length), fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(3) },
                label = { Text(str(R.string.songs_1_500)) },
                singleLine = true,
                isError = parsed == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onSave) }, enabled = parsed != null) { Text(str(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } }
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
        title = { Text(str(R.string.configure_proxy), fontWeight = FontWeight.Bold) },
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
                    label = { Text(str(R.string.host_port)) },
                    singleLine = true,
                    isError = url.isNotBlank() && ContentProxy.parseHostPort(url) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(str(R.string.authentication), modifier = Modifier.weight(1f))
                    Switch(checked = auth, onCheckedChange = {
                        auth = it
                        if (!it) { user = ""; pass = "" }
                    })
                }
                if (auth) {
                    OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text(str(R.string.username)) }, singleLine = true)
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text(str(R.string.password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(type, url, if (auth) user else "", if (auth) pass else "") }, enabled = valid) {
                Text(str(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } }
    )
}
