package com.auralis.music.windows.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.auralis.music.data.network.YouTubeSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DesktopSignInDialog(account: DesktopAccount, onDismiss: () -> Unit) {
    var auralis by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        SignInGlassSurface {
            Column(Modifier.width(560.dp).heightIn(max = 700.dp).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(if (auralis) "Auralis account" else "Sign in to YouTube Music", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Row {
                    FilterChip(!auralis, { auralis = false }, label = { Text("YouTube Music") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(auralis, { auralis = true }, label = { Text("Auralis account") })
                }
                if (auralis) AccountPage(account) { auralis = false }
                else BrowserYouTubePicker()
            }
        }
    }
}

/** The app content beneath the modal is blurred in AuralisDesktopApp, leaving this content sharp. */
@Composable
internal fun SignInGlassSurface(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface.copy(alpha = .82f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp, shadowElevation = 24.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .14f)),
        content = content)
}

@Composable
internal fun BrowserYouTubePicker(previewProfiles: List<BrowserYouTubeLogin.Profile>? = null) {
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(previewProfiles) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var paste by remember { mutableStateOf(false) }
    var cookie by remember { mutableStateOf("") }
    val signedIn by YouTubeSession.signedIn.collectAsState()
    val label by YouTubeSession.accountLabel.collectAsState()
    LaunchedEffect(Unit) { if (previewProfiles == null) profiles = withContext(Dispatchers.IO) { BrowserYouTubeLogin.profiles() } }
    fun import(read: () -> Pair<String, String>) {
        busy = true; message = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val (value, accountLabel) = read()
                    check(YouTubeSession.hasAuthCookies(value)) { "This cookie doesn't contain a signed-in YouTube session." }
                    YouTubeSession.save(value, "", "0", accountLabel)
                }
                cookie = ""; paste = false
                message = "YouTube Music connected."
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Couldn't connect this account." }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (signedIn) {
            Text("Connected: ${label.ifBlank { "YouTube Music" }}")
            TextButton(onClick = { scope.launch(Dispatchers.IO) { YouTubeSession.signOut() } }, enabled = !busy) { Text("Disconnect YouTube Music") }
        }
        Text("Choose the browser profile where you're already signed in to YouTube. Only its YouTube session is imported, and saved securely on this PC.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            profiles == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            profiles!!.isEmpty() -> Text("No supported browser profiles found. You can paste a YouTube cookie below.")
            else -> profiles!!.forEach { profile ->
                OutlinedButton(onClick = { import { BrowserYouTubeLogin.read(profile) to profile.label } },
                    enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(profile.label) }
            }
        }
        TextButton(onClick = { paste = !paste }, enabled = !busy) { Text("Paste a YouTube cookie instead") }
        if (paste) {
            OutlinedTextField(cookie, { cookie = it }, label = { Text("YouTube Cookie header") }, modifier = Modifier.fillMaxWidth(),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), enabled = !busy, maxLines = 3)
            Button(onClick = { import { cookie.trim().removePrefix("Cookie:").trim() to "YouTube Music" } }, enabled = cookie.isNotBlank() && !busy) { Text("Connect YouTube Music") }
        }
        TextButton(onClick = {
            scope.launch(Dispatchers.IO) {
                runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F")) }
                    .onFailure { message = "No browser is available. Use a supported browser to sign in to Google, or paste an existing YouTube cookie." }
            }
        }, enabled = !busy) { Text("Sign in with email and password on Google") }
        Text("After signing in on Google's page, select that browser profile above. Auralis email/password sign-in is available in the Auralis account tab.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}
