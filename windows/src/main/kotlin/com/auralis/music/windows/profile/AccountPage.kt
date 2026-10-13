package com.auralis.music.windows.profile

import com.auralis.music.ui.components.Button
import com.auralis.music.ui.components.TextButton
import com.auralis.music.ui.components.OutlinedButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun AccountPage(account: DesktopAccount, onBack: () -> Unit) {
    val state by account.state.collectAsState()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var create by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Auralis account", style = MaterialTheme.typography.headlineLarge)
        if (state.profile != null) {
            Text("Signed in as ${state.profile!!.email}")
            Text("Your library on this PC is still local. Account cloud library sync has not been connected on Windows yet.")
            OutlinedButton(onClick = account::signOut, enabled = !state.busy) { Text("Sign out") }
        } else {
            Button(onClick = account::signInGoogle, enabled = !state.busy && account.googleAvailable) { Text("Continue with Google") }
            if (state.browser) TextButton(onClick = account::cancelGoogle) { Text("Cancel browser sign-in") }
            Text("Sign in with your Auralis email and password. Gmail addresses work here when the account has a password.")
            OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, enabled = !state.busy)
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, enabled = !state.busy, visualTransformation = PasswordVisualTransformation())
            Button(onClick = { account.signIn(email, password, create); password = "" }, enabled = !state.busy && email.contains('@') && password.isNotEmpty()) {
                Text(if (create) "Create account" else "Sign in")
            }
            TextButton(onClick = { create = !create }, enabled = !state.busy) { Text(if (create) "Already have an account? Sign in" else "Create an account") }
            TextButton(onClick = { account.reset(email) }, enabled = !state.busy && email.contains('@')) { Text("Forgot password") }
            Text("Your browser handles Google sign-in. Auralis never sees your Google password.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (state.busy) CircularProgressIndicator()
    }
}
