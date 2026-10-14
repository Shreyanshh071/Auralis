package com.auralis.music.windows.profile

import com.auralis.music.ui.components.Button
import com.auralis.music.ui.components.TextButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun AccountPage(account: DesktopAccount, onBack: () -> Unit) =
    AccountPage(account = account, showBack = true, onBack = onBack)

@Composable
fun AccountPage(account: DesktopAccount, showBack: Boolean = true, onBack: () -> Unit = {}) {
    val state by account.state.collectAsState()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var create by remember { mutableStateOf(false) }

    fun submit() {
        if (!state.busy && email.contains('@') && password.isNotEmpty()) {
            account.signIn(email, password, create)
            password = ""
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(if (showBack) 24.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (showBack) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Auralis account", style = MaterialTheme.typography.headlineLarge)
        }
        if (state.profile != null) {
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
                        Icon(Icons.Default.AccountCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(state.profile!!.displayName.ifBlank { "Signed in to Auralis" }, style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Text(state.profile!!.email, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                    }
                    Button(
                        onClick = account::signOut,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                            contentColor = Color.White
                        ),
                        enabled = !state.busy
                    ) {
                        Text("Sign out")
                    }
                }
            }
            Text(
                "Your playlists, liked songs and listening stats sync with this account. Signing out removes what came from the account; playlists you imported on this PC stay.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Button(
                onClick = account::signInGoogle,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy && account.googleAvailable
            ) {
                Text("Sign in with Google")
            }
            if (state.browser) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Select your email in the browser window to finish signing in…", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = account::cancelGoogle) { Text("Cancel") }
                }
            }
            Text("Or sign in with your Auralis email and password:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() })
            )
            Button(
                onClick = { submit() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy && email.contains('@') && password.isNotEmpty()
            ) {
                Text(if (create) "Create account" else "Sign in")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = { create = !create }, enabled = !state.busy) {
                    Text(if (create) "Already have an account? Sign in" else "Create an account")
                }
                TextButton(onClick = { account.reset(email) }, enabled = !state.busy && email.contains('@')) {
                    Text("Forgot password")
                }
            }
            Text(
                "Your browser handles Google sign-in. Auralis never sees your Google password.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        state.message?.let {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(10.dp))
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
