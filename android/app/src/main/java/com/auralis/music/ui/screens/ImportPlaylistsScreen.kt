package com.auralis.music.ui.screens

import com.auralis.music.ui.components.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import android.content.ClipboardManager
import android.content.Context
import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.data.network.SpotifyLibrary
import com.auralis.music.data.network.SpotifySession
import com.auralis.music.data.network.YouTubeMusicLibrary
import com.auralis.music.data.network.YouTubeSession
import com.auralis.music.ui.profile.SpotifyLogoIcon
import com.auralis.music.ui.theme.dynamicBackground
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope

/**
 * Dedicated Import Playlists screen.
 * Gives users clean choices:
 * 1. Pick from their signed-in YouTube Music account via a scrollable popup.
 * 2. Import a YouTube Music playlist by link.
 * 3. Pick from their signed-in Spotify account; picking an imported list adds only what's new.
 * 4. Import a Spotify playlist by link.
 */
@Composable
fun ImportPlaylistsScreen(
    onDismiss: () -> Unit,
    onNavigateToYouTubeAccount: () -> Unit,
    onImportYouTubeLibraryPlaylists: (List<YouTubeMusicLibrary.LibraryPlaylist>) -> Unit,
    isImportingYouTube: Boolean,
    youtubeImportMessage: String?,
    onClearYouTubeImportMessage: () -> Unit,
    onImportYouTubePlaylist: (String) -> Unit,
    onImportSpotifyPlaylist: (String) -> Unit,
    onImportSpotifyLibraryPlaylists: (List<SpotifyLibrary.LibraryPlaylist>) -> Unit = {},
    isImportingSpotify: Boolean,
    spotifyImportMessage: String?,
    onClearSpotifyImportMessage: () -> Unit,
    importedPlaylistIds: Set<String> = emptySet(),
    modifier: Modifier = Modifier
) {
    val youTubeSignedIn by YouTubeSession.signedIn.collectAsState()
    val accountLabel by YouTubeSession.accountLabel.collectAsState()
    val spotifySignedIn by SpotifySession.signedIn.collectAsState()
    val spotifyAccountLabel by SpotifySession.accountLabel.collectAsState()

    var showPlaylistPickerPopup by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf<List<YouTubeMusicLibrary.LibraryPlaylist>?>(null) }
    var isLoadingPlaylists by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    val selectedPlaylists = remember { mutableStateListOf<String>() }
    var showSpotifySignIn by remember { mutableStateOf(false) }
    var spotifyPlaylists by remember { mutableStateOf<List<SpotifyLibrary.LibraryPlaylist>?>(null) }
    var isLoadingSpotifyPlaylists by remember { mutableStateOf(false) }
    var spotifyPlaylistLoadFailed by remember { mutableStateOf(false) }
    var spotifyReloadKey by remember { mutableStateOf(0) }
    var spotifyImportFromAccount by remember { mutableStateOf(false) }
    var showSpotifyPicker by remember { mutableStateOf(false) }
    val selectedSpotifyPlaylists = remember { mutableStateListOf<String>() }

    LaunchedEffect(youTubeSignedIn, reloadKey) {
        if (youTubeSignedIn) {
            isLoadingPlaylists = true
            loadFailed = false
            val result = YouTubeMusicLibrary.fetchPlaylists()
            playlists = result
            loadFailed = result == null
            isLoadingPlaylists = false
        } else {
            playlists = null
            selectedPlaylists.clear()
        }
    }

    LaunchedEffect(spotifySignedIn, spotifyReloadKey) {
        if (spotifySignedIn) {
            isLoadingSpotifyPlaylists = true
            val result = SpotifyLibrary.fetchPlaylists()
            spotifyPlaylists = result
            spotifyPlaylistLoadFailed = result == null
            isLoadingSpotifyPlaylists = false
        } else {
            spotifyPlaylists = null
            spotifyPlaylistLoadFailed = false
            selectedSpotifyPlaylists.clear()
        }
    }

    if (showSpotifySignIn) {
        SpotifySignInScreen(
            onSignedIn = {
                showSpotifySignIn = false
                showSpotifyPicker = true
                spotifyImportFromAccount = true
                spotifyReloadKey++
            },
            onDismiss = { showSpotifySignIn = false }
        )
        return
    }

    AccountPageTheme {
        AccountPage(title = str(R.string.import_playlists), onBack = onDismiss, modifier = modifier) {
            Text(str(R.string.transfer_music_from_youtube_music_via_ac), fontSize = 14.sp, lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                com.auralis.music.ui.library.PlaylistImportSyncToggle()
                val linkedCount = listOf(youTubeSignedIn, spotifySignedIn).count { it }
                AccountSectionLabel(str(R.string.account_ui_connected_accounts),
                    str(R.string.account_ui_linked_count, linkedCount))
                YouTubeAccountCard(
                    signedIn = youTubeSignedIn, accountLabel = accountLabel,
                    isLoadingPlaylists = isLoadingPlaylists, playlists = playlists,
                    isImporting = isImportingYouTube,
                    importMessage = youtubeImportMessage,
                    onOpenPicker = { onClearYouTubeImportMessage(); showPlaylistPickerPopup = true },
                    onNavigateToAccount = onNavigateToYouTubeAccount,
                    onDisconnect = { signOutOfYouTube() }
                )
                SpotifyAccountCard(
                    signedIn = spotifySignedIn, accountLabel = spotifyAccountLabel,
                    isLoadingPlaylists = isLoadingSpotifyPlaylists,
                    isImporting = isImportingSpotify,
                    importMessage = if (spotifyPlaylistLoadFailed) str(R.string.spotify_playlists_load_failed)
                        else if (spotifyImportFromAccount) spotifyImportMessage else null,
                    onOpenPicker = {
                        onClearSpotifyImportMessage()
                        spotifyImportFromAccount = true
                        showSpotifyPicker = true
                        spotifyReloadKey++ // Picks up playlists made in Spotify since the last look.
                    },
                    onSignIn = { showSpotifySignIn = true },
                    onDisconnect = { SpotifySession.signOut() }
                )
            }

            UniversalSmartLinkCard(
                isImportingYouTube = isImportingYouTube,
                youtubeImportMessage = if (!showPlaylistPickerPopup) youtubeImportMessage else null,
                onImportYouTube = onImportYouTubePlaylist,
                onClearYouTubeMessage = onClearYouTubeImportMessage,
                isImportingSpotify = isImportingSpotify,
                spotifyImportMessage = if (!spotifyImportFromAccount) spotifyImportMessage else null,
                onImportSpotify = { spotifyImportFromAccount = false; onImportSpotifyPlaylist(it) },
                onClearSpotifyMessage = onClearSpotifyImportMessage
            )
        }

        if (showPlaylistPickerPopup) {
            YouTubePlaylistPickerBottomSheet(
                playlists = playlists, isLoading = isLoadingPlaylists, loadFailed = loadFailed,
                onRetry = { reloadKey++ }, selected = selectedPlaylists,
                isImporting = isImportingYouTube, importMessage = youtubeImportMessage,
                onImport = onImportYouTubeLibraryPlaylists,
                onDismiss = { showPlaylistPickerPopup = false },
                importedPlaylistIds = importedPlaylistIds
            )
        }

        if (showSpotifyPicker) {
            SpotifyPlaylistPickerBottomSheet(
                playlists = spotifyPlaylists, isLoading = isLoadingSpotifyPlaylists,
                loadFailed = spotifyPlaylistLoadFailed, onRetry = { spotifyReloadKey++ },
                selected = selectedSpotifyPlaylists, importedPlaylistIds = importedPlaylistIds,
                isImporting = isImportingSpotify, importMessage = spotifyImportMessage,
                onImport = onImportSpotifyLibraryPlaylists,
                onSignInAgain = { showSpotifyPicker = false; showSpotifySignIn = true },
                onDismiss = { showSpotifyPicker = false }
            )
        }
    }
}

@Composable
private fun YouTubeAccountCard(
    signedIn: Boolean,
    accountLabel: String,
    isLoadingPlaylists: Boolean,
    playlists: List<YouTubeMusicLibrary.LibraryPlaylist>?,
    isImporting: Boolean,
    importMessage: String?,
    onOpenPicker: () -> Unit,
    onNavigateToAccount: () -> Unit,
    onDisconnect: () -> Unit
) {
    AccountPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccountGlyph(Icons.Default.PlayArrow)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(str(R.string.youtube_music), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(if (signedIn) accountLabel.ifBlank { str(R.string.signed_in) }
                    else str(R.string.sign_in_to_pick_playlists_directly),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onNavigateToAccount) {
                Icon(Icons.Default.MoreVert, str(R.string.open_youtube_account),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (signedIn && playlists != null && !isLoadingPlaylists) {
            AccountBadge(str(R.string.choose_playlists_x_available, playlists.size))
            Spacer(Modifier.height(12.dp))
        }
        Text(str(if (signedIn) R.string.import_liked_music_and_playlists_directl
            else R.string.sign_in_to_your_youtube_account_to_pick), fontSize = 12.sp,
            lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AccountAction(
                label = when {
                    !signedIn -> str(R.string.sign_in_to_youtube)
                    isLoadingPlaylists -> str(R.string.loading_playlists)
                    isImporting -> str(R.string.importing)
                    else -> str(R.string.choose_playlists_to_import)
                },
                icon = Icons.Default.Sync,
                onClick = if (signedIn) onOpenPicker else onNavigateToAccount,
                loading = isLoadingPlaylists || isImporting,
                modifier = Modifier.weight(1f)
            )
            if (signedIn) OutlinedIconButton(onClick = onDisconnect,
                enabled = !isImporting && !isLoadingPlaylists,
                shape = AccountButtonShape, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.LinkOff, str(R.string.sign_out_of_youtube), modifier = Modifier.size(20.dp))
            }
        }
        if (importMessage != null) {
            Spacer(Modifier.height(10.dp))
            Text(importMessage, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val SPOTIFY_GREEN = Color(0xFF1DB954)

@Composable
private fun SpotifyAccountCard(
    signedIn: Boolean,
    accountLabel: String,
    isLoadingPlaylists: Boolean,
    isImporting: Boolean,
    importMessage: String?,
    onOpenPicker: () -> Unit,
    onSignIn: () -> Unit,
    onDisconnect: () -> Unit
) {
    AccountPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccountGlyph(Icons.Default.GraphicEq)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(str(R.string.spotify_account), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(if (signedIn) accountLabel.ifBlank { str(R.string.signed_in) }
                    else str(R.string.account_ui_spotify_description),
                    fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(16.dp))
        AccountAction(
            label = when {
                isLoadingPlaylists -> str(R.string.loading_playlists)
                isImporting -> str(R.string.importing_songs)
                signedIn -> str(R.string.choose_playlists_to_import)
                else -> str(R.string.account_ui_connect_spotify)
            },
            icon = if (signedIn) Icons.Default.Sync else Icons.Default.AddLink,
            onClick = if (signedIn) onOpenPicker else onSignIn,
            loading = isLoadingPlaylists || isImporting, secondary = true
        )
        if (importMessage != null) {
            Spacer(Modifier.height(10.dp))
            Text(importMessage, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (signedIn) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDisconnect, enabled = !isImporting && !isLoadingPlaylists) {
                Text(str(R.string.disconnect_spotify), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun UniversalSmartLinkCard(
    isImportingYouTube: Boolean,
    youtubeImportMessage: String?,
    onImportYouTube: (String) -> Unit,
    onClearYouTubeMessage: () -> Unit,
    isImportingSpotify: Boolean,
    spotifyImportMessage: String?,
    onImportSpotify: (String) -> Unit,
    onClearSpotifyMessage: () -> Unit
) {
    val context = LocalContext.current
    var youtubeInput by rememberSaveable { mutableStateOf("") }
    var spotifyInput by rememberSaveable { mutableStateOf("") }
    var platform by rememberSaveable { mutableStateOf("youtube") }
    var validationMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val isImporting = isImportingYouTube || isImportingSpotify
    val input = if (platform == "youtube") youtubeInput else spotifyInput
    val setInput: (String) -> Unit = { text ->
        if (platform == "youtube") youtubeInput = text else spotifyInput = text
        validationMessage = null
        onClearYouTubeMessage()
        onClearSpotifyMessage()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(str(R.string.import_by_link), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(str(R.string.account_ui_link_description), fontSize = 12.sp, lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = AccountButtonShape, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .07f)) {
            Row(Modifier.fillMaxWidth().padding(5.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("youtube" to str(R.string.youtube_music), "spotify" to str(R.string.spotify)).forEach { (id, label) ->
                    val selected = id == platform
                    Surface(Modifier.weight(1f), shape = AccountButtonShape,
                        color = if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f) else Color.Transparent) {
                        Row(Modifier.selectable(selected = selected, enabled = !isImporting, role = Role.Tab,
                            onClick = { platform = id; validationMessage = null })
                            .heightIn(min = 48.dp).padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (id == "youtube") Icons.Default.PlayCircle else Icons.Default.GraphicEq,
                                null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(label, fontSize = 13.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                        }
                    }
                }
            }
        }
        OutlinedTextField(value = input, onValueChange = setInput, enabled = !isImporting,
            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = AccountCardShape,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            label = { Text(str(if (platform == "youtube") R.string.youtube_music_link else R.string.spotify_link), fontSize = 12.sp) },
            placeholder = { Text(str(R.string.account_ui_paste_link), fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Link, null, modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                TextButton(enabled = !isImporting, onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                    if (text.isNotBlank()) {
                        val host = Uri.parse(text).host.orEmpty().lowercase()
                        when {
                            host == "spotify.com" || host.endsWith(".spotify.com") || host == "spotify.link" -> {
                                platform = "spotify"; spotifyInput = text
                            }
                            host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" -> {
                                platform = "youtube"; youtubeInput = text
                            }
                            else -> setInput(text)
                        }
                        validationMessage = null
                        onClearYouTubeMessage(); onClearSpotifyMessage()
                    }
                }) {
                    Icon(Icons.Default.ContentPaste, str(R.string.paste), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(str(R.string.paste), fontSize = 11.sp)
                }
            },
            isError = validationMessage != null,
            supportingText = validationMessage?.let { message -> { Text(message) } },
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f),
                focusedContainerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.09f),
                unfocusedBorderColor = Color.Transparent))

        AccountAction(
            label = str(if (isImporting) R.string.importing_songs else R.string.account_ui_fetch_import),
            icon = Icons.Default.CloudDownload, enabled = input.isNotBlank(), loading = isImporting,
            onClick = {
                val trimmed = input.trim()
                val uri = Uri.parse(trimmed)
                val host = uri.host.orEmpty().lowercase()
                val spotify = host == "spotify.com" || host.endsWith(".spotify.com") || host == "spotify.link"
                val youtube = host == "music.youtube.com" || host == "www.youtube.com" || host == "youtube.com" || host == "youtu.be"
                if (uri.scheme?.lowercase() !in listOf("https", "http") || (platform == "spotify" && !spotify) || (platform == "youtube" && !youtube)) {
                    validationMessage = str(R.string.account_ui_invalid_link)
                } else if (platform == "spotify") onImportSpotify(trimmed) else onImportYouTube(trimmed)
            })

        AccountPanel(padding = 14.dp) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Info, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(str(if (platform == "youtube") R.string.tip_make_sure_your_playlist_is_set_to_pu
                    else R.string.account_ui_spotify_link_tip), fontSize = 12.sp,
                    lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val message = if (platform == "youtube") youtubeImportMessage else spotifyImportMessage
        if (message != null) AccountPanel {
            Text(str(R.string.account_ui_transfer_status), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(message, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SpotifySignInScreen(onSignedIn: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadSequence by remember { mutableIntStateOf(0) }
    var pageError by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(loadSequence) {
        if (loadSequence > 0) {
            delay(15_000)
            if (loading && !checking) pageError = true
        }
    }

    fun checkSession() {
        if (checking) return
        val cookies = CookieManager.getInstance().getCookie("https://open.spotify.com").orEmpty()
        if (!SpotifySession.hasAuth(cookies, "")) return
        checking = true
        scope.launch {
            val token = SpotifySession.fetchWebPlayerToken(cookies)
            val profile = token?.let { SpotifySession.fetchUserProfile(it) }
            if (token != null) {
                CookieManager.getInstance().flush()
                SpotifySession.save(cookies, token, System.currentTimeMillis() + 3_000_000L,
                    profile?.displayName.orEmpty().ifBlank { "Spotify" },
                    profile?.id.orEmpty(), profile?.avatarUrl.orEmpty())
                onSignedIn()
            } else {
                android.util.Log.w("SpotifyLogin", "Could not obtain an authenticated Spotify token")
                error = true
                checking = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.dynamicBackground)
        .statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(R.string.back))
            }
            Text(str(R.string.sign_in_to_spotify), fontWeight = FontWeight.Bold, fontSize = 20.sp,
                modifier = Modifier.weight(1f))
            TextButton(onClick = {
                pageError = false
                error = false
                loading = true
                webView?.loadUrl(SPOTIFY_LOGIN_URL)
            }) { Text(str(R.string.try_again), color = SPOTIFY_GREEN) }
        }
        if (loading || checking) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = SPOTIFY_GREEN)
        Box(Modifier.fillMaxWidth().weight(1f)) {
          AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(context).apply {
                    webView = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(false)
                    settings.userAgentString = SpotifySession.USER_AGENT
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            if (newProgress == 100 && !checking) loading = false
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                            loading = true
                            pageError = false
                            loadSequence++
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (request.isForMainFrame && request.url.host == "open.spotify.com") {
                                val cookies = CookieManager.getInstance().getCookie("https://open.spotify.com").orEmpty()
                                if (SpotifySession.hasAuth(cookies, "")) {
                                    checkSession()
                                    return true
                                }
                            }
                            return request.url.scheme !in listOf("https", "http")
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            loading = false
                            when (Uri.parse(url).host) {
                                "accounts.spotify.com" -> repairSpotifyLoginLayout(view)
                                "open.spotify.com" -> checkSession()
                            }
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) {
                                loading = false
                                pageError = true
                                android.util.Log.w("SpotifyLogin", "Page load failed: ${error.errorCode} ${error.description}")
                            }
                        }

                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (request.isForMainFrame) {
                                loading = false
                                pageError = true
                                android.util.Log.w("SpotifyLogin", "Page returned HTTP ${response.statusCode}")
                            }
                        }
                    }
                    loadUrl(SPOTIFY_LOGIN_URL)
                }
            },
            onRelease = { view -> if (webView === view) webView = null; view.destroy() }
          )
          if (checking || error || pageError) {
              Column(
                  modifier = Modifier.fillMaxSize().background(MaterialTheme.dynamicBackground),
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.Center
              ) {
                  if (checking) CircularProgressIndicator(color = SPOTIFY_GREEN)
                  Text(
                      text = if (pageError) str(R.string.spotify_login_page_failed)
                          else if (error) str(R.string.spotify_sign_in_failed)
                          else str(R.string.connecting_to_spotify),
                      color = if (pageError || error) MaterialTheme.colorScheme.error
                          else MaterialTheme.colorScheme.onBackground,
                      modifier = Modifier.padding(16.dp)
                  )
                  if (pageError || error) TextButton(onClick = {
                      pageError = false
                      error = false
                      loading = true
                      webView?.loadUrl(SPOTIFY_LOGIN_URL)
                  }) { Text(str(R.string.try_again), color = SPOTIFY_GREEN) }
              }
          }
        }
    }
}

private const val SPOTIFY_LOGIN_URL =
    "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"

/** Spotify's login shell can clip its form to a zero-height main element in WebView. */
private fun repairSpotifyLoginLayout(view: WebView) {
    view.evaluateJavascript(
        """(function() {
          if (document.getElementById('auralis-spotify-layout')) return;
          var style = document.createElement('style');
          style.id = 'auralis-spotify-layout';
          style.textContent =
            'html, body { height: auto !important; min-height: 100% !important; overflow: visible !important; }' +
            'body > div { height: auto !important; min-height: 100% !important; }' +
            'main { position: static !important; height: auto !important; min-height: 100dvh !important; max-height: none !important; overflow: visible !important; }';
          document.head.appendChild(style);
        })();""".trimIndent(), null
    )
}

/** Scrollable bottom sheet containing playlists from the signed-in Spotify account. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpotifyPlaylistPickerBottomSheet(
    playlists: List<SpotifyLibrary.LibraryPlaylist>?,
    isLoading: Boolean,
    loadFailed: Boolean,
    onRetry: () -> Unit,
    selected: SnapshotStateList<String>,
    importedPlaylistIds: Set<String>,
    isImporting: Boolean,
    importMessage: String?,
    onImport: (List<SpotifyLibrary.LibraryPlaylist>) -> Unit,
    onSignInAgain: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(str(R.string.your_spotify_playlists), fontWeight = FontWeight.Bold,
                    fontSize = 17.sp, modifier = Modifier.weight(1f))
                val all = playlists.orEmpty()
                if (all.isNotEmpty()) {
                    TextButton(onClick = {
                        val allSelected = all.all { it.id in selected }
                        selected.clear()
                        if (!allSelected) selected.addAll(all.map { it.id })
                    }) {
                        Text(if (all.all { it.id in selected }) str(R.string.clear)
                            else str(R.string.select_all), color = SPOTIFY_GREEN)
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = str(R.string.close))
                }
            }
            Text(str(R.string.spotify_picker_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 12.dp))
            when {
                isLoading -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = SPOTIFY_GREEN)
                }
                loadFailed || playlists == null -> Column(Modifier.fillMaxWidth().height(180.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text(str(R.string.spotify_playlists_load_failed))
                    TextButton(onClick = onRetry) { Text(str(R.string.try_again), color = SPOTIFY_GREEN) }
                    TextButton(onClick = onSignInAgain) { Text(str(R.string.sign_in_again), color = SPOTIFY_GREEN) }
                }
                playlists.isEmpty() -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    Text(str(R.string.spotify_playlists_empty))
                }
                else -> LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(playlists, key = { it.id }) { item ->
                        val checked = item.id in selected
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(AccountCardShape)
                                .background(if (checked) SPOTIFY_GREEN.copy(alpha = 0.08f) else Color.Transparent)
                                .clickable(enabled = !isImporting) {
                                    if (checked) selected.remove(item.id) else selected.add(item.id)
                                }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                if (item.isLikedSongs) Icon(Icons.Default.Favorite, null, tint = SPOTIFY_GREEN)
                                else if (item.thumbnail != null) coil.compose.AsyncImage(
                                    model = item.thumbnail, contentDescription = null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize())
                                else SpotifyLogoIcon(modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                val imported = "imported:spotify:${item.id}" in importedPlaylistIds
                                Text(if (imported) "Already imported • adds only new songs" else item.subtitle, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Checkbox(checked = checked, enabled = !isImporting,
                                onCheckedChange = { if (it) selected.add(item.id) else selected.remove(item.id) },
                                colors = CheckboxDefaults.colors(checkedColor = SPOTIFY_GREEN))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onImport(playlists.orEmpty().filter { it.id in selected }) },
                enabled = selected.isNotEmpty() && !isImporting,
                colors = ButtonDefaults.buttonColors(containerColor = SPOTIFY_GREEN,
                    disabledContainerColor = SPOTIFY_GREEN.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth().height(46.dp)
            ) {
                if (isImporting) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else Text(if (selected.isEmpty()) str(R.string.select_playlists_to_import)
                    else "Import ${selected.size} ${if (selected.size == 1) "playlist" else "playlists"}")
            }
            if (importMessage != null) Text(importMessage,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Scrollable bottom sheet modal popup containing all the signed-in YouTube Music playlists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubePlaylistPickerBottomSheet(
    playlists: List<YouTubeMusicLibrary.LibraryPlaylist>?,
    isLoading: Boolean,
    loadFailed: Boolean,
    onRetry: () -> Unit,
    selected: SnapshotStateList<String>,
    isImporting: Boolean,
    importMessage: String?,
    onImport: (List<YouTubeMusicLibrary.LibraryPlaylist>) -> Unit,
    onDismiss: () -> Unit,
    importedPlaylistIds: Set<String> = emptySet()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
                Spacer(Modifier.height(10.dp))
            }
        },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            // Header: Title + Select all / Clear + Close button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
            ) {
                Text(
                    text = str(R.string.your_youtube_music_playlists),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 17.sp,
                    modifier = Modifier.weight(1f)
                )

                val all = playlists.orEmpty()
                if (all.isNotEmpty()) {
                    val allSelected = selected.size == all.size
                    TextButton(
                        onClick = {
                            selected.clear()
                            if (!allSelected) selected.addAll(all.map { it.id })
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (allSelected) str(R.string.clear) else str(R.string.select_all),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = str(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Text(
                text = str(R.string.private_playlists_included_liked_music_i),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Body: Loading / Error / Empty / Scrollable list
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    }
                }
                loadFailed || playlists == null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = str(R.string.couldn_t_load_your_playlists_from_youtub),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onRetry) {
                            Text(str(R.string.try_again), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                playlists.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = str(R.string.no_playlists_found_in_your_youtube_music),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                }
                else -> {
                    // Scrollable list bounded by weight so it never exceeds screen bounds
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(playlists, key = { it.id }) { item ->
                            val checked = item.id in selected
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(AccountCardShape)
                                    .background(if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
                                    .clickable(enabled = !isImporting) {
                                        if (checked) selected.remove(item.id) else selected.add(item.id)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (item.isLikedMusic) {
                                        Icon(
                                            imageVector = Icons.Default.Favorite,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    } else if (item.thumbnail != null) {
                                        coil.compose.AsyncImage(
                                            model = item.thumbnail,
                                            contentDescription = null,
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }

                                Spacer(Modifier.width(12.dp))

                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val imported = "imported:youtube_music:${item.id}" in importedPlaylistIds
                                    if (imported || item.subtitle.isNotBlank()) {
                                        Text(
                                            text = if (imported) "Already imported • re-import updates it" else item.subtitle,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Checkbox(
                                    checked = checked,
                                    enabled = !isImporting,
                                    onCheckedChange = { on ->
                                        if (on) selected.add(item.id) else selected.remove(item.id)
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary, checkmarkColor = MaterialTheme.colorScheme.onPrimary)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Action button
            Button(
                onClick = {
                    val toImport = playlists?.filter { it.id in selected }.orEmpty()
                    if (toImport.isNotEmpty()) {
                        onImport(toImport)
                    }
                },
                enabled = selected.isNotEmpty() && !isImporting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                shape = AccountButtonShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = str(R.string.importing),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                } else {
                    Text(
                        text = when {
                            selected.isEmpty() -> str(R.string.select_playlists_to_import)
                            else -> "Import ${selected.size} ${if (selected.size == 1) "playlist" else "playlists"}"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }

            if (importMessage != null) {
                Text(
                    text = importMessage,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
