package com.auralis.music.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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
import com.auralis.music.data.network.YouTubeMusicLibrary
import com.auralis.music.data.network.YouTubeSession
import com.auralis.music.ui.profile.SpotifyLogoIcon
import com.auralis.music.ui.theme.dynamicBackground

/**
 * Dedicated Import Playlists screen.
 * Gives users clean choices:
 * 1. Pick from their signed-in YouTube Music account via a scrollable popup.
 * 2. Import a YouTube Music playlist by link.
 * 3. Import a Spotify playlist by link.
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
    isImportingSpotify: Boolean,
    spotifyImportMessage: String?,
    onClearSpotifyImportMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val youTubeSignedIn by YouTubeSession.signedIn.collectAsState()
    val accountLabel by YouTubeSession.accountLabel.collectAsState()

    var showPlaylistPickerPopup by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf<List<YouTubeMusicLibrary.LibraryPlaylist>?>(null) }
    var isLoadingPlaylists by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    val selectedPlaylists = remember { mutableStateListOf<String>() }

    var youtubeUrlInput by remember { mutableStateOf("") }
    var spotifyUrlInput by remember { mutableStateOf("") }

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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.dynamicBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
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

        // ── SCROLLABLE PLAYLIST PICKER POPUP ──
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
    var pageLoadingProgress by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    fun checkAndFinish(view: WebView) {
        if (finishing) return
        scope.launch {
            val cookie = listOf(
                "https://open.spotify.com",
                "https://spotify.com",
                "https://accounts.spotify.com"
            ).map { CookieManager.getInstance().getCookie(it).orEmpty() }
                .filter { it.isNotBlank() }
                .joinToString("; ")

            if (cookie.contains("sp_dc=") || cookie.contains("sp_key=")) {
                finishing = true
                val token = withContext(Dispatchers.IO) {
                    SpotifySession.fetchWebPlayerToken(cookie)
                }
                if (!token.isNullOrBlank()) {
                    val profile = withContext(Dispatchers.IO) {
                        SpotifySession.fetchUserProfile(token)
                    }
                    CookieManager.getInstance().flush()
                    SpotifySession.save(
                        cookie = cookie,
                        accessToken = token,
                        expiresAtMs = System.currentTimeMillis() + 3600_000L,
                        accountLabel = profile?.displayName?.ifBlank { "Signed in" } ?: "Signed in",
                        userId = profile?.id.orEmpty(),
                        avatarUrl = profile?.avatarUrl.orEmpty()
                    )
                    onSignedIn()
                    return@launch
                }
                finishing = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.dynamicBackground)
    ) {
        // Top App Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
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
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Sign in to Spotify",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 17.sp
                )
            }
            if (finishing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .size(20.dp),
                    color = SPOTIFY_GREEN,
                    strokeWidth = 2.dp
                )
            }
        }

        // Real-time progress bar for page loading
        if (pageLoadingProgress in 1..99) {
            LinearProgressIndicator(
                progress = { pageLoadingProgress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp),
                color = SPOTIFY_GREEN,
                trackColor = Color.Transparent
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.WHITE)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.setSupportMultipleWindows(false)
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true

                        // Strip '; wv' and 'Version/X.X' so Google Sign-In and Spotify allow mobile web login
                        // without anti-bot or "disallowed_useragent" errors
                        val rawUa = settings.userAgentString.orEmpty()
                        settings.userAgentString = rawUa
                            .replace("; wv", "")
                            .replace(Regex("Version/\\d+\\.\\d+\\s*"), "")

                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                pageLoadingProgress = newProgress
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url ?: return false
                                val scheme = url.scheme.orEmpty().lowercase()
                                if (scheme == "http" || scheme == "https") {
                                    return false
                                }
                                return try {
                                    val intent = android.content.Intent.parseUri(url.toString(), android.content.Intent.URI_INTENT_SCHEME)
                                    view.context.startActivity(intent)
                                    true
                                } catch (_: Exception) {
                                    true
                                }
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                checkAndFinish(view)
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                checkAndFinish(view)
                            }
                        }

                        loadUrl("https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F")
                    }
                }
            )

            if (finishing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(24.dp)
                    ) {
                        CircularProgressIndicator(color = SPOTIFY_GREEN, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Connecting Spotify account...",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                    }
                }
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
}

