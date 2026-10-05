package com.auralis.music.ui.screens

import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ContentSettingsDataStore(context) }
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(R.string.back), tint = onBackground)
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = str(R.string.content),
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
                    Section(str(R.string.general), primary, surface) {
                        ValueRow(Icons.Default.Language, str(R.string.content_language),
                            languageName(settings.contentLanguage), colors) { dialog = ContentDialog.CONTENT_LANGUAGE }
                        Divider()
                        ValueRow(Icons.Default.Public, str(R.string.content_country),
                            countryName(settings.contentCountry), colors) { dialog = ContentDialog.CONTENT_COUNTRY }
                    }
                }

                item(key = "playlists") {
                    Section(str(R.string.playlists), primary, surface) {
                        ValueRow(Icons.AutoMirrored.Filled.PlaylistAdd, str(R.string.add_to_playlist_position),
                            com.auralis.music.ui.i18n.UiLabels.of(settings.addToPlaylistPosition.displayName), colors) { dialog = ContentDialog.PLAYLIST_POSITION }
                    }
                }

                item(key = "artist") {
                    Section(str(R.string.artist_page), primary, surface) {
                        SwitchRow(Icons.Default.Description, str(R.string.show_artist_description), null, settings.showArtistDescription, colors) { v ->
                            update { it.copy(showArtistDescription = v) }
                        }
                        Divider()
                        SwitchRow(Icons.Default.People, str(R.string.show_subscriber_count), null, settings.showArtistSubscriberCount, colors) { v ->
                            update { it.copy(showArtistSubscriberCount = v) }
                        }
                        Divider()
                        SwitchRow(Icons.Default.Groups, str(R.string.show_monthly_listeners), null, settings.showMonthlyListeners, colors) { v ->
                            update { it.copy(showMonthlyListeners = v) }
                        }
                    }
                }

                item(key = "app_language") {
                    Section(str(R.string.app_language), primary, surface) {
                        ValueRow(Icons.Default.Translate, str(R.string.app_language), currentAppLanguageLabel(context), colors) {
                            dialog = ContentDialog.APP_LANGUAGE
                        }
                    }
                }

                item(key = "proxy") {
                    Section(str(R.string.proxy), primary, surface) {
                        SwitchRow(Icons.Default.VpnLock, str(R.string.enable_proxy),
                            if (settings.proxyEnabled && ContentProxy.configuredProxy(settings) == null) str(R.string.set_a_host_port_to_use_it) else null,
                            settings.proxyEnabled, colors) { v ->
                            update { it.copy(proxyEnabled = v) }
                            if (v && settings.proxyUrl.isBlank()) dialog = ContentDialog.PROXY
                        }
                        if (settings.proxyEnabled) {
                            Divider()
                            ValueRow(Icons.Default.Tune, str(R.string.configure_proxy),
                                settings.proxyUrl.ifBlank { str(R.string.not_set) }.let { "${settings.proxyType} · $it" }, colors) {
                                dialog = ContentDialog.PROXY
                            }
                        }
                    }
                }

                item(key = "lyrics") {
                    Section(str(R.string.lyrics), primary, surface) {
                        val enabledCount = LyricsProviderId.entries.size - settings.disabledLyricsProviders.size
                        ValueRow(Icons.Default.Lyrics, str(R.string.lyrics_providers),
                            str(R.string.x_of_x_enabled, enabledCount, LyricsProviderId.entries.size), colors) { dialog = ContentDialog.LYRICS_PROVIDERS }
                        Divider()
                        SwitchRow(Icons.Default.SortByAlpha, str(R.string.romanize_lyrics),
                            str(R.string.latin_spelling_as_a_small_line_under_the), settings.romanizationEnabled, colors) { v ->
                            update { it.copy(romanizationEnabled = v) }
                        }
                        if (settings.romanizationEnabled) {
                            Divider()
                            ValueRow(Icons.Default.Translate, str(R.string.romanized_scripts),
                                settings.romanizedScripts.size.let { if (it == RomanizationScript.entries.size) "All" else "$it selected" },
                                colors) { dialog = ContentDialog.ROMANIZATION }
                        }
                    }
                }

                item(key = "wrapped") {
                    Section(str(R.string.wrapped), primary, surface) {
                        SwitchRow(Icons.Default.Leaderboard, str(R.string.weekly_and_monthly_most_playlists),
                            str(R.string.your_most_played_songs_of_the_last_7_and), settings.showMostStatsPlaylists, colors) { v ->
                            update { it.copy(showMostStatsPlaylists = v) }
                        }
                        Divider()
                        SwitchRow(Icons.Default.AutoAwesome, str(R.string.show_wrapped_card), str(R.string.on_the_home_screen), settings.showWrappedCard, colors) { v ->
                            update { it.copy(showWrappedCard = v) }
                        }
                    }
                }

                item(key = "misc") {
                    Section(str(R.string.misc), primary, surface) {
                        SwitchRow(Icons.Default.Shuffle, str(R.string.randomize_home_order),
                            str(R.string.shuffle_the_home_shelves_keeping_higher), settings.randomizeHomeOrder, colors) { v ->
                            update { it.copy(randomizeHomeOrder = v) }
                        }
                        Divider()
                        ValueRow(Icons.Default.FormatListNumbered, str(R.string.my_top_list_length),
                            str(R.string.x_songs, settings.topLength), colors) { dialog = ContentDialog.TOP_LENGTH }
                        Divider()
                        ValueRow(Icons.Default.FlashOn, str(R.string.quick_picks),
                            com.auralis.music.ui.i18n.UiLabels.of(settings.quickPicksMode.displayName), colors) { dialog = ContentDialog.QUICK_PICKS }
                    }
                }
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

private data class RowColors(val primary: Color, val onSurface: Color, val onSurfaceVariant: Color)

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
