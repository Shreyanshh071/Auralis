package com.auralis.music.windows.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.auralis.music.data.network.SpotifySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Spotify sign-in on Windows, where there is no embedded browser: the session (sp_dc) comes from
 * a browser profile the user is already signed in to Spotify on, chosen here, or from a pasted
 * cookie. It's checked by fetching an access token before it's kept, protected by Windows (DPAPI).
 */
@Composable
internal fun DesktopSpotifySignIn(onSignedIn: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf<List<BrowserYouTubeLogin.Profile>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var paste by remember { mutableStateOf(false) }
    var cookie by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { profiles = withContext(Dispatchers.IO) { BrowserYouTubeLogin.profiles() } }
    fun connect(read: () -> Pair<String, String>) {
        busy = true; message = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val (value, label) = read()
                    check(SpotifySession.hasAuth(value, "")) { "This cookie doesn't contain a signed-in Spotify session (sp_dc)." }
                    SpotifySession.save(value, "", 0L, label)
                    if (SpotifySession.getValidAccessToken() == null) {
                        SpotifySession.signOut()
                        error("Spotify didn't accept this session. Sign in again at open.spotify.com, then retry.")
                    }
                }
                onSignedIn()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Couldn't connect Spotify." }
            finally { busy = false }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
            Column(Modifier.width(460.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sign in to Spotify", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Choose the browser profile where you're signed in to open.spotify.com. Only its Spotify session is used, and it's saved securely on this PC.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    profiles == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    profiles!!.isEmpty() -> Text("No supported browser profiles found. You can paste your sp_dc cookie below.")
                    else -> profiles!!.forEach { profile ->
                        OutlinedButton(onClick = { connect { BrowserYouTubeLogin.readSpotify(profile) to "Spotify · ${profile.label}" } },
                            enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(profile.label) }
                    }
                }
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI("https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F")) } }
                }, enabled = !busy) { Text("Not signed in? Open Spotify in your browser") }
                TextButton(onClick = { paste = !paste }, enabled = !busy) { Text("Paste the sp_dc cookie instead") }
                if (paste) {
                    OutlinedTextField(cookie, { cookie = it }, label = { Text("sp_dc value or Cookie header") }, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), enabled = !busy, maxLines = 3)
                    Button(onClick = {
                        val raw = cookie.trim().removePrefix("Cookie:").trim()
                        connect { (if (raw.contains("=")) raw else "sp_dc=$raw") to "Spotify" }
                    }, enabled = cookie.isNotBlank() && !busy) { Text("Connect Spotify") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.padding(top = 4.dp)) { Text("Cancel") }
            }
        }
    }
}
