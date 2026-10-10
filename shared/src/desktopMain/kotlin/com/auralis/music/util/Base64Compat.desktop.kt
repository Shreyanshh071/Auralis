package com.auralis.music.util

actual object Base64Compat {
    // The MIME decoder ignores line breaks and other non-alphabet characters, as Android's does.
    actual fun decode(text: String): ByteArray = java.util.Base64.getMimeDecoder().decode(text)
}
