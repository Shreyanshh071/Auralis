package com.auralis.music.util

/** Base64 decoding that skips line breaks, like android.util.Base64's DEFAULT flags. */
expect object Base64Compat {
    fun decode(text: String): ByteArray
}
