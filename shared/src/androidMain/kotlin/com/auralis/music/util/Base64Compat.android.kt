package com.auralis.music.util

actual object Base64Compat {
    actual fun decode(text: String): ByteArray = android.util.Base64.decode(text, android.util.Base64.DEFAULT)
}
