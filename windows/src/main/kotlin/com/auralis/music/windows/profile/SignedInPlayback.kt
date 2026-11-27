package com.auralis.music.windows.profile

import com.auralis.music.desktop.playback.DesktopSignedInStream
import java.io.File

/**
 * Age-restricted songs through the user's YouTube sign-in: YouTube's player script runs in an
 * unseen copy of their Chromium browser (started only when such a song is played), and requests
 * speak for the account the library uses (same account index, channel page and account IDs).
 */
internal fun installSignedInPlayback(cacheDir: File) {
    com.zemer.cipher.ZemerCipher.initialize(cacheDir) { YouTubeBrowserSignIn.chromiumExe }
    DesktopSignedInStream.account = { YouTubeMusicClient().signedInAccount() }
}
