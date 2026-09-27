// Ported from Metrolist (github.com/MetrolistGroup/Metrolist), which adapted it from NewPipe
// (github.com/TeamNewPipe/NewPipe). GPL-3.0, like Auralis.
package com.auralis.music.data.network.potoken

class PoTokenException(message: String) : Exception(message)

// to be thrown if the WebView provided by the system is broken
class BadWebViewException(message: String) : Exception(message)

fun buildExceptionForJsError(error: String): Exception {
    return if (error.contains("SyntaxError"))
        BadWebViewException(error)
    else
        PoTokenException(error)
}
