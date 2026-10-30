package com.auralis.music.ui.settings

/**
 * Why a phone feature isn't available on this platform, shown instead of a button that does
 * nothing. Desktop-only text, so it isn't in the phone's translations yet.
 */
object PlatformNotes {
    private val isMac: Boolean get() = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
    val noSystemAppSettings: String get() = if (isMac) "macOS has no app settings page for Auralis." else "Windows has no app settings page for Auralis."
    val noInAppUpdates: String get() = if (isMac) "macOS updates come as a new installer for now; in-app updates are on the phone." else "Windows updates come as a new installer for now; in-app updates are on the phone."
    val appLanguageFollowsSystem: String get() = if (isMac) "On macOS, Auralis uses your macOS display language." else "On Windows, Auralis uses your Windows display language."
    const val phoneOnly = "This part of Auralis is on the phone for now."
    val noWebSignIn: String get() = if (isMac) "Signing in to YouTube or Spotify needs a built-in browser, which the desktop app doesn't have yet. Playlist links still import from Library." else "Signing in to YouTube or Spotify needs a built-in browser, which the Windows app doesn't have yet. Playlist links still import from Library."
    val noFirebaseSignIn: String get() = if (isMac) "Signing in to your Auralis account isn't on macOS yet: it uses Google's Android sign-in. Your library on this Mac is saved locally." else "Signing in to your Auralis account isn't on Windows yet: it uses Google's Android sign-in. Your library on this PC is saved locally."
    val noListenTogether: String get() = if (isMac) "Listen Together needs your Auralis account, which isn't on macOS yet." else "Listen Together needs your Auralis account, which isn't on Windows yet."
    val noDownloads: String get() = if (isMac) "Downloads aren't available on macOS yet." else "Downloads aren't available on Windows yet."
}
