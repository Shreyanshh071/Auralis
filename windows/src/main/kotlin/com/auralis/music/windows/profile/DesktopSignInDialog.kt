package com.auralis.music.windows.profile

import com.auralis.music.ui.i18n.desktopText

import com.auralis.music.ui.components.IconButton
import com.auralis.music.ui.components.Button
import com.auralis.music.ui.components.TextButton
import com.auralis.music.ui.components.OutlinedButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.auralis.music.ui.components.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.auralis.music.data.network.YouTubeSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class SignInFilterTab { ALL, GOOGLE_EMAIL, YOUTUBE }

@Composable
internal fun DesktopSignInDialog(
    account: DesktopAccount,
    onConnected: () -> Unit = {},
    onDisconnected: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val ytSignedIn by YouTubeSession.signedIn.collectAsState()
    val ytLabel by YouTubeSession.accountLabel.collectAsState()
    val accountState by account.state.collectAsState()
    // Two tabs: the Auralis account (Google or email) and YouTube Music (its own browser session).
    var selectedTab by remember { mutableStateOf(SignInFilterTab.GOOGLE_EMAIL) }

    var localMessage by remember { mutableStateOf<String?>(null) }
    var isErrorMessage by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // Email / Password inputs
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var createMode by remember { mutableStateOf(false) }

    fun submitEmail() {
        if (!accountState.busy && !busy && email.contains('@') && password.isNotEmpty()) {
            localMessage = null
            isErrorMessage = false
            account.signIn(email.trim(), password, createMode)
            password = ""
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        SignInGlassSurface {
            Column(
                Modifier.width(580.dp).heightIn(max = 750.dp).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header with title and clean close button
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = when {
                                accountState.profile != null && ytSignedIn -> "Connected Accounts"
                                ytSignedIn -> "YouTube Music Account"
                                accountState.profile != null -> "Auralis Account"
                                else -> "Sign in to Auralis"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = desktopText("Sync your library, playlists, and listening stats across devices"),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = desktopText("Close"),
                            tint = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }

                // Two tabs: Auralis, YouTube Music.
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                        .background(Color.White.copy(alpha = 0.05f)).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(SignInFilterTab.GOOGLE_EMAIL to "Auralis", SignInFilterTab.YOUTUBE to "YouTube Music").forEach { (tab, label) ->
                        val isSelected = selectedTab == tab
                        Box(
                            Modifier.weight(1f).clip(MaterialTheme.shapes.small)
                                .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
                                .then(if (isSelected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), MaterialTheme.shapes.small) else Modifier)
                                .clickable { selectedTab = tab }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.65f))
                        }
                    }
                }

                // Scrollable main body
                Column(
                    Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // ── STATUS BANNERS (CONNECTED ACCOUNTS) ──
                    if (selectedTab == SignInFilterTab.GOOGLE_EMAIL && accountState.profile != null) {
                        Card(
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.AccountCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            accountState.profile!!.displayName.ifBlank { "Auralis Account" },
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White
                                        )
                                        Surface(
                                            shape = MaterialTheme.shapes.small,
                                            color = Color(0xFF16A34A).copy(alpha = 0.2f),
                                            border = BorderStroke(1.dp, Color(0xFF16A34A).copy(alpha = 0.5f))
                                        ) {
                                            Text(
                                                desktopText(if (accountState.profile!!.isGoogleConnected) "Google" else "Account"),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF4ADE80),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        accountState.profile!!.email,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.7f)
                                    )
                                }
                                Button(
                                    onClick = {
                                        account.signOut()
                                        localMessage = "Signed out of account."
                                        isErrorMessage = false
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                        contentColor = Color.White
                                    ),
                                    enabled = !accountState.busy && !busy
                                ) {
                                    Text(desktopText("Sign out"))
                                }
                            }
                        }
                        // Google sign-in is the Auralis account only; YouTube Music is its own session.
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(if (ytSignedIn) Icons.Default.CheckCircle else Icons.Outlined.Info, null, Modifier.size(16.dp),
                                tint = if (ytSignedIn) Color(0xFF4ADE80) else Color.White.copy(alpha = 0.6f))
                            Text(if (ytSignedIn) "YouTube Music connected${ytLabel?.let { " · $it" } ?: ""}"
                                else "YouTube Music isn't connected yet.",
                                style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.weight(1f))
                            if (!ytSignedIn) TextButton(onClick = { selectedTab = SignInFilterTab.YOUTUBE }) { Text(desktopText("Connect")) }
                        }
                    }

                    if (selectedTab == SignInFilterTab.YOUTUBE && ytSignedIn) {
                        Card(
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(desktopText("YouTube Music Connected"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    Text(
                                        text = ytLabel.ifBlank { "Active session linked to this PC" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.7f)
                                    )
                                }
                                Button(
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            localMessage = null
                                            isErrorMessage = false
                                            try {
                                                withContext(Dispatchers.IO) { YouTubeSession.signOut() }
                                                onDisconnected()
                                                localMessage = "Disconnected from YouTube Music."
                                            } catch (e: Exception) {
                                                localMessage = e.message ?: "Failed to disconnect."
                                                isErrorMessage = true
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                        contentColor = Color.White
                                    ),
                                    enabled = !busy && !accountState.busy
                                ) {
                                    if (busy) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                        Spacer(Modifier.width(6.dp))
                                        Text(desktopText("Disconnecting..."))
                                    } else {
                                        Text(desktopText("Disconnect"))
                                    }
                                }
                            }
                        }
                    }

                    // Progress indicators
                    if (busy || accountState.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }

                    // Message alerts
                    val activeMessage = localMessage ?: accountState.message
                    activeMessage?.let { msg ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (isErrorMessage) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                            border = BorderStroke(1.dp, if (isErrorMessage) MaterialTheme.colorScheme.error.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    if (isErrorMessage) Icons.Default.Close else Icons.Default.CheckCircle,
                                    null,
                                    tint = if (isErrorMessage) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isErrorMessage) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                                // Dismisses both kinds: the dialog's own note and the account's ("Signed in as …").
                                IconButton(onClick = { localMessage = null; account.clearMessage() }, modifier = Modifier.size(18.dp)) {
                                    Icon(Icons.Default.Close, desktopText("Dismiss"), tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }

                    // ─────────────────────────────────────────────────────────────
                    // 1. TOP OPTION: SIGN IN WITH GOOGLE
                    // ─────────────────────────────────────────────────────────────
                    if (selectedTab == SignInFilterTab.GOOGLE_EMAIL && accountState.profile == null) SectionHeading("Auralis account",
                        "Sign in to bring your phone’s personalized feed to your desktop, with your playlists, likes and listening stats synced across devices.")
                    if (selectedTab != SignInFilterTab.YOUTUBE && accountState.profile == null) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = Color.White,
                            contentColor = Color(0xFF1F1F1F),
                            border = BorderStroke(1.dp, Color(0xFF747775).copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth().clickable(
                                enabled = !busy && !accountState.busy && account.googleAvailable
                            ) {
                                localMessage = null
                                isErrorMessage = false
                                account.signInGoogle()
                            }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                GoogleLogo(Modifier.size(24.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        desktopText("Sign in with Google"),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF1F1F1F)
                                    )
                                    Text(
                                        desktopText("Opens browser to choose your Gmail / Google account"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF5F6368)
                                    )
                                }
                            }
                        }

                        // Browser waiting state
                        AnimatedVisibility(visible = accountState.browser) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                    Text(
                                        desktopText("Select your Google account in the browser to sign in…"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = { account.cancelGoogle() },
                                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.85f))
                                    ) {
                                        Text(desktopText("Cancel"))
                                    }
                                }
                            }
                        }

                        // ─────────────────────────────────────────────────────────────
                        // 2. SECOND OPTION: MANUALLY ENTER EMAIL AND PASS
                        // ─────────────────────────────────────────────────────────────
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            HorizontalDivider(Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
                            Text(desktopText("OR ENTER EMAIL MANUALLY"), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
                            HorizontalDivider(Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
                        }

                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text(desktopText("Email Address")) },
                            placeholder = { Text(desktopText("name@example.com")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !accountState.busy && !busy,
                            trailingIcon = {
                                if (email.isNotEmpty()) {
                                    IconButton(onClick = { email = "" }) {
                                        Icon(Icons.Default.Close, desktopText("Clear"), Modifier.size(16.dp))
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
                        )

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(desktopText("Password")) },
                            placeholder = { Text(desktopText("Enter your password")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !accountState.busy && !busy,
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        if (showPassword) "Hide password" else "Show password",
                                        Modifier.size(18.dp)
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submitEmail() })
                        )

                        Button(
                            onClick = { submitEmail() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !accountState.busy && !busy && email.contains('@') && password.isNotEmpty()
                        ) {
                            if (accountState.busy && !accountState.browser) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text(desktopText(if (createMode) "Creating Account..." else "Signing In..."))
                            } else {
                                Text(desktopText(if (createMode) "Create Account" else "Sign In with Email"))
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { createMode = !createMode }, enabled = !accountState.busy && !busy) {
                                Text(
                                    desktopText(if (createMode) "Already have an account? Sign in" else "Need an account? Create one"),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            TextButton(onClick = { account.reset(email) }, enabled = !accountState.busy && !busy && email.contains('@')) {
                                Text(desktopText("Forgot password?"), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    // ─────────────────────────────────────────────────────────────
                    // 3. THIRD OPTION: IMPORT YOUTUBE MUSIC FROM BROWSER
                    // 4. FOURTH OPTION (LAST): PASTE COOKIE MANUALLY
                    // ─────────────────────────────────────────────────────────────
                    if (selectedTab != SignInFilterTab.GOOGLE_EMAIL) {

                        SectionHeading("YouTube Music",
                            "Sign in to play songs that require a YouTube account, including restricted songs your account is eligible to access. This also connects your YouTube Music mixes and library. Use the Google window Auralis opens or a browser where you're already signed in.")
                        // Sign in with Google for YouTube Music: a browser window with Auralis's own profile
                        // opens on Google's account chooser (or email and password); when YouTube Music loads
                        // signed in there, the session comes straight into Auralis and the window closes.
                        if (!ytSignedIn) {
                            var signingIn by remember { mutableStateOf(false) }
                            var status by remember { mutableStateOf<String?>(null) }
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = Color.White,
                                contentColor = Color(0xFF1F1F1F),
                                border = BorderStroke(1.dp, Color(0xFF747775).copy(alpha = 0.3f)),
                                modifier = Modifier.fillMaxWidth().clickable(enabled = !signingIn) {
                                    signingIn = true
                                    scope.launch {
                                        try {
                                            val session = YouTubeBrowserSignIn.signIn { status = it }
                                            withContext(Dispatchers.IO) {
                                                YouTubeSession.save(session, "", "0", "YouTube Music")
                                                YouTubeSessionSource.rememberSignInWindow()
                                            }
                                            status = null
                                            localMessage = "YouTube Music connected."
                                            isErrorMessage = false
                                            onConnected()
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            status = null
                                            localMessage = e.message ?: "Couldn't sign in. Try again."
                                            isErrorMessage = true
                                        } finally {
                                            signingIn = false
                                        }
                                    }
                                }
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                    if (signingIn) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF1F1F1F))
                                    else GoogleLogo(Modifier.size(20.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(desktopText(if (signingIn) "Waiting for sign-in…" else "Sign in with Google for YouTube Music"), fontWeight = FontWeight.Medium)
                                }
                            }
                            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f)) }
                            Text(desktopText("Or use a browser where you're already signed in:"), style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.6f))
                        }

                        BrowserYouTubePicker(
                            account = account,
                            showConnectedCard = false,
                            onConnected = onConnected,
                            onDisconnected = onDisconnected
                        )
                    }
                }
            }
        }
    }
}

/** Vector Google 'G' 4-color logo drawn via Canvas paths. */
@Composable
internal fun GoogleLogo(modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier = modifier) {
        val scaleX = size.width / 48f
        val scaleY = size.height / 48f

        // Blue path
        val blue = Path().apply {
            moveTo(46.98f * scaleX, 24.55f * scaleY)
            cubicTo(46.98f * scaleX, 22.84f * scaleY, 46.83f * scaleX, 21.19f * scaleY, 46.54f * scaleX, 19.6f * scaleY)
            lineTo(24f * scaleX, 19.6f * scaleY)
            lineTo(24f * scaleX, 28.98f * scaleY)
            lineTo(36.94f * scaleX, 28.98f * scaleY)
            cubicTo(36.38f * scaleX, 31.98f * scaleY, 34.69f * scaleX, 34.52f * scaleY, 32.14f * scaleY, 36.23f * scaleY)
            lineTo(32.14f * scaleX, 42.23f * scaleY)
            lineTo(39.9f * scaleX, 42.23f * scaleY)
            cubicTo(44.44f * scaleX, 38.05f * scaleY, 46.98f * scaleX, 31.87f * scaleY, 46.98f * scaleX, 24.55f * scaleY)
            close()
        }
        drawPath(blue, Color(0xFF4285F4))

        // Green path
        val green = Path().apply {
            moveTo(24f * scaleX, 48f * scaleY)
            cubicTo(30.48f * scaleX, 48f * scaleY, 35.91f * scaleX, 45.85f * scaleY, 39.9f * scaleX, 42.23f * scaleY)
            lineTo(32.14f * scaleX, 36.23f * scaleY)
            cubicTo(29.98f * scaleX, 37.68f * scaleY, 27.22f * scaleX, 38.55f * scaleY, 24f * scaleX, 38.55f * scaleY)
            cubicTo(17.75f * scaleX, 38.55f * scaleY, 12.46f * scaleX, 34.29f * scaleY, 10.57f * scaleX, 28.6f * scaleY)
            lineTo(2.56f * scaleX, 28.6f * scaleY)
            lineTo(2.56f * scaleX, 34.8f * scaleY)
            cubicTo(6.51f * scaleX, 42.66f * scaleY, 14.62f * scaleX, 48f * scaleY, 24f * scaleX, 48f * scaleY)
            close()
        }
        drawPath(green, Color(0xFF34A853))

        // Yellow path
        val yellow = Path().apply {
            moveTo(10.57f * scaleX, 28.6f * scaleY)
            cubicTo(10.09f * scaleX, 27.16f * scaleY, 9.81f * scaleX, 25.61f * scaleY, 9.81f * scaleX, 24f * scaleY)
            cubicTo(9.81f * scaleX, 22.39f * scaleY, 10.09f * scaleX, 20.84f * scaleY, 10.57f * scaleX, 19.4f * scaleY)
            lineTo(10.57f * scaleX, 13.2f * scaleY)
            lineTo(2.56f * scaleX, 13.2f * scaleY)
            cubicTo(0.92f * scaleX, 16.46f * scaleY, 0f * scaleX, 20.12f * scaleY, 0f * scaleX, 24f * scaleY)
            cubicTo(0f * scaleX, 27.88f * scaleY, 0.92f * scaleX, 31.54f * scaleY, 2.56f * scaleX, 34.8f * scaleY)
            lineTo(10.57f * scaleX, 28.6f * scaleY)
            close()
        }
        drawPath(yellow, Color(0xFFFBBC05))

        // Red path
        val red = Path().apply {
            moveTo(24f * scaleX, 9.45f * scaleY)
            cubicTo(27.52f * scaleX, 9.45f * scaleY, 30.69f * scaleX, 10.66f * scaleY, 33.18f * scaleX, 13.04f * scaleY)
            lineTo(40.08f * scaleX, 6.14f * scaleY)
            cubicTo(35.9f * scaleX, 2.25f * scaleY, 30.47f * scaleX, 0f * scaleY, 24f * scaleX, 0f * scaleY)
            cubicTo(14.62f * scaleX, 0f * scaleY, 6.51f * scaleX, 5.34f * scaleY, 2.56f * scaleX, 13.2f * scaleY)
            lineTo(10.57f * scaleX, 19.4f * scaleY)
            cubicTo(12.46f * scaleX, 13.71f * scaleY, 17.75f * scaleX, 9.45f * scaleY, 24f * scaleX, 9.45f * scaleY)
            close()
        }
        drawPath(red, Color(0xFFEA4335))
    }
}

/** The app content beneath the modal is blurred in AuralisDesktopApp, leaving this content sharp. */
@Composable
internal fun SignInGlassSurface(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = Color(0xFF141318).copy(alpha = .60f),
        contentColor = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = .12f))
    ) {
        Box {
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = .06f),
                        .35f to Color.Transparent
                    )
                )
            )
            content()
        }
    }
}

/** A plain glass list row, for choosing a browser profile or action. */
@Composable
internal fun GlassRow(
    label: String,
    subtitle: String? = null,
    leadingIcon: ImageVector = Icons.Outlined.Language,
    trailingBadge: String? = null,
    trailingBadgeColor: Color? = null,
    trailingIcon: ImageVector? = null,
    trailingIconTint: Color? = null,
    isActive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(
                if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = if (hovered && enabled) 0.20f else 0.12f)
                else Color.White.copy(alpha = if (hovered && enabled) .08f else 0f)
            )
            .border(
                1.dp,
                if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else Color.Transparent,
                MaterialTheme.shapes.medium
            )
            .hoverable(hover)
            .clickable(enabled = enabled, interactionSource = hover, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(
                leadingIcon,
                null,
                Modifier.size(20.dp),
                tint = if (isActive) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = if (enabled) .85f else .4f)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = if (enabled) 1f else .5f)
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isActive) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.55f)
                )
            }
        }
        if (trailingBadge != null) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = (trailingBadgeColor ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.20f),
                border = BorderStroke(1.dp, (trailingBadgeColor ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.5f))
            ) {
                Text(
                    trailingBadge,
                    style = MaterialTheme.typography.labelSmall,
                    color = trailingBadgeColor ?: MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        if (trailingIcon != null) {
            Icon(
                trailingIcon,
                null,
                Modifier.size(18.dp),
                tint = trailingIconTint ?: MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
internal fun BrowserYouTubePicker(
    previewProfiles: List<BrowserYouTubeLogin.Profile>? = null,
    account: DesktopAccount? = null,
    showConnectedCard: Boolean = true,
    onConnected: () -> Unit = {},
    onDisconnected: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(previewProfiles) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isErrorMessage by remember { mutableStateOf(false) }
    var paste by remember { mutableStateOf(false) }
    var cookie by remember { mutableStateOf("") }
    var showCookieText by remember { mutableStateOf(false) }
    val signedIn by YouTubeSession.signedIn.collectAsState()
    val label by YouTubeSession.accountLabel.collectAsState()
    val accountState by account?.state?.collectAsState() ?: remember { mutableStateOf(DesktopAccount.State()) }

    LaunchedEffect(Unit) {
        if (previewProfiles == null) {
            profiles = withContext(Dispatchers.IO) { BrowserYouTubeLogin.profiles() }
        }
    }

    // Cookies are read only when a profile is picked. The suggestion is the profile the browser
    // lists as signed in with the same Google account as Auralis (its profile list, no cookies).
    val workingCandidate = remember(profiles, accountState.profile?.email) {
        val email = accountState.profile?.email?.takeIf { it.contains('@') }
        profiles?.firstOrNull { email != null && it.email.equals(email, ignoreCase = true) }
    }

    fun import(read: () -> Pair<String, String>) {
        busy = true
        message = null
        isErrorMessage = false
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val (value, accountLabel) = read()
                    check(YouTubeSession.hasAuthCookies(value)) { "This cookie doesn't contain a signed-in YouTube session." }
                    YouTubeSession.save(value, "", "0", accountLabel)
                }
                cookie = ""
                paste = false
                onConnected()
                message = "YouTube Music connected successfully!"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Couldn't connect this account."
                isErrorMessage = true
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showConnectedCard && signedIn) {
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(desktopText("YouTube Music Connected"), style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Text(
                            text = label.ifBlank { "Active session linked to this PC" },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                message = null
                                isErrorMessage = false
                                try {
                                    withContext(Dispatchers.IO) { YouTubeSession.signOut() }
                                    onDisconnected()
                                    message = "Disconnected from YouTube Music."
                                } catch (e: Exception) {
                                    message = e.message ?: "Failed to disconnect."
                                    isErrorMessage = true
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                            contentColor = Color.White
                        ),
                        enabled = !busy && !accountState.busy
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text(desktopText("Disconnecting..."))
                        } else {
                            Text(desktopText("Disconnect"))
                        }
                    }
                }
            }
        } else if (!signedIn && workingCandidate != null) {
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.TravelExplore, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(desktopText("1-Click Connect Available"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text("${workingCandidate.label} is signed in with ${workingCandidate.email}, the same Google account as Auralis.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
                    }
                    Button(
                        onClick = { import { BrowserYouTubeLogin.read(workingCandidate).also { YouTubeSessionSource.remember(workingCandidate) } to workingCandidate.label } },
                        enabled = !busy && !accountState.busy
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text(desktopText("Connecting..."))
                        } else {
                            Text(desktopText("Connect"))
                        }
                    }
                }
            }
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        message?.let { msg ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (isErrorMessage) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                border = BorderStroke(1.dp, if (isErrorMessage) MaterialTheme.colorScheme.error.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        if (isErrorMessage) Icons.Default.Close else Icons.Default.CheckCircle,
                        null,
                        tint = if (isErrorMessage) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isErrorMessage) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { message = null }, modifier = Modifier.size(18.dp)) {
                        Icon(Icons.Default.Close, desktopText("Dismiss"), tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        Text(
            text = if (signedIn) "Switch YouTube Music session from another browser:" else "Select the browser profile where you're signed into YouTube Music:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when {
            profiles == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            profiles!!.isEmpty() -> Text(desktopText("No supported browser profiles found. You can paste a YouTube cookie below."))
            else -> profiles!!.forEach { profile ->
                val isCurrentlyActive = signedIn && (workingCandidate?.label == profile.label || profile.label == label)
                val isCandidate = !signedIn && workingCandidate?.label == profile.label
                val isChromeOrEdge = profile.browser == "Chrome" || profile.browser == "Edge" || profile.browser == "Brave"
                val subtitle = when {
                    isCurrentlyActive -> "Active session connected on this PC"
                    isCandidate -> "Same Google account as Auralis • Click to connect"
                    isChromeOrEdge -> "App-Bound protected by Chrome • Click to paste cookie"
                    else -> "Click to import session"
                }
                val icon = when (profile.browser) {
                    "Chrome" -> Icons.Outlined.Public
                    "Edge" -> Icons.Outlined.Language
                    "Firefox" -> Icons.Outlined.TravelExplore
                    else -> Icons.Outlined.Language
                }
                GlassRow(
                    label = profile.label,
                    subtitle = subtitle,
                    leadingIcon = icon,
                    trailingBadge = when {
                        isCurrentlyActive -> "Connected"
                        isCandidate -> "Ready"
                        isChromeOrEdge -> "Protected"
                        else -> null
                    },
                    trailingBadgeColor = when {
                        isCurrentlyActive -> MaterialTheme.colorScheme.primary
                        isCandidate -> MaterialTheme.colorScheme.primary
                        isChromeOrEdge -> Color(0xFFF59E0B)
                        else -> null
                    },
                    trailingIcon = if (isCurrentlyActive) Icons.Default.CheckCircle else null,
                    isActive = isCurrentlyActive,
                    enabled = !busy && !accountState.busy
                ) {
                    if (isCurrentlyActive) {
                        message = "This profile is already connected."
                        isErrorMessage = false
                    } else if (isChromeOrEdge) {
                        paste = true
                        message = "Chrome & Edge cookies are protected by Windows App-Bound encryption. Please copy your cookie from Chrome DevTools (F12) and paste it below."
                        isErrorMessage = false
                    } else {
                        import { BrowserYouTubeLogin.read(profile).also { YouTubeSessionSource.remember(profile) } to profile.label }
                    }
                }
            }
        }

        // ─────────────────────────────────────────────────────────────
        // 4. FOURTH OPTION (LAST): PASTE COOKIE MANUALLY
        // ─────────────────────────────────────────────────────────────
        GlassRow(
            label = "Paste Cookie Manually",
            subtitle = if (paste) "Hide cookie input field" else "Advanced • Paste YouTube Cookie header directly",
            leadingIcon = Icons.Outlined.ContentPaste,
            enabled = !busy && !accountState.busy
        ) {
            paste = !paste
        }

        AnimatedVisibility(visible = paste) {
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        desktopText("How to copy your cookie in Chrome / Edge:"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        desktopText("1. Open music.youtube.com and sign in\n2. Press F12 → go to the 'Network' tab\n3. Click any request, copy the 'Cookie' header and paste it here"),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )

                    OutlinedTextField(
                        value = cookie,
                        onValueChange = { cookie = it },
                        label = { Text(desktopText("YouTube Cookie Header")) },
                        placeholder = { Text(desktopText("Paste Cookie: SID=... or __Secure-3PSID=...")) },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showCookieText) VisualTransformation.None else PasswordVisualTransformation(),
                        enabled = !busy && !accountState.busy,
                        maxLines = 3,
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (cookie.isNotBlank()) {
                                    IconButton(onClick = { cookie = "" }) {
                                        Icon(Icons.Default.Close, desktopText("Clear cookie"))
                                    }
                                    IconButton(onClick = { showCookieText = !showCookieText }) {
                                        Icon(
                                            if (showCookieText) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            if (showCookieText) "Hide cookie" else "Show cookie"
                                        )
                                    }
                                }
                                IconButton(onClick = {
                                    try {
                                        val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                        val text = cb.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
                                        if (!text.isNullOrBlank()) cookie = text.trim()
                                    } catch (_: Exception) {}
                                }) {
                                    Icon(Icons.Outlined.ContentPaste, desktopText("Paste from clipboard"))
                                }
                            }
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                import {
                                    val cleaned = cookie.trim().removePrefix("Cookie:").removePrefix("cookie:").trim()
                                    // A pasted cookie has no browser profile to renew from.
                                    YouTubeSessionSource.forget()
                                    cleaned to "YouTube Music (Cookie)"
                                }
                            },
                            enabled = cookie.isNotBlank() && !busy && !accountState.busy
                        ) {
                            Text(desktopText("Connect YouTube Music"))
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    runCatching {
                                        java.awt.Desktop.getDesktop().browse(java.net.URI("https://music.youtube.com/"))
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Outlined.OpenInBrowser, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(desktopText("Open in Browser"))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Google's account chooser for YouTube ("Choose an account to continue to YouTube"; email and
 * password when the browser has no Google account yet), landing on YouTube Music when done.
 * ServiceLogin showed "Verify it's you" for an account already signed in instead of the list.
 */
internal const val YouTubeGoogleSignInUrl =
    "https://accounts.google.com/AccountChooser?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F"

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
    }
}
