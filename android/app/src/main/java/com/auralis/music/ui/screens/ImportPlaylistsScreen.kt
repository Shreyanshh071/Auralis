package com.auralis.music.ui.screens

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.viewinterop.AndroidView
import com.auralis.music.data.network.SpotifyLibrary
import com.auralis.music.data.network.SpotifySession
import com.auralis.music.data.network.YouTubeMusicLibrary
import com.auralis.music.data.network.YouTubeSession
import com.auralis.music.ui.profile.SpotifyLogoIcon
import com.auralis.music.ui.theme.dynamicBackground
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

private val SPOTIFY_GREEN = Color(0xFF1DB954)

/**
 * Dedicated Import Playlists screen.
 * Gives users clean choices:
 * 1. Pick from their signed-in YouTube Music account via a scrollable popup.
 * 2. Pick from their signed-in Spotify account via a scrollable popup.
 * 3. Import a YouTube Music playlist by link.
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val youTubeSignedIn by YouTubeSession.signedIn.collectAsState()
    val accountLabel by YouTubeSession.accountLabel.collectAsState()

    val spotifySignedIn by SpotifySession.signedIn.collectAsState()
    val spotifyAccountLabel by SpotifySession.accountLabel.collectAsState()
    var isSigningInSpotify by remember { mutableStateOf(false) }

    var showPlaylistPickerPopup by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf<List<YouTubeMusicLibrary.LibraryPlaylist>?>(null) }
    var isLoadingPlaylists by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    val selectedPlaylists = remember { mutableStateListOf<String>() }

    var showSpotifyPlaylistPickerPopup by remember { mutableStateOf(false) }
    var spotifyPlaylists by remember { mutableStateOf<List<SpotifyLibrary.LibraryPlaylist>?>(null) }
    var isLoadingSpotifyPlaylists by remember { mutableStateOf(false) }
    var loadSpotifyFailed by remember { mutableStateOf(false) }
    var reloadSpotifyKey by remember { mutableStateOf(0) }
    val selectedSpotifyPlaylists = remember { mutableStateListOf<String>() }

    var youtubeUrlInput by remember { mutableStateOf("") }
    var spotifyUrlInput by remember { mutableStateOf("") }

    BackHandler(enabled = isSigningInSpotify) {
        isSigningInSpotify = false
    }

    // Prefetch YouTube playlists when signed in so the popup opens instantly
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

    // Prefetch Spotify playlists when signed in so the popup opens instantly
    LaunchedEffect(spotifySignedIn, reloadSpotifyKey) {
        if (spotifySignedIn) {
            isLoadingSpotifyPlaylists = true
            loadSpotifyFailed = false
            val result = SpotifyLibrary.fetchPlaylists()
            spotifyPlaylists = result
            loadSpotifyFailed = result == null
            isLoadingSpotifyPlaylists = false
        } else {
            spotifyPlaylists = null
            selectedSpotifyPlaylists.clear()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.dynamicBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        if (isSigningInSpotify) {
            SpotifySignInWebView(
                onSignedIn = {
                    isSigningInSpotify = false
                    reloadSpotifyKey++
                },
                onCancel = { isSigningInSpotify = false }
            )
        } else {
            Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // ── TOP APP BAR ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Import playlists",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 20.sp
                )
            }

            Text(
                text = "Transfer music from YouTube Music or Spotify directly into your Auralis library.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )

            Spacer(Modifier.height(14.dp))

            // ── 1. YOUTUBE MUSIC ACCOUNT (Scrollable Popup Picker) ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.2.dp, YOUTUBE_RED.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(YOUTUBE_RED.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (youTubeSignedIn) Icons.Default.CheckCircle else Icons.Default.PlayArrow,
                                contentDescription = "YouTube Music",
                                tint = YOUTUBE_RED,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "YouTube Music Account",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp
                            )
                            Text(
                                text = if (youTubeSignedIn) {
                                    if (accountLabel.isNotBlank()) accountLabel else "Signed in"
                                } else "Sign in to pick playlists directly",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    if (youTubeSignedIn) {
                        Text(
                            text = "Import Liked Music and playlists directly from your YouTube account—including private playlists—without pasting links or setting them to public.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )

                        Spacer(Modifier.height(12.dp))

                        Button(
                            onClick = { showPlaylistPickerPopup = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = YOUTUBE_RED,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.LibraryMusic,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = when {
                                        isLoadingPlaylists -> "Loading playlists..."
                                        playlists != null && playlists!!.isNotEmpty() -> "Choose playlists (${playlists!!.size} available)"
                                        else -> "Choose playlists to import"
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        if (isImportingYouTube) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    color = YOUTUBE_RED,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = youtubeImportMessage ?: "Importing...",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                        } else if (youtubeImportMessage != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = youtubeImportMessage,
                                color = if (youtubeImportMessage.startsWith("Imported", ignoreCase = true) || youtubeImportMessage.startsWith("Success", ignoreCase = true)) Color(0xFF16A34A) else Color(0xFFEF4444),
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        Text(
                            text = "Sign in to your YouTube account to pick and import any of your playlists (including private ones) and Liked Music directly into Auralis.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )

                        Spacer(Modifier.height(12.dp))

                        Button(
                            onClick = onNavigateToYouTubeAccount,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = YOUTUBE_RED,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Sign in to YouTube",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── 2. SPOTIFY ACCOUNT (Scrollable Popup Picker) ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.2.dp, SPOTIFY_GREEN.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(SPOTIFY_GREEN.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            SpotifyLogoIcon(modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Spotify Account",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp
                            )
                            Text(
                                text = if (spotifySignedIn) {
                                    if (spotifyAccountLabel.isNotBlank()) spotifyAccountLabel else "Signed in"
                                } else "Sign in to pick playlists directly",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (spotifySignedIn) {
                            TextButton(
                                onClick = {
                                    SpotifySession.signOut()
                                    reloadSpotifyKey++
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("Sign out", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    if (spotifySignedIn) {
                        Text(
                            text = "Import Liked Songs and playlists directly from your Spotify account—including private playlists—without pasting links or setting them to public.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )

                        Spacer(Modifier.height(12.dp))

                        Button(
                            onClick = { showSpotifyPlaylistPickerPopup = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SPOTIFY_GREEN,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SpotifyLogoIcon(modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = when {
                                        isLoadingSpotifyPlaylists -> "Loading playlists..."
                                        spotifyPlaylists != null && spotifyPlaylists!!.isNotEmpty() -> "Choose playlists (${spotifyPlaylists!!.size} available)"
                                        else -> "Choose playlists to import"
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        if (isImportingSpotify && !showSpotifyPlaylistPickerPopup) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    color = SPOTIFY_GREEN,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = spotifyImportMessage ?: "Importing...",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                        } else if (spotifyImportMessage != null && !showSpotifyPlaylistPickerPopup) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = spotifyImportMessage,
                                color = if (spotifyImportMessage.startsWith("Imported", ignoreCase = true) || spotifyImportMessage.startsWith("Success", ignoreCase = true)) Color(0xFF16A34A) else Color(0xFFEF4444),
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        Text(
                            text = "Sign in to your Spotify account to pick and import any of your playlists (including private ones) and Liked Songs directly into Auralis.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )

                        Spacer(Modifier.height(12.dp))

                        Button(
                            onClick = { isSigningInSpotify = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SPOTIFY_GREEN,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SpotifyLogoIcon(modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Sign in to Spotify",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── SECTION HEADER: IMPORT BY LINK ──
            Text(
                text = "Import by link",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )

            Spacer(Modifier.height(8.dp))

            // ── 2. YOUTUBE MUSIC PLAYLIST LINK IMPORTER CARD ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                    .padding(14.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.LibraryMusic,
                                contentDescription = null,
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "YouTube Music Link",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp
                            )
                        }

                        if (isImportingYouTube && !showPlaylistPickerPopup) {
                            CircularProgressIndicator(
                                color = Color(0xFFEF4444),
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Paste a YouTube Music playlist link to import songs directly into your library.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = youtubeUrlInput,
                        onValueChange = {
                            youtubeUrlInput = it
                            onClearYouTubeImportMessage()
                        },
                        label = { Text("YouTube Music Link", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) },
                        placeholder = { Text("Paste music.youtube.com/playlist?list=...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFEF4444),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            cursorColor = Color(0xFFEF4444)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (youtubeUrlInput.isNotEmpty()) {
                                    IconButton(onClick = {
                                        youtubeUrlInput = ""
                                        onClearYouTubeImportMessage()
                                    }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = clipboard.primaryClip
                                        if (clip != null && clip.itemCount > 0) {
                                            youtubeUrlInput = clip.getItemAt(0).text.toString().trim()
                                            onClearYouTubeImportMessage()
                                            Toast.makeText(context, "Pasted YouTube Music link", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            if (youtubeUrlInput.isNotBlank() && !isImportingYouTube) {
                                onImportYouTubePlaylist(youtubeUrlInput.trim())
                            }
                        },
                        enabled = youtubeUrlInput.isNotBlank() && !isImportingYouTube,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFEF4444),
                            disabledContainerColor = Color(0xFFEF4444).copy(alpha = 0.30f),
                            contentColor = Color.White,
                            disabledContentColor = Color.White.copy(alpha = 0.70f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                    ) {
                        if (isImportingYouTube && !showPlaylistPickerPopup) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Importing Songs...",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Import YT Music Playlist",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Tip: Make sure your playlist is set to Public or Unlisted in YouTube Music. Normal YouTube video links are blocked to keep your library pure music.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }

                    if (youtubeImportMessage != null && !showPlaylistPickerPopup) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = youtubeImportMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (youtubeImportMessage.startsWith("Imported", ignoreCase = true) || youtubeImportMessage.startsWith("Success", ignoreCase = true)) Color(0xFF16A34A) else Color(0xFFEF4444),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── 3. SPOTIFY PLAYLIST LINK IMPORTER CARD ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, Color(0xFF1DB954).copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                    .padding(14.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SpotifyLogoIcon(modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Spotify Link",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp
                            )
                        }

                        if (isImportingSpotify) {
                            CircularProgressIndicator(
                                color = Color(0xFF1DB954),
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Paste any Spotify playlist, album, or track link to import songs into Auralis.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = spotifyUrlInput,
                        onValueChange = {
                            spotifyUrlInput = it
                            onClearSpotifyImportMessage()
                        },
                        label = { Text("Spotify Link", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) },
                        placeholder = { Text("Paste open.spotify.com/playlist/...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF1DB954),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            cursorColor = Color(0xFF1DB954)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            SpotifyLogoIcon(modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (spotifyUrlInput.isNotEmpty()) {
                                    IconButton(onClick = {
                                        spotifyUrlInput = ""
                                        onClearSpotifyImportMessage()
                                    }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = clipboard.primaryClip
                                        if (clip != null && clip.itemCount > 0) {
                                            spotifyUrlInput = clip.getItemAt(0).text.toString().trim()
                                            onClearSpotifyImportMessage()
                                            Toast.makeText(context, "Pasted Spotify link", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            if (spotifyUrlInput.isNotBlank() && !isImportingSpotify) {
                                onImportSpotifyPlaylist(spotifyUrlInput.trim())
                            }
                        },
                        enabled = spotifyUrlInput.isNotBlank() && !isImportingSpotify,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1DB954),
                            disabledContainerColor = Color(0xFF1DB954).copy(alpha = 0.30f),
                            contentColor = Color.White,
                            disabledContentColor = Color.White.copy(alpha = 0.70f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                    ) {
                        if (isImportingSpotify) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Importing Songs...",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Import Spotify Playlist",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF1DB954),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Tip: If your playlist is private, briefly toggle it to Public in Spotify to import. Once imported, you can make it Private again anytime — your songs stay saved in Auralis forever!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }

                    if (spotifyImportMessage != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = spotifyImportMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (spotifyImportMessage.startsWith("Imported", ignoreCase = true) || spotifyImportMessage.startsWith("Success", ignoreCase = true)) Color(0xFF16A34A) else Color(0xFFEF4444),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

        // ── SCROLLABLE PLAYLIST PICKER POPUP (YOUTUBE) ──
        if (showPlaylistPickerPopup) {
            YouTubePlaylistPickerBottomSheet(
                playlists = playlists,
                isLoading = isLoadingPlaylists,
                loadFailed = loadFailed,
                onRetry = { reloadKey++ },
                selected = selectedPlaylists,
                isImporting = isImportingYouTube,
                importMessage = youtubeImportMessage,
                onImport = { selected ->
                    onImportYouTubeLibraryPlaylists(selected)
                },
                onDismiss = { showPlaylistPickerPopup = false }
            )
        }

        // ── SCROLLABLE PLAYLIST PICKER POPUP (SPOTIFY) ──
        if (showSpotifyPlaylistPickerPopup) {
            SpotifyPlaylistPickerBottomSheet(
                playlists = spotifyPlaylists,
                isLoading = isLoadingSpotifyPlaylists,
                loadFailed = loadSpotifyFailed,
                onRetry = { reloadSpotifyKey++ },
                selected = selectedSpotifyPlaylists,
                isImporting = isImportingSpotify,
                importMessage = spotifyImportMessage,
                onImport = { selected ->
                    onImportSpotifyLibraryPlaylists(selected)
                },
                onDismiss = { showSpotifyPlaylistPickerPopup = false }
            )
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
    onDismiss: () -> Unit
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
                    text = "Your YouTube Music playlists",
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
                            text = if (allSelected) "Clear" else "Select all",
                            color = YOUTUBE_RED,
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
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Text(
                text = "Private playlists included. Liked Music is added to your liked songs; importing a playlist you already have updates it.",
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
                        CircularProgressIndicator(color = YOUTUBE_RED, modifier = Modifier.size(32.dp))
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
                            text = "Couldn't load your playlists from YouTube Music.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onRetry) {
                            Text("Try again", color = YOUTUBE_RED, fontWeight = FontWeight.Bold)
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
                            text = "No playlists found in your YouTube Music account.",
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
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (checked) YOUTUBE_RED.copy(alpha = 0.08f) else Color.Transparent)
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
                                            tint = YOUTUBE_RED,
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
                                            tint = YOUTUBE_RED,
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
                                    if (item.subtitle.isNotBlank()) {
                                        Text(
                                            text = item.subtitle,
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
                                    colors = CheckboxDefaults.colors(checkedColor = YOUTUBE_RED)
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
                    containerColor = YOUTUBE_RED,
                    disabledContainerColor = YOUTUBE_RED.copy(alpha = 0.35f),
                    contentColor = Color.White,
                    disabledContentColor = Color.White.copy(alpha = 0.7f)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Importing...",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                } else {
                    Text(
                        text = when {
                            selected.isEmpty() -> "Select playlists to import"
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

/**
 * Scrollable bottom sheet modal popup containing all the signed-in Spotify playlists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyPlaylistPickerBottomSheet(
    playlists: List<SpotifyLibrary.LibraryPlaylist>?,
    isLoading: Boolean,
    loadFailed: Boolean,
    onRetry: () -> Unit,
    selected: SnapshotStateList<String>,
    isImporting: Boolean,
    importMessage: String?,
    onImport: (List<SpotifyLibrary.LibraryPlaylist>) -> Unit,
    onDismiss: () -> Unit
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
                    text = "Your Spotify playlists",
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
                            text = if (allSelected) "Clear" else "Select all",
                            color = SPOTIFY_GREEN,
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
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Text(
                text = "Private playlists and Liked Songs included. Liked Songs are added to your favorites; importing a playlist you already have updates it.",
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
                        CircularProgressIndicator(color = SPOTIFY_GREEN, modifier = Modifier.size(32.dp))
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
                            text = "Couldn't load your playlists from Spotify.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onRetry) {
                            Text("Try again", color = SPOTIFY_GREEN, fontWeight = FontWeight.Bold)
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
                            text = "No playlists found in your Spotify account.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                }
                else -> {
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
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (checked) SPOTIFY_GREEN.copy(alpha = 0.08f) else Color.Transparent)
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
                                    if (item.isLikedSongs) {
                                        Icon(
                                            imageVector = Icons.Default.Favorite,
                                            contentDescription = null,
                                            tint = SPOTIFY_GREEN,
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
                                        SpotifyLogoIcon(modifier = Modifier.size(22.dp))
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
                                    if (item.subtitle.isNotBlank()) {
                                        Text(
                                            text = item.subtitle,
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
                                    colors = CheckboxDefaults.colors(checkedColor = SPOTIFY_GREEN)
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
                    containerColor = SPOTIFY_GREEN,
                    disabledContainerColor = SPOTIFY_GREEN.copy(alpha = 0.35f),
                    contentColor = Color.White,
                    disabledContentColor = Color.White.copy(alpha = 0.7f)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Importing...",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                } else {
                    Text(
                        text = when {
                            selected.isEmpty() -> "Select playlists to import"
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SpotifySignInWebView(onSignedIn: () -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    var finishing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun finishFrom(view: WebView) {
        if (finishing) return
        finishing = true
        scope.launch {
            // Settle session cookies and web player state
            repeat(25) {
                val cookie = CookieManager.getInstance().getCookie("https://open.spotify.com").orEmpty()
                if (cookie.contains("sp_dc=") || cookie.contains("sp_key=")) {
                    val session = readSpotifySession(view)
                    if (session != null && session.accessToken.isNotBlank() && !session.isAnonymous) {
                        CookieManager.getInstance().flush()
                        SpotifySession.save(
                            cookie = cookie,
                            accessToken = session.accessToken,
                            expiresAtMs = session.expiresAt,
                            accountLabel = session.displayName.ifBlank { "Signed in" },
                            userId = session.userId,
                            avatarUrl = session.avatarUrl
                        )
                        onSignedIn()
                        return@launch
                    }
                }
                delay(600)
            }
            finishing = false
            error = "Couldn't finish signing in. Make sure you completed sign-in, then try again."
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(top = 52.dp),
            factory = { ctx ->
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = SpotifySession.USER_AGENT
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val scheme = request.url.scheme
                            return !(scheme == "http" || scheme == "https")
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            val uri = url?.let(android.net.Uri::parse)
                            if (uri?.host == "open.spotify.com") {
                                finishFrom(view)
                            }
                        }
                    }
                    loadUrl("https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F")
                }
            }
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(MaterialTheme.dynamicBackground)
                .padding(horizontal = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Cancel",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    text = "Sign in to Spotify",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 17.sp
                )
            }
        }

        if (finishing) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = SPOTIFY_GREEN)
        }
        error?.let {
            Text(
                text = it,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xE6202020))
                    .padding(12.dp)
            )
        }
    }
}

private class SpotifyWebSession(
    val accessToken: String,
    val expiresAt: Long,
    val isAnonymous: Boolean,
    val displayName: String,
    val userId: String,
    val avatarUrl: String
)

private suspend fun readSpotifySession(view: WebView): SpotifyWebSession? {
    val result = CompletableDeferred<String?>()
    withContext(Dispatchers.Main) {
        val script = """
            (async function() {
                try {
                    const res = await fetch('/get_access_token?reason=transport&productType=web_player', {
                        credentials: 'include'
                    });
                    if (!res.ok) return JSON.stringify({ error: 'http_' + res.status });
                    const data = await res.json();
                    let name = '';
                    let uid = '';
                    let img = '';
                    if (data.accessToken) {
                        try {
                            const meRes = await fetch('https://api.spotify.com/v1/me', {
                                headers: { 'Authorization': 'Bearer ' + data.accessToken }
                            });
                            if (meRes.ok) {
                                const me = await meRes.json();
                                name = me.display_name || me.id || '';
                                uid = me.id || '';
                                if (me.images && me.images.length > 0) {
                                    img = me.images[0].url || '';
                                }
                            }
                        } catch(e) {}
                    }
                    return JSON.stringify({
                        token: data.accessToken || '',
                        expiresAt: data.accessTokenExpirationTimestampMs || 0,
                        isAnon: data.isAnonymous || false,
                        name: name,
                        uid: uid,
                        img: img
                    });
                } catch(e) {
                    return JSON.stringify({ error: e.message });
                }
            })()
        """.trimIndent()
        view.evaluateJavascript(script) { raw -> result.complete(raw) }
    }
    val raw = withTimeoutOrNull(2_000L) { result.await() } ?: return null
    return try {
        val inner = org.json.JSONTokener(raw).nextValue() as? String ?: return null
        if (inner.isBlank()) return null
        val json = JSONObject(inner)
        val token = json.optString("token")
        if (token.isBlank()) return null
        SpotifyWebSession(
            accessToken = token,
            expiresAt = json.optLong("expiresAt", System.currentTimeMillis() + 3600_000L),
            isAnonymous = json.optBoolean("isAnon", false),
            displayName = json.optString("name"),
            userId = json.optString("uid"),
            avatarUrl = json.optString("img")
        )
    } catch (_: Exception) {
        null
    }
}

