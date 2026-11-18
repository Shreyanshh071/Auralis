package com.auralis.music.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.auralis.music.R
import com.auralis.music.data.importer.*
import com.auralis.music.ui.components.*
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.theme.dynamicBackground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

@Composable
internal fun MusicAccountCards(
    connected: Set<MusicAccountService>,
    busy: Boolean,
    onOpen: (MusicAccountService) -> Unit,
    onDisconnect: (MusicAccountService) -> Unit
) {
    MusicAccountService.entries.forEach { service ->
        AccountPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccountGlyph(Icons.Default.LibraryMusic)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(service.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(str(if (service in connected) R.string.music_account_connected else R.string.music_account_description),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccountAction(
                    label = if (service in connected) str(R.string.choose_playlists_to_import) else str(R.string.music_account_connect, service.label),
                    icon = if (service in connected) Icons.Default.Sync else Icons.Default.AddLink,
                    onClick = { if (!busy) onOpen(service) }, loading = busy, modifier = Modifier.weight(1f))
                if (service in connected) OutlinedIconButton(onClick = { onDisconnect(service) }, enabled = !busy,
                    modifier = Modifier.size(48.dp), shape = AccountButtonShape) {
                    Icon(Icons.Default.LinkOff, str(R.string.music_account_disconnect, service.label))
                }
            }
        }
    }
}

/** Browser sign-in and native multiple selection share a live WebView, including private access. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun MusicAccountImportScreen(
    service: MusicAccountService,
    importedPlaylistIds: Set<String>,
    isSaving: Boolean,
    saveMessage: String?,
    onConnected: () -> Unit,
    onImport: (List<ExternalPlaylist>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var web by remember(service) { mutableStateOf<WebView?>(null) }
    var reader by remember(service) { mutableStateOf<MusicAccountBrowser?>(null) }
    var playlists by remember(service) { mutableStateOf<List<MusicAccountPlaylist>?>(null) }
    var selected by remember(service) { mutableStateOf(emptySet<String>()) }
    var query by remember(service) { mutableStateOf("") }
    var browsing by remember(service) { mutableStateOf(true) }
    var busy by remember(service) { mutableStateOf(false) }
    var progress by remember(service) { mutableStateOf<String?>(null) }
    var failure by remember(service) { mutableStateOf<String?>(null) }
    var pageLoading by remember(service) { mutableStateOf(false) }
    var currentHost by remember(service) { mutableStateOf("") }
    var task by remember(service) { mutableStateOf<Job?>(null) }
    var amazonRegion by remember { mutableStateOf("com") }
    val locked = busy || isSaving

    fun libraryUrl() = if (service == MusicAccountService.AMAZON) "https://music.amazon.$amazonRegion/my/playlists" else service.libraryUrl
    fun loadPlaylists() {
        if (locked) return
        task = scope.launch {
            busy = true
            failure = null
            progress = str(R.string.loading_playlists)
            try {
                val result = (reader ?: throw MusicAccountException(str(R.string.music_account_wait_for_page)))
                    .playlists { count -> progress = str(R.string.music_account_found_playlists, count) }
                playlists = result
                selected = selected.intersect(result.map { it.id }.toSet())
                CookieManager.getInstance().flush()
                onConnected()
                browsing = false
            } catch (_: TimeoutCancellationException) {
                failure = str(R.string.music_account_timeout)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                failure = if (e is MusicAccountException) e.message else str(R.string.music_account_read_failed)
            } finally { busy = false; progress = null }
        }
    }

    fun importSelected() {
        if (locked || selected.isEmpty()) return
        val pending = playlists.orEmpty().filter { it.id in selected }
        task = scope.launch {
            busy = true
            failure = null
            val completed = mutableListOf<ExternalPlaylist>()
            val succeeded = mutableSetOf<String>()
            val failed = mutableListOf<String>()
            try {
                pending.forEachIndexed { index, item ->
                    progress = str(R.string.importing_x_of_x_x, index + 1, pending.size, item.title)
                    try {
                        val imported = (reader ?: throw MusicAccountException(str(R.string.music_account_wait_for_page)))
                            .readPlaylist(item) { count ->
                                // Native client progress can arrive from its IO dispatcher.
                                scope.launch { if (busy) progress = str(R.string.music_account_reading_tracks, index + 1, pending.size, item.title, count) }
                            }
                        completed += imported
                        succeeded += item.id
                    } catch (_: TimeoutCancellationException) {
                        failed += "${item.title}: ${str(R.string.music_account_timeout)}"
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) {
                        failed += "${item.title}: ${if (e is MusicAccountException) e.message else str(R.string.music_account_read_failed)}"
                    }
                }
                // A failed playlist is never replaced by partial data. Keep it selected for retry.
                if (completed.isNotEmpty()) {
                    onImport(completed)
                    selected = selected - succeeded
                }
                failure = failed.takeIf { it.isNotEmpty() }?.joinToString("\n")
                browsing = false
            } finally { busy = false; progress = null }
        }
    }

    BackHandler {
        if (busy) task?.cancel()
        else if (browsing && playlists != null) browsing = false
        else onDismiss()
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.dynamicBackground).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (busy) task?.cancel() else onDismiss() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, str(R.string.back))
            }
            Text(service.label, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { browsing = !browsing }, enabled = !locked && playlists != null) {
                Text(str(if (browsing) R.string.choose_playlists_to_import else R.string.music_account_browser))
            }
        }
        if (browsing) {
            Text(str(R.string.music_account_login_help), modifier = Modifier.padding(horizontal = 16.dp),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { web?.loadUrl(libraryUrl(), mapOf("Accept-Language" to "en-US,en;q=0.9")) }, enabled = !locked) {
                    Text(str(R.string.music_account_library))
                }
                Text(currentHost, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
                Button(onClick = ::loadPlaylists, enabled = web != null && !locked) { Text(str(R.string.music_account_load)) }
            }
            if (service == MusicAccountService.AMAZON) {
                var regionMenu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { regionMenu = true }, enabled = !locked) { Text(str(R.string.music_account_region, amazonRegion)) }
                    DropdownMenu(expanded = regionMenu, onDismissRequest = { regionMenu = false }) {
                        listOf("com", "in", "co.uk", "de", "fr", "it", "es", "co.jp", "ca", "com.au", "com.mx", "com.br").forEach { region ->
                            DropdownMenuItem(text = { Text("amazon.$region") }, onClick = {
                                amazonRegion = region; regionMenu = false
                                web?.loadUrl(libraryUrl(), mapOf("Accept-Language" to "en-US,en;q=0.9"))
                            })
                        }
                    }
                }
            }
        }
        if (pageLoading && browsing || busy || isSaving) LinearProgressIndicator(Modifier.fillMaxWidth())
        (progress ?: saveMessage)?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 12.sp) }
        failure?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            // Keep the same browser mounted while the native selection list is shown.
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                WebView(ctx).apply {
                    web = this
                    reader = MusicAccountBrowser(context.applicationContext, service, this)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) { pageLoading = newProgress < 100 }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.scheme != "https"
                        override fun onPageFinished(view: WebView, url: String) {
                            currentHost = Uri.parse(url).host.orEmpty()
                            CookieManager.getInstance().flush()
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                            if (request.isForMainFrame) { pageLoading = false; failure = str(R.string.music_account_page_failed) }
                        }
                    }
                    loadUrl(libraryUrl(), mapOf("Accept-Language" to "en-US,en;q=0.9"))
                }
            }, onRelease = { view ->
                task?.cancel()
                reader?.dispose()
                reader = null; web = null
                view.stopLoading(); view.destroy()
            })
            if (!browsing) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.dynamicBackground).padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(str(R.string.choose_playlists_x_available, playlists.orEmpty().size), modifier = Modifier.weight(1f), fontSize = 13.sp)
                        TextButton(onClick = ::loadPlaylists, enabled = !locked) { Text(str(R.string.music_account_refresh)) }
                    }
                    OutlinedTextField(query, { query = it }, singleLine = true,
                        label = { Text(str(R.string.search)) }, modifier = Modifier.fillMaxWidth(), enabled = !locked)
                    val shown = playlists.orEmpty().filter { it.title.contains(query.trim(), ignoreCase = true) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            val ids = shown.map { it.id }.toSet()
                            selected = if (ids.all { it in selected }) selected - ids else selected + ids
                        }, enabled = !locked && shown.isNotEmpty()) {
                            Text(str(if (shown.isNotEmpty() && shown.all { it.id in selected }) R.string.music_account_deselect_all else R.string.select_all))
                        }
                    }
                    if (shown.isEmpty()) Text(str(R.string.music_account_empty), modifier = Modifier.padding(16.dp), fontSize = 13.sp)
                    LazyColumn(Modifier.weight(1f)) {
                        items(shown, key = { it.id }) { item ->
                            val checked = item.id in selected
                            Row(Modifier.fillMaxWidth().toggleable(checked, enabled = !locked, role = Role.Checkbox) {
                                selected = if (it) selected + item.id else selected - item.id
                            }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                ArtworkCard(item.coverUrl, Modifier.size(48.dp), cornerRadius = 10.dp, contentDescription = null)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                    val imported = "imported:${service.source}:${item.remoteId}" in importedPlaylistIds
                                    Text(if (imported) str(R.string.music_account_update_existing)
                                        else if (item.trackCount >= 0) str(R.string.x_songs, item.trackCount) else service.label,
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (item.isPrivate == true) Icon(Icons.Default.Lock, str(R.string.music_account_private), Modifier.size(16.dp))
                                Checkbox(checked, onCheckedChange = null, enabled = !locked)
                            }
                        }
                    }
                    Button(onClick = ::importSelected, enabled = selected.isNotEmpty() && !locked,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Text(str(R.string.music_account_import_selected, selected.size))
                    }
                }
            }
            if (busy) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = .92f))
                .clickable { }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(progress.orEmpty(), modifier = Modifier.padding(16.dp), maxLines = 3)
                    TextButton(onClick = { task?.cancel() }) { Text(str(R.string.cancel)) }
                }
            }
        }
    }
}
