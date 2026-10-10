package com.auralis.music.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Signing in to YouTube and Spotify in an embedded browser, whose cookies become the sessions.
 * The phone uses WebViews; null where the platform has no embedded browser (the screens then
 * explain that, and link import keeps working).
 */
class WebSignIn(
    val spotifySignIn: (@Composable (onSignedIn: () -> Unit, onDismiss: () -> Unit) -> Unit)? = null,
    val youTubeSignIn: (@Composable (onSignedIn: () -> Unit, onCancel: () -> Unit) -> Unit)? = null,
    /** Forgets the YouTube session and its sign-in cookies. */
    val signOutOfYouTube: () -> Unit = { com.auralis.music.data.network.YouTubeSession.signOut() }
)

val LocalWebSignIn = staticCompositionLocalOf { WebSignIn() }
