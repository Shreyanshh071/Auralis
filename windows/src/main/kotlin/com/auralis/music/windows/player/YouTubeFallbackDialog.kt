package com.auralis.music.windows.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import ca.weblite.webview.swing.WebViewComponent
import com.auralis.music.desktop.playback.DesktopPlaybackController.YouTubeFallback
import com.auralis.music.desktop.playback.DesktopPlaybackMatch
import com.auralis.music.util.Log
import com.sun.net.httpserver.HttpServer
import java.awt.Dimension
import java.net.InetSocketAddress
import java.util.UUID

@Composable
internal fun YouTubeFallbackDialog(
    fallback: YouTubeFallback,
    onClose: () -> Unit,
    onOpenInBrowser: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onClose,
        title = "YouTube player",
        state = rememberDialogState(width = 720.dp, height = 490.dp),
    ) {
        window.minimumSize = Dimension(500, 360)
        var webView by remember(fallback) { mutableStateOf<WebViewComponent?>(null) }
        var failed by remember(fallback) { mutableStateOf(false) }
        DisposableEffect(fallback) {
            val page = runCatching { YouTubeEmbedPage(fallback) }.onFailure {
                Log.w("YouTubeFallback", "Couldn't start the player page: ${it.javaClass.simpleName}")
            }.getOrNull()
            var createdView: WebViewComponent? = null
            val view = page?.let { embedPage ->
                runCatching {
                    check(WebViewComponent.isNavigationHandlerSupported())
                    WebViewComponent.create().apply {
                        createdView = this
                        setPopupHandler(null)
                        setNavigationHandler { event ->
                            event.url() == embedPage.url || event.url() == "about:blank" ||
                                event.url().startsWith("${embedPage.embedUrl}?")
                        }
                        setUrl(embedPage.url)
                    }
                }.onFailure {
                    Log.w("YouTubeFallback", "Couldn't start the system WebView: ${it.javaClass.simpleName}")
                }.getOrNull()
            }
            webView = view
            failed = view == null
            onDispose {
                try {
                    createdView?.dispose()
                } finally {
                    page?.close()
                }
            }
        }

        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                val view = webView
                if (view != null) {
                    SwingPanel(
                        background = Color.Black,
                        factory = { view },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    Text(
                        if (failed) "The embedded YouTube player couldn't start." else "Loading YouTube player…",
                        modifier = Modifier.weight(1f).padding(12.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onOpenInBrowser) { Text("Open in browser") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}

// A real loopback HTTP page lets the native browser send a Referer on the iframe request.
// Serve only this dialog's static document, with no connection to Auralis account or playback APIs.
internal class YouTubeEmbedPage(fallback: YouTubeFallback) : AutoCloseable {
    val embedUrl: String
    val url: String
    private val server: HttpServer

    init {
        require(DesktopPlaybackMatch.isVideoId(fallback.videoId))
        embedUrl = "https://www.youtube.com/embed/${fallback.videoId}"
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val path = "/player/${UUID.randomUUID()}"
        val authority = "127.0.0.1:${server.address.port}"
        url = "http://$authority$path"
        val html = """
            <!doctype html>
            <html lang="en">
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>YouTube player</title>
            <style>
                html, body { margin: 0; height: 100%; background: #000; }
                iframe { display: block; border: 0; width: 100%; height: 100%; }
            </style>
            <iframe title="YouTube video player" src="$embedUrl?start=${fallback.startSeconds.coerceAtLeast(0L)}"
                    referrerpolicy="strict-origin-when-cross-origin" allow="encrypted-media; picture-in-picture"></iframe>
            </html>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        try {
            server.createContext(path) { exchange ->
                exchange.use {
                    if (exchange.requestMethod != "GET" || exchange.requestURI.rawPath != path ||
                        exchange.requestHeaders.getFirst("Host") != authority) {
                        exchange.sendResponseHeaders(404, -1)
                    } else {
                        exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
                        exchange.responseHeaders.set("Cache-Control", "no-store")
                        exchange.responseHeaders.set("Referrer-Policy", "strict-origin-when-cross-origin")
                        exchange.responseHeaders.set("Content-Security-Policy",
                            "default-src 'none'; style-src 'unsafe-inline'; frame-src https://www.youtube.com; frame-ancestors 'none'")
                        exchange.sendResponseHeaders(200, html.size.toLong())
                        exchange.responseBody.write(html)
                    }
                }
            }
            server.start()
        } catch (error: RuntimeException) {
            server.stop(0)
            throw error
        }
    }

    override fun close() = server.stop(0)
}
