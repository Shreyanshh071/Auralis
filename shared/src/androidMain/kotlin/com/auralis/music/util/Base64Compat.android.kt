package com.auralis.music.util

actual object Base64Compat {
    actual fun decode(text: String): ByteArray = android.util.Base64.decode(text, android.util.Base64.DEFAULT)
    actual fun encodeNoWrap(bytes: ByteArray): String = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
}
