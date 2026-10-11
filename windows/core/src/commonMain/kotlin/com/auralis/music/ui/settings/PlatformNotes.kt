package com.auralis.music.ui.settings

/**
 * Why a phone feature isn't available on this platform, shown instead of a button that does
 * nothing. Desktop-only text, so it isn't in the phone's translations yet.
 */
object PlatformNotes {
    const val noSystemAppSettings = "Windows has no app settings page for Auralis."
    const val noInAppUpdates = "Windows updates come as a new installer for now; in-app updates are on the phone."
    const val appLanguageFollowsSystem = "On Windows, Auralis uses your Windows display language."
    const val phoneOnly = "This part of Auralis is on the phone for now."
    const val noWebSignIn = "Signing in to YouTube or Spotify needs a built-in browser, which the Windows app doesn't have yet. Playlist links still import from Library."
    const val noFirebaseSignIn = "Signing in to your Auralis account isn't on Windows yet: it uses Google's Android sign-in. Your library on this PC is saved locally."
    const val noListenTogether = "Listen Together needs your Auralis account, which isn't on Windows yet."
    const val noDownloads = "Downloads aren't available on Windows yet."
}
