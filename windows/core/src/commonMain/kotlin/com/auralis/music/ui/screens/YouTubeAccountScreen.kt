package com.auralis.music.ui.screens

import com.auralis.music.ui.i18n.Strings

import com.auralis.music.ui.i18n.str

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.auralis.music.ui.components.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import com.auralis.music.ui.components.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import com.auralis.music.ui.components.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import com.auralis.music.ui.components.IconButton
import androidx.compose.material3.MaterialTheme
import com.auralis.music.ui.components.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.data.network.YouTubeMusicLibrary
import com.auralis.music.data.network.YouTubeSession
import com.auralis.music.ui.theme.dynamicBackground
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

val YOUTUBE_RED = Color(0xFFFF0033)
const val SIGN_IN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"

/**
 * Optional YouTube sign-in, only for age-restricted songs. Auralis's own sign-in is a Firebase
 * account YouTube never sees, so these songs need the user to sign in to YouTube itself.
 */
@Composable
fun YouTubeAccountScreen(onDismiss: () -> Unit) {
    val webSignIn = LocalWebSignIn.current
    val platformActions = com.auralis.music.ui.components.LocalPlatformActions.current
    val signedIn by YouTubeSession.signedIn.collectAsState()
    val accountLabel by YouTubeSession.accountLabel.collectAsState()
    var isSigningIn by remember { mutableStateOf(false) }

    com.auralis.music.ui.components.PlatformBackHandler(enabled = true) {
        if (isSigningIn) isSigningIn = false else onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.dynamicBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        val youTubeSignIn = webSignIn.youTubeSignIn
        if (isSigningIn && youTubeSignIn != null) {
            youTubeSignIn(
                { isSigningIn = false },
                { isSigningIn = false }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
            ) {
                com.auralis.music.ui.components.PageHeader(str(Strings.youtube_account), onDismiss)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.2.dp, YOUTUBE_RED.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                        .padding(16.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(YOUTUBE_RED.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (signedIn) Icons.Default.CheckCircle else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = YOUTUBE_RED,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (signedIn) str(Strings.signed_in_to_youtube) else str(Strings.not_signed_in_to_youtube),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 15.sp
                                )
                                if (signedIn && accountLabel.isNotBlank()) {
                                    Text(
                                        text = accountLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = if (signedIn) {
                                str(Strings.your_youtube_account_is_connected_you_ca)
                            } else {
                                "Signing in to a YouTube account is required to download age-restricted music. It also lets you directly import your YouTube Music playlists—including private ones—without having to make them public."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp
                        )

                        Spacer(Modifier.height(16.dp))
                        if (signedIn) {
                            OutlinedButton(
                                onClick = { webSignIn.signOutOfYouTube() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(str(Strings.sign_out_of_youtube), color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            Button(
                                onClick = {
                                    if (webSignIn.youTubeSignIn != null) isSigningIn = true
                                    else platformActions.showMessage(com.auralis.music.ui.settings.PlatformNotes.noWebSignIn)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = YOUTUBE_RED, contentColor = Color.White),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(str(Strings.sign_in_to_youtube), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp).padding(top = 2.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = str(Strings.your_youtube_sign_in_stays_on_this_devic),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}







