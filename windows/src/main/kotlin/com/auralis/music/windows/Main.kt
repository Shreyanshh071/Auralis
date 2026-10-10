package com.auralis.music.windows

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.auralis.music.data.network.AlbumMetadataResolver
import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.SearchSuggestionsClient
import com.auralis.music.data.network.YouTubeSession
import com.auralis.music.data.repository.KeyValueSearchHistoryStore
import com.auralis.music.data.repository.SearchRepositoryImpl
import com.auralis.music.util.Log
import com.auralis.music.util.PropertiesFileKeyValueStore
import com.auralis.music.util.desktopAppDataDir
import javax.imageio.ImageIO

private const val TAG = "AuralisWindows"

fun main() {
    Log.i(TAG, "Starting Auralis for Windows")
    // Same startup as the phone's: the YouTube session and the search lookup caches load from their
    // own files under %APPDATA%\Auralis (separate, so signing out never clears the lookup cache).
    val dataDir = desktopAppDataDir()
    YouTubeSession.init(PropertiesFileKeyValueStore(java.io.File(dataDir, "youtube-session.properties")))
    val searchLookups = PropertiesFileKeyValueStore(java.io.File(dataDir, "search-lookups.properties"))
    AlbumMetadataResolver.persistTo(searchLookups)
    InnerTubeClient.persistReleaseTypesTo(searchLookups)
    val searchRepository = SearchRepositoryImpl(
        InnerTubeClient(),
        SearchSuggestionsClient(),
        KeyValueSearchHistoryStore(PropertiesFileKeyValueStore(java.io.File(dataDir, "search-history.properties")))
    )

    val icon = Thread.currentThread().contextClassLoader.getResourceAsStream("auralis_logo.png")
        ?.use { BitmapPainter(ImageIO.read(it).toComposeImageBitmap()) }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Auralis",
            icon = icon,
            state = rememberWindowState(size = DpSize(1280.dp, 820.dp))
        ) {
            window.minimumSize = java.awt.Dimension(900, 600)
            AuralisDesktopApp(searchRepository)
        }
    }
}
